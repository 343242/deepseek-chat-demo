package com.demo.chat.rag.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * RAG 检索优化配置
 * <p>
 * 对应 application.yml 中 app.rag.* 配置项。
 * </p>
 */
@Component
@ConfigurationProperties(prefix = "app.rag")
public class RagRetrievalProperties {

    // === 查询改写 ===
    /** 是否启用查询改写 */
    private boolean queryRewriteEnabled = true;

    // === 混合检索 ===
    /** 是否启用混合检索（向量 + BM25） */
    private boolean hybridRetrievalEnabled = true;
    /** PostgreSQL 全文检索配置名（如 jiebacfg、simple） */
    private String ftsConfig = "jiebacfg";
    /** 向量检索 topK */
    private int vectorTopK = 10;
    /** BM25 全文检索 topK */
    private int bm25TopK = 10;
    /** RRF 常数 k（越小对高排名越敏感） */
    private int rrfK = 60;

    // === Rerank ===
    /** 是否启用 Rerank */
    private boolean rerankEnabled = true;
    /** 百炼 Rerank API base URL */
    private String rerankBaseUrl = "https://dashscope.aliyuncs.com/compatible-api/v1";
    /** 百炼 API Key（复用 DashScope key） */
    private String rerankApiKey;
    /** Rerank 模型 */
    private String rerankModel = "qwen3-rerank";
    /** Rerank 返回 topN */
    private int rerankTopN = 5;

    // === MMR ===
    /** 是否启用 MMR 多样性 */
    private boolean mmrEnabled = true;
    /** MMR lambda 参数（0=最大多样性，1=最大相关性） */
    private double mmrLambda = 0.7;
    /** MMR 返回数量 */
    private int mmrTopK = 5;

    // === Parent-Child ===
    /** 相似度阈值 */
    private double similarityThreshold = 0.5;

    // Getters and Setters
    public boolean isQueryRewriteEnabled() { return queryRewriteEnabled; }
    public void setQueryRewriteEnabled(boolean queryRewriteEnabled) { this.queryRewriteEnabled = queryRewriteEnabled; }

    public boolean isHybridRetrievalEnabled() { return hybridRetrievalEnabled; }
    public void setHybridRetrievalEnabled(boolean hybridRetrievalEnabled) { this.hybridRetrievalEnabled = hybridRetrievalEnabled; }

    public String getFtsConfig() { return ftsConfig; }
    public void setFtsConfig(String ftsConfig) { this.ftsConfig = ftsConfig; }

    public int getVectorTopK() { return vectorTopK; }
    public void setVectorTopK(int vectorTopK) { this.vectorTopK = vectorTopK; }

    public int getBm25TopK() { return bm25TopK; }
    public void setBm25TopK(int bm25TopK) { this.bm25TopK = bm25TopK; }

    public int getRrfK() { return rrfK; }
    public void setRrfK(int rrfK) { this.rrfK = rrfK; }

    public boolean isRerankEnabled() { return rerankEnabled; }
    public void setRerankEnabled(boolean rerankEnabled) { this.rerankEnabled = rerankEnabled; }

    public String getRerankBaseUrl() { return rerankBaseUrl; }
    public void setRerankBaseUrl(String rerankBaseUrl) { this.rerankBaseUrl = rerankBaseUrl; }

    public String getRerankApiKey() { return rerankApiKey; }
    public void setRerankApiKey(String rerankApiKey) { this.rerankApiKey = rerankApiKey; }

    public String getRerankModel() { return rerankModel; }
    public void setRerankModel(String rerankModel) { this.rerankModel = rerankModel; }

    public int getRerankTopN() { return rerankTopN; }
    public void setRerankTopN(int rerankTopN) { this.rerankTopN = rerankTopN; }

    public boolean isMmrEnabled() { return mmrEnabled; }
    public void setMmrEnabled(boolean mmrEnabled) { this.mmrEnabled = mmrEnabled; }

    public double getMmrLambda() { return mmrLambda; }
    public void setMmrLambda(double mmrLambda) { this.mmrLambda = mmrLambda; }

    public int getMmrTopK() { return mmrTopK; }
    public void setMmrTopK(int mmrTopK) { this.mmrTopK = mmrTopK; }

    public double getSimilarityThreshold() { return similarityThreshold; }
    public void setSimilarityThreshold(double similarityThreshold) { this.similarityThreshold = similarityThreshold; }
}
