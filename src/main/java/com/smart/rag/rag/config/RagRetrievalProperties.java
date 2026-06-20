package com.smart.rag.rag.config;

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
        /** Rerank 精排保留的文档数（候选池），必须 > mmrTopK，否则调换顺序后 MMR 命中早退退化为 no-op */
        int rerankTopN,
        boolean mmrEnabled,
        double mmrLambda,
        int mmrTopK,
        double similarityThreshold,
        /** 查询改写使用的模型 ID（registry 候选 ID，如 deepseek-v4-flash，与 LlmClientRegistry 注册一致），null 使用全局默认 */
        String queryRewriteModel,
        /** 查询改写 temperature，null 使用模型默认 */
        Double queryRewriteTemperature
) {
    public RagRetrievalProperties {
        if (ftsConfig == null || ftsConfig.isBlank()) {
            ftsConfig = "jiebacfg";
        }
        // rerankTopN 未显式配置（<=0）时回退默认 20，保证旧 yml 不填仍可启动
        if (rerankTopN <= 0) {
            rerankTopN = 20;
        }
        // 候选池约束：rerankTopN 必须 > mmrTopK，否则调换顺序为 Rerank→MMR 后，
        // MMR 命中 documents.size() <= topK 早退（MmrDocumentPostProcessor#process）退化为 no-op
        if (rerankTopN <= mmrTopK) {
            throw new IllegalArgumentException(
                    "rerankTopN must be > mmrTopK (rerankTopN=" + rerankTopN
                            + ", mmrTopK=" + mmrTopK + ")，否则 MMR 退化为 no-op");
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
                rerankTopN,
                mmrEnabled,
                mmrLambda,
                mmrTopK,
                similarityThreshold,
                queryRewriteModel,
                queryRewriteTemperature
        );
    }
}
