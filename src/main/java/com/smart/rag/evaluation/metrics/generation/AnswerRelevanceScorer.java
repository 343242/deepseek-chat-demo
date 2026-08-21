package com.smart.rag.evaluation.metrics.generation;

import com.smart.rag.evaluation.config.EvaluationProperties;
import com.smart.rag.evaluation.judge.LlmJudge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 回答相关性评分器（翻译 ragas ResponseRelevancy / AnswerRelevancy）
 * <p>
 * 衡量回答是否直接回应了用户的问题。算法：
 * <ol>
 *   <li>从 answer 反向生成 strictness 个独立采样（温度 0.3，每次一个问题 + noncommittal 标志）</li>
 *   <li>计算每个生成问题与原始问题的 embedding cosine 相似度，取均值</li>
 *   <li>全部采样均 noncommittal（含糊/回避型回答）时分数归 0</li>
 * </ol>
 * score = mean(cosine) × (all_noncommittal ? 0 : 1)。哨兵：采样全部失败/问题全空 → -1。
 * </p>
 */
@Component
public class AnswerRelevanceScorer {

    private static final Logger log = LoggerFactory.getLogger(AnswerRelevanceScorer.class);

    private final LlmJudge judge;
    private final EmbeddingModel embeddingModel;
    private final EvaluationProperties props;

    public AnswerRelevanceScorer(LlmJudge judge, EmbeddingModel embeddingModel,
                                 EvaluationProperties props) {
        this.judge = judge;
        this.embeddingModel = embeddingModel;
        this.props = props;
    }

    /**
     * 计算回答相关性
     *
     * @param question 原始问题
     * @param answer   生成的回答
     * @return 回答相关性分数（0-1），失败返回 -1
     */
    public double score(String question, String answer) {
        int strictness = props.getMetrics().getAnswerRelevancy().getStrictness();
        List<LlmJudge.GeneratedQuestion> samples =
                judge.generateQuestionsWithFlags(answer, strictness);
        if (samples.isEmpty()
                || samples.stream().allMatch(s -> s.question() == null || s.question().isBlank())) {
            log.warn("No questions generated from answer (all samples empty or failed)");
            return -1;
        }
        // 全部采样 noncommittal → 归 0（ragas: int(not all_noncommittal) 乘子）
        boolean allNoncommittal = samples.stream().allMatch(LlmJudge.GeneratedQuestion::noncommittal);

        var questions = samples.stream()
                .map(LlmJudge.GeneratedQuestion::question)
                .filter(q -> q != null && !q.isBlank())
                .toList();

        float[] originalEmbedding;
        try {
            originalEmbedding = embeddingModel.embed(question);
        } catch (Exception e) {
            log.warn("Failed to embed original question", e);
            return -1;
        }
        if (originalEmbedding == null || originalEmbedding.length == 0) {
            log.warn("Failed to embed original question");
            return -1;
        }

        List<float[]> embeddings;
        try {
            embeddings = embeddingModel.embed(questions);
        } catch (Exception e) {
            log.warn("Failed to embed generated questions", e);
            return -1;
        }

        double totalSimilarity = 0;
        int count = 0;
        for (float[] genEmbedding : embeddings) {
            if (genEmbedding != null && genEmbedding.length > 0) {
                totalSimilarity += VectorMathUtil.cosine(originalEmbedding, genEmbedding);
                count++;
            }
        }

        if (count == 0) {
            log.warn("All generated-question embeddings failed");
            return -1;
        }
        double mean = totalSimilarity / count;
        return allNoncommittal ? 0.0 : mean;
    }
}
