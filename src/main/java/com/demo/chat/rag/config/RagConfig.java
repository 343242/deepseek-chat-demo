package com.demo.chat.rag.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RAG 配置
 * <p>
 * 注册 QuestionAnswerAdvisor Bean，供 ChatService 在 ragEnabled=true 时注入到 advisor 链。
 * 使用 builder 模式，参数可后续通过 YAML 动态配置。
 * </p>
 */
@Configuration
public class RagConfig {

    private static final Logger log = LoggerFactory.getLogger(RagConfig.class);

    /**
     * RAG Advisor：查询向量数据库，将检索到的文档作为上下文注入用户提问。
     * <p>
     * 默认参数：相似度阈值 0.7，返回 top 5 结果。
     * 不会自动加入 advisor 链——由 ChatService 根据 ragEnabled 参数决定是否使用。
     * </p>
     */
    @Bean
    public QuestionAnswerAdvisor questionAnswerAdvisor(VectorStore vectorStore) {
        log.info("QuestionAnswerAdvisor initialized with VectorStore");
        return QuestionAnswerAdvisor.builder(vectorStore)
                .searchRequest(SearchRequest.builder()
                        .similarityThreshold(0.7)
                        .topK(5)
                        .build())
                .build();
    }
}
