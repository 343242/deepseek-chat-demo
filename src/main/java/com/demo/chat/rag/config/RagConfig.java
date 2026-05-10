package com.demo.chat.rag.config;

import com.demo.chat.rag.chunk.ParentDocumentPostProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;
import org.springframework.ai.rag.retrieval.search.VectorStoreDocumentRetriever;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * RAG 配置
 * <p>
 * 使用 RetrievalAugmentationAdvisor + ParentDocumentPostProcessor，
 * 实现子切分精准检索 → 父文档完整上下文注入。
 * </p>
 */
@Configuration
public class RagConfig {

    private static final Logger log = LoggerFactory.getLogger(RagConfig.class);

    @Bean
    public ParentDocumentPostProcessor parentDocumentPostProcessor() {
        log.info("ParentDocumentPostProcessor registered");
        return new ParentDocumentPostProcessor();
    }

    @Bean
    public RetrievalAugmentationAdvisor retrievalAugmentationAdvisor(
            VectorStore vectorStore,
            ParentDocumentPostProcessor parentDocumentPostProcessor) {

        log.info("RetrievalAugmentationAdvisor initialized with ParentDocumentPostProcessor");

        DocumentRetriever retriever = VectorStoreDocumentRetriever.builder()
                .vectorStore(vectorStore)
                .similarityThreshold(0.5)
                .build();

        return RetrievalAugmentationAdvisor.builder()
                .documentRetriever(retriever)
                .documentPostProcessors(List.of(parentDocumentPostProcessor))
                .build();
    }
}
