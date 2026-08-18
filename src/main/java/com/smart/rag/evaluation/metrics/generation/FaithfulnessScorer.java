package com.smart.rag.evaluation.metrics.generation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * 忠实度评分器（Faithfulness）
 * <p>
 * 衡量生成的回答是否仅基于检索到的上下文，没有"幻觉"。
 * 两步分离法（对齐 RAGAS）：
 * <ol>
 *   <li>Step 1 — Claims 提取：从 answer 中提取所有独立声明</li>
 *   <li>Step 2 — Claims 验证：对每个 claim 判断是否可从 context 中推导</li>
 * </ol>
 * Faithfulness = 可推导的 claims 数 / 总 claims 数（范围 0-1）。
 * 两步流程由 {@link ClaimVerificationSupport} 提供（与 ContextRecallScorer 共用）。
 * </p>
 * <p>
 * 哨兵值约定：
 * <ul>
 *   <li>Judge 调用或 JSON 解析失败 → 返回 -1（评测无效，聚合时应过滤）</li>
 *   <li>答案本身不含任何声明（真空）→ 返回 1.0（无声明即无幻觉）</li>
 * </ul>
 * </p>
 */
@Component
public class FaithfulnessScorer {

    private static final Logger log = LoggerFactory.getLogger(FaithfulnessScorer.class);

    private final ClaimVerificationSupport claims;

    public FaithfulnessScorer(ClaimVerificationSupport claims) {
        this.claims = claims;
    }

    /**
     * 计算忠实度
     *
     * @param answer  LLM 生成的回答
     * @param context 检索到的上下文
     * @return 忠实度分数（0-1），Judge 失败返回 -1，答案无声明返回 1.0
     */
    public double score(String answer, String context) {
        Optional<List<String>> claimsOpt = claims.extractClaims(answer);
        if (claimsOpt.isEmpty()) {
            return -1; // Judge 失败
        }
        List<String> extracted = claimsOpt.get();
        if (extracted.isEmpty()) {
            log.debug("No claims extracted from answer (genuinely claim-free)");
            return 1.0; // 答案本身无声明 = 无幻觉 = 满分
        }
        return claims.verifyClaims(extracted, context);
    }
}
