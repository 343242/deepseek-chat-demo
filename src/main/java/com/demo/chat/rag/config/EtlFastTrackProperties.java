package com.demo.chat.rag.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;

/**
 * ETL 快速通道配置属性
 * <p>
 * 当文档数量 ≤ maxDocCount 且总大小 ≤ maxTotalSize 时，
 * 触发快速通道：解析后直接写入 BM25 全文检索，后续切分+向量化异步执行。
 * <p>
 * 对应 application.yml 中 app.etl.fast-track.* 配置项。
 */
@Component
@ConfigurationProperties(prefix = "app.etl.fast-track")
public class EtlFastTrackProperties {

    /** 是否启用快速通道 */
    private boolean enabled = true;

    /** 最大文档数量阈值 */
    private int maxDocCount = 10;

    /** 最大总大小阈值（支持 DataSize 格式如 5MB, 10MB） */
    private String maxTotalSize = "5MB";

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public int getMaxDocCount() { return maxDocCount; }
    public void setMaxDocCount(int maxDocCount) { this.maxDocCount = maxDocCount; }

    public String getMaxTotalSize() { return maxTotalSize; }
    public void setMaxTotalSize(String maxTotalSize) { this.maxTotalSize = maxTotalSize; }

    /** 解析为字节数 */
    public long getMaxTotalSizeBytes() {
        return DataSize.parse(maxTotalSize).toBytes();
    }
}
