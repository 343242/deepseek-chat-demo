package com.demo.chat.rag.evaluation.metrics.generation;

import com.demo.chat.rag.evaluation.judge.LlmJudge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Profile;

import java.util.List;

/**
 * 回答相关性评分器（Answer Relevance）
 * <p>
 * 衡量回答是否直接回应了用户的问题。
 * 方法（对齐 RAGAS）：
 * 1. 从 answer 反向生成 N 个可能的问题（LLM 调用）
 * 2. 计算每个生成问题与原始问题的 embedding cosine 相似度
 * 3. Answer Relevance = 平均 cosine 相似度（范围 0-1）
 * </p>
 */
@Component
@Profile("evaluation")
public class AnswerRelevanceScorer {

    private static final Logger log = LoggerFactory.getLogger(AnswerRelevanceScorer.class);

    private final LlmJudge judge;
    private final EmbeddingModel embeddingModel;

    public AnswerRelevanceScorer(LlmJudge judge, EmbeddingModel embeddingModel) {
        this.judge = judge;
        this.embeddingModel = embeddingModel;
    }

    /**
     * 计算回答相关性
     *
     * @param question 原始问题
     * @param answer   生成的回答
     * @return 回答相关性分数（0-1），失败返回 -1
     */
    public double score(String question, String answer) {
        // Step 1: 从 answer 反向生成问题
        List<String> generatedQuestions = judge.generateQuestions(answer);
        if (generatedQuestions.isEmpty()) {
            log.warn("No questions generated from answer");
            return -1;
        }

        // Step 2: 计算 embedding 相似度
        float[] originalEmbedding = embeddingModel.embed(question);
        if (originalEmbedding == null || originalEmbedding.length == 0) {
            log.warn("Failed to embed original question");
            return -1;
        }

        double totalSimilarity = 0;
        int count = 0;

        for (String genQ : generatedQuestions) {
            float[] genEmbedding = embeddingModel.embed(genQ);
            if (genEmbedding != null && genEmbedding.length > 0) {
                totalSimilarity += cosineSimilarity(originalEmbedding, genEmbedding);
                count++;
            }
        }

        return count == 0 ? 0 : totalSimilarity / count;
    }

    /**
     * 余弦相似度
     */
    private double cosineSimilarity(float[] a, float[] b) {
        double dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        double denominator = Math.sqrt(normA) * Math.sqrt(normB);
        return denominator == 0 ? 0 : dot / denominator;
    }
}
