package com.smart.rag.evaluation.metrics.generation;

import com.smart.rag.evaluation.config.EvaluationProperties;
import com.smart.rag.infrastructure.concurrent.ScopeJoiner;
import com.smart.rag.infrastructure.concurrent.ScopeOptions;
import com.smart.rag.infrastructure.concurrent.ScopePolicy;
import com.smart.rag.infrastructure.concurrent.ScopedTasks;
import com.smart.rag.infrastructure.concurrent.TaskScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 噪声敏感度评分器（翻译 ragas NoiseSensitivity 语句级矩阵算法）。
 * <p>
 * 判定单元是「主张」而非片段：
 * <ul>
 *   <li>分解 reference 与 response 各自的主张</li>
 *   <li>逐片段对两组主张做 NLI（retrieved2gt / retrieved2answer 矩阵），
 *       再对 response 主张 vs reference 做一次 NLI（gt2answer）</li>
 *   <li>incorrect[j] = response 主张未被 reference 支撑；
 *       relevantCtx[i] = 片段 i 支撑任一 reference 主张；
 *       relevantFaithful[j] = 主张 j 被任一相关片段支撑</li>
 * </ul>
 * mode=relevant（ragas 默认）：score = mean(relevantFaithful ∧ incorrect)；
 * mode=irrelevant：irrelevantFaithful[j] = 被任一无关片段支撑 且 ¬relevantFaithful[j]，
 * score = mean(irrelevantFaithful ∧ incorrect)。
 * 哨兵：Judge 失败或分解为空 → -1；无片段 → 0（无片段即无噪声）。
 * 各片段 NLI 调用相互独立，经 ScopedTasks 并发（不影响判定语义，仅降延迟）。
 * </p>
 */
@Component
public class NoiseSensitivityScorer {

    private static final Logger log = LoggerFactory.getLogger(NoiseSensitivityScorer.class);

    private final ClaimVerificationSupport claims;
    private final EvaluationProperties props;
    private final ScopedTasks scopedTasks;

    public NoiseSensitivityScorer(ClaimVerificationSupport claims,
                                  EvaluationProperties props,
                                  ScopedTasks scopedTasks) {
        this.claims = claims;
        this.props = props;
        this.scopedTasks = scopedTasks;
    }

    /**
     * @param answer            生成的回答
     * @param groundTruthAnswer 标准答案
     * @param contextDocs       检索到的文档片段
     * @return 噪声敏感度（0-1，主张级命中占比），Judge 失败 -1，无片段 0
     */
    public double score(String answer, String groundTruthAnswer, List<Document> contextDocs) {
        if (contextDocs == null || contextDocs.isEmpty()) {
            return 0;
        }
        boolean irrelevantMode = "irrelevant".equals(
                props.getMetrics().getNoiseSensitivity().getMode().toLowerCase(Locale.ROOT));

        Optional<List<String>> gtClaimsOpt = claims.extractClaims(groundTruthAnswer);
        Optional<List<String>> ansClaimsOpt = claims.extractClaims(answer);
        if (gtClaimsOpt.isEmpty() || ansClaimsOpt.isEmpty()) {
            return -1;
        }
        List<String> gtClaims = gtClaimsOpt.get();
        List<String> ansClaims = ansClaimsOpt.get();
        // 分解为空则矩阵无从构建（ragas 对空矩阵 mean 为 nan），判无效
        if (gtClaims.isEmpty() || ansClaims.isEmpty()) {
            log.warn("NoiseSensitivity 分解为空: gt={}, answer={}", gtClaims.size(), ansClaims.size());
            return -1;
        }

        int nCtx = contextDocs.size();
        // retrieved2gt[gtIdx][ctxIdx] / retrieved2answer[ansIdx][ctxIdx]（逐片段 NLI，并发）
        boolean[][] gtByCtx = new boolean[gtClaims.size()][nCtx];
        boolean[][] ansByCtx = new boolean[ansClaims.size()][nCtx];
        if (!judgePerContext(gtClaims, ansClaims, contextDocs, gtByCtx, ansByCtx)) {
            return -1;
        }
        // gt2answer：response 主张 vs reference
        boolean[] ansVsRef = claims.verifyClaimVerdicts(ansClaims, groundTruthAnswer);
        if (ansVsRef == null) {
            return -1;
        }

        int nAns = ansClaims.size();
        boolean[] incorrect = new boolean[nAns];
        boolean[] relevantFaithful = new boolean[nAns];
        boolean[] irrelevantFaithful = new boolean[nAns];

        boolean[] relevantCtx = new boolean[nCtx];
        for (int i = 0; i < nCtx; i++) {
            for (boolean[] row : gtByCtx) {
                if (row[i]) {
                    relevantCtx[i] = true;
                    break;
                }
            }
        }
        for (int k = 0; k < nAns; k++) {
            incorrect[k] = !ansVsRef[k];
            for (int i = 0; i < nCtx; i++) {
                if (ansByCtx[k][i] && relevantCtx[i]) {
                    relevantFaithful[k] = true;
                }
                if (ansByCtx[k][i] && !relevantCtx[i]) {
                    irrelevantFaithful[k] = true;
                }
            }
            // irrelevant 模式的排他性：同时被相关片段支撑的主张不算
            if (relevantFaithful[k]) {
                irrelevantFaithful[k] = false;
            }
        }

        int hits = 0;
        for (int k = 0; k < nAns; k++) {
            boolean faithful = irrelevantMode ? irrelevantFaithful[k] : relevantFaithful[k];
            if (faithful && incorrect[k]) {
                hits++;
            }
        }
        return (double) hits / nAns;
    }

    /** 逐片段并发 NLI：gt 主张与 ans 主张各对每个片段验证一次。任一失败返回 false。 */
    private boolean judgePerContext(List<String> gtClaims, List<String> ansClaims,
                                    List<Document> contextDocs,
                                    boolean[][] gtByCtx, boolean[][] ansByCtx) {
        int nCtx = contextDocs.size();
        record CtxVerdicts(int ctxIdx, boolean[] gt, boolean[] ans) {
        }
        try (TaskScope scope = scopedTasks.open("noise-sensitivity-nli",
                ScopeOptions.builder("noise-sensitivity-nli")
                        .policy(ScopePolicy.COLLECT_ALL)
                        .maxConcurrency(props.getRunner().getConcurrency())
                        .defaultTimeout(Duration.ofSeconds(props.getRunner().getItemTimeoutSeconds()))
                        .build())) {
            for (int i = 0; i < nCtx; i++) {
                String context = contextDocs.get(i).getText();
                int ctxIdx = i;
                scope.fork("nli-ctx-" + i, () -> new CtxVerdicts(ctxIdx,
                        claims.verifyClaimVerdicts(gtClaims, context),
                        claims.verifyClaimVerdicts(ansClaims, context)));
            }
            @SuppressWarnings("unchecked")
            var results = (List<CtxVerdicts>) (List<?>)
                    scope.join(ScopeJoiner.successfulResults(Object.class));
            if (results.size() != nCtx) {
                return false;
            }
            for (var r : results) {
                if (r.gt() == null || r.ans() == null) {
                    log.warn("NoiseSensitivity 片段 {} NLI 失败", r.ctxIdx());
                    return false;
                }
                for (int j = 0; j < gtClaims.size(); j++) {
                    gtByCtx[j][r.ctxIdx()] = r.gt()[j];
                }
                for (int k = 0; k < ansClaims.size(); k++) {
                    ansByCtx[k][r.ctxIdx()] = r.ans()[k];
                }
            }
            return true;
        }
    }
}
