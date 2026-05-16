package com.demo.chat.rag.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * RAG 检索优化配置（不可变 record）
 * <p>
 * 对应 application.yml 中 app.rag.* 配置项。
 * Java record 确保配置在构造后不可变，消除无意中修改配置的风险。
 * Spring Boot 通过规范构造器绑定 yml 值，compact constructor 提供默认值。
 */
@ConfigurationProperties(prefix = "app.rag")
public record RagRetrievalProperties(
        boolean queryRewriteEnabled,
        boolean hybridRetrievalEnabled,
        String ftsConfig,
        int vectorTopK,
        int bm25TopK,
        int rrfK,
        boolean rerankEnabled,
        String rerankBaseUrl,
        String rerankApiKey,
        String rerankModel,
        int rerankTopN,
        boolean mmrEnabled,
        double mmrLambda,
        int mmrTopK,
        double similarityThreshold
) {
    public RagRetrievalProperties {
        if (ftsConfig == null || ftsConfig.isBlank()) {
            ftsConfig = "jiebacfg";
        }
        if (rerankBaseUrl == null || rerankBaseUrl.isBlank()) {
            rerankBaseUrl = "https://dashscope.aliyuncs.com/compatible-api/v1";
        }
        if (rerankModel == null || rerankModel.isBlank()) {
            rerankModel = "qwen3-rerank";
        }
        // fail-fast：rerank 启用但 apiKey 为空时立即报错
        if (rerankEnabled && (rerankApiKey == null || rerankApiKey.isBlank())) {
            throw new IllegalArgumentException(
                    "app.rag.rerank-api-key must be set when app.rag.rerank-enabled is true");
        }
    }

    /**
     * 创建覆盖了 topK 参数的新实例（用于评估模块动态配置）
     * <p>
     * 仅覆盖非 null 参数，其余字段保持原值。
     */
    public RagRetrievalProperties withOverrides(Integer vectorTopKOverride, Integer bm25TopKOverride, Integer rrfKOverride) {
        return new RagRetrievalProperties(
                queryRewriteEnabled,
                hybridRetrievalEnabled,
                ftsConfig,
                vectorTopKOverride != null ? vectorTopKOverride : vectorTopK,
                bm25TopKOverride != null ? bm25TopKOverride : bm25TopK,
                rrfKOverride != null ? rrfKOverride : rrfK,
                rerankEnabled,
                rerankBaseUrl,
                rerankApiKey,
                rerankModel,
                rerankTopN,
                mmrEnabled,
                mmrLambda,
                mmrTopK,
                similarityThreshold
        );
    }
}
