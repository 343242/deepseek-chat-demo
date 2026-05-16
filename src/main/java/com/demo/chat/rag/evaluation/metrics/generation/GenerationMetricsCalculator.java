package com.demo.chat.rag.evaluation.metrics.generation;

import com.demo.chat.rag.evaluation.judge.LlmJudge;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Profile;

import java.util.List;

/**
 * 生成指标聚合计算器
 * <p>
 * 聚合四个生成侧指标：Faithfulness, Context Recall, Answer Relevance, Context Relevance。
 * </p>
 */
@Component
@Profile("evaluation")
public class GenerationMetricsCalculator {

    private static final Logger log = LoggerFactory.getLogger(GenerationMetricsCalculator.class);

    private final FaithfulnessScorer faithfulnessScorer;
    private final ContextRecallScorer contextRecallScorer;
    private final AnswerRelevanceScorer answerRelevanceScorer;
    private final ContextRelevanceScorer contextRelevanceScorer;

    public GenerationMetricsCalculator(FaithfulnessScorer faithfulnessScorer,
                                       ContextRecallScorer contextRecallScorer,
                                       AnswerRelevanceScorer answerRelevanceScorer,
                                       ContextRelevanceScorer contextRelevanceScorer) {
        this.faithfulnessScorer = faithfulnessScorer;
        this.contextRecallScorer = contextRecallScorer;
        this.answerRelevanceScorer = answerRelevanceScorer;
        this.contextRelevanceScorer = contextRelevanceScorer;
    }

    /**
     * 计算所有生成侧指标
     *
     * @param question         用户问题
     * @param answer           LLM 生成的回答
     * @param groundTruthAnswer 标准答案
     * @param contextDocs      检索到的文档片段
     * @return 生成指标（Judge 失败的指标为 -1）
     */
    public GenerationMetrics calculate(String question, String answer,
                                       String groundTruthAnswer,
                                       List<Document> contextDocs) {
        // 构建上下文文本
        String contextText = buildContextText(contextDocs);

        // 计算各项指标
        double faithfulness = faithfulnessScorer.score(answer, contextText);
        double contextRecall = groundTruthAnswer != null
                ? contextRecallScorer.score(groundTruthAnswer, contextText) : -1;
        double answerRelevance = answerRelevanceScorer.score(question, answer);
        double contextRelevance = contextRelevanceScorer.score(question, contextDocs);

        log.debug("Generation metrics: faithfulness={}, contextRecall={}, answerRelevance={}, contextRelevance={}",
                faithfulness, contextRecall, answerRelevance, contextRelevance);

        return new GenerationMetrics(faithfulness, contextRecall, answerRelevance, contextRelevance);
    }

    private String buildContextText(List<Document> docs) {
        if (docs == null || docs.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < docs.size(); i++) {
            sb.append("片段").append(i + 1).append("：\n");
            sb.append(docs.get(i).getText()).append("\n\n");
        }
        return sb.toString();
    }
}
