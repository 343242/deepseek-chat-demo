package com.smart.rag.rag.health;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.AbstractHealthIndicator;
import org.springframework.boot.actuate.health.Health;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * PGvector 向量库健康指标——对 {@link VectorStore} 做 1-topK 的零向量探针查询，
 * 验证向量表/索引可读、维度匹配。供 actuator {@code /health/vector} 消费。
 * <p>
 * <b>DOWN 语义</b>：pgvector 是 RAG 核心依赖（不可用则检索链路完全断），
 * 表损坏/索引异常时标 DOWN（影响主 {@code /health}，但<b>不影响</b> K8s liveness 探针——
 * Spring Boot 默认 liveness group 只含 {@code livenessState}，不纳入业务 indicator）。
 * <p>
 * <b>探针设计</b>：构造全零向量（与配置 dimensions 等长）+ topK=1。无论是否召回文档都视为 UP——
 * 只验证「能查」不验证「有数据」。加 2s 超时保护，避免 health 探针拖死。
 */
@Component
public class VectorStoreHealthIndicator extends AbstractHealthIndicator {

    private static final Logger log = LoggerFactory.getLogger(VectorStoreHealthIndicator.class);

    private static final int PROBE_TIMEOUT_SECONDS = 2;

    private final VectorStore vectorStore;
    private final int dimensions;

    public VectorStoreHealthIndicator(VectorStore vectorStore,
                                      @Value("${spring.ai.vectorstore.pgvector.dimensions:1024}") int dimensions) {
        this.vectorStore = vectorStore;
        this.dimensions = dimensions;
    }

    @Override
    protected void doHealthCheck(Health.Builder builder) throws Exception {
        String query = buildZeroVectorQuery();
        try {
            CompletableFuture.supplyAsync(() -> vectorStore.similaritySearch(
                    SearchRequest.builder().query(query).topK(1).build()
                ))
                .get(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            // 不论结果是否为空，能成功执行即视为向量库可用
            builder.up()
                .withDetail("store", vectorStore.getClass().getSimpleName())
                .withDetail("dimensions", dimensions);
        } catch (TimeoutException e) {
            builder.down()
                .withDetail("error", "similarity search timed out after " + PROBE_TIMEOUT_SECONDS + "s")
                .withDetail("action", "Check pgvector index health and DB load");
        } catch (Exception e) {
            Throwable root = e.getCause() != null ? e.getCause() : e;
            log.warn("Vector store health check failed: {}: {}", root.getClass().getSimpleName(), root.getMessage());
            builder.down()
                .withDetail("error", root.getClass().getSimpleName() + ": " + root.getMessage())
                .withDetail("action", "Check vector_store table existence, pgvector extension, and index consistency");
        }
    }

    /**
     * 构造全零向量查询字符串（逗号分隔的浮点数，Spring AI EmbeddingModel 走 OpenAI 兼容格式）。
     * 与 dimensions 配置等长，避免维度不匹配报错。
     */
    private String buildZeroVectorQuery() {
        StringBuilder sb = new StringBuilder(dimensions * 4);
        for (int i = 0; i < dimensions; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("0.0");
        }
        return sb.toString();
    }
}
