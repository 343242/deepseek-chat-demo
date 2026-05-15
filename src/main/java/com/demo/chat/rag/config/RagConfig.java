package com.demo.chat.rag.config;

import com.demo.chat.rag.chunk.ParentDocumentPostProcessor;
import com.demo.chat.rag.mapper.VectorStoreMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.rag.preretrieval.query.transformation.RewriteQueryTransformer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RAG 配置
 * <p>
 * 完整检索 Pipeline：
 * <pre>
 * 用户查询
 *   ↓ RewriteQueryTransformer（查询改写）
 *   ↓ HybridDocumentRetriever（pgvector + BM25, RRF 融合, userId 隔离）
 *   ↓ BailianRerankPostProcessor（百炼 Rerank 语义精排）
 *   ↓ MmrDocumentPostProcessor（MMR 多样性去重）
 *   ↓ ParentDocumentPostProcessor（子块→父文档替换）
 *   ↓ 注入 LLM prompt
 * </pre>
 * <p>
 * 注意：RetrievalAugmentationAdvisor 和 DocumentRetriever 不再以全局单例 Bean 存在，
 * 改为 {@link RagAdvisorFactory} 按请求动态创建（需携带当前 userId 进行检索隔离）。
 * </p>
 */
@Configuration
@EnableConfigurationProperties(RagRetrievalProperties.class)
public class RagConfig {

    private static final Logger log = LoggerFactory.getLogger(RagConfig.class);

    // ======================== 查询改写 ========================

    @Bean
    public RewriteQueryTransformer rewriteQueryTransformer(ChatClient.Builder chatClientBuilder) {
        String template = """
                Given the following user query, rewrite it into a clear and specific search query \
                suitable for querying a {target}. Keep the core intent, remove conversational filler, \
                and use precise terminology.
                
                Original query: {query}
                
                Rewritten search query:""";

        return RewriteQueryTransformer.builder()
                .chatClientBuilder(chatClientBuilder)
                .promptTemplate(new PromptTemplate(template))
                .build();
    }

    // ======================== 后处理器 ========================

    @Bean
    public ParentDocumentPostProcessor parentDocumentPostProcessor(VectorStoreMapper vectorStoreMapper) {
        log.info("ParentDocumentPostProcessor registered");
        return new ParentDocumentPostProcessor(vectorStoreMapper);
    }

    // ======================== RAG Advisor 集成 ========================
    // 已移至 RagAdvisorFactory — 按请求动态创建带用户隔离的 Advisor
    // 原全局单例 Bean 无法按请求携带 userId filter，存在数据泄露风险
    // 参见：RagAdvisorFactory.create(userId)

    // ======================== 混合检索 ========================
    // 全局 DocumentRetriever Bean 已移除 — 改为 RagAdvisorFactory 按请求动态创建
    // 原因：检索器需要携带当前请求的 userId，无法使用全局单例
    // 参见：RagAdvisorFactory.createUserIsolatedRetriever(userId)
}
