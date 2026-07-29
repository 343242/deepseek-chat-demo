package com.smart.rag.rag.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 实体抽取功能配置
 */
@ConfigurationProperties(prefix = "app.rag.entity")
public class RagEntityProperties {

    /** 是否启用实体抽取（默认关闭，grayscale） */
    private boolean enabled = false;

    /** 批量 embedding 每批大小（DashScope 建议 ≤ 20） */
    private int embeddingBatchSize = 10;

    /** description 超过此字符数时 LLM 压缩后再 embed */
    private int descriptionMaxLength = 500;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public int getEmbeddingBatchSize() { return embeddingBatchSize; }
    public void setEmbeddingBatchSize(int embeddingBatchSize) { this.embeddingBatchSize = embeddingBatchSize; }

    public int getDescriptionMaxLength() { return descriptionMaxLength; }
    public void setDescriptionMaxLength(int descriptionMaxLength) { this.descriptionMaxLength = descriptionMaxLength; }
}
