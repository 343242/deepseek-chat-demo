package com.smart.rag.evaluation.metrics.generation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * 上下文召回率评分器（Context Recall）
 * <p>
 * 衡量检索到的上下文是否足够完整地覆盖了标准答案的所有要点。
 * 方向与 Faithfulness 相反：标准答案 claims → context 支撑。
 * 两步流程由 {@link ClaimVerificationSupport} 提供（与 FaithfulnessScorer 共用），
 * 只是将 answer 替换为 ground_truth_answer。
 * </p>
 * <p>
 * 哨兵值约定：Judge 失败/解析失败 → -1；标准答案本身无声明 → 1.0。
 * </p>
 */
@Component
public class ContextRecallScorer {

    private static final Logger log = LoggerFactory.getLogger(ContextRecallScorer.class);

    private final ClaimVerificationSupport claims;

    public ContextRecallScorer(ClaimVerificationSupport claims) {
        this.claims = claims;
    }

    /**
     * 计算上下文召回率
     *
     * @param groundTruthAnswer 标准答案
     * @param context           检索到的上下文
     * @return 上下文召回率（0-1），Judge 失败返回 -1，标准答案无声明返回 1.0
     */
    public double score(String groundTruthAnswer, String context) {
        Optional<List<String>> claimsOpt = claims.extractClaims(groundTruthAnswer);
        if (claimsOpt.isEmpty()) {
            return -1; // Judge 失败
        }
        List<String> extracted = claimsOpt.get();
        if (extracted.isEmpty()) {
            log.debug("No claims extracted from ground truth answer (genuinely claim-free)");
            return 1.0;
        }
        return claims.verifyClaims(extracted, context);
    }
}
