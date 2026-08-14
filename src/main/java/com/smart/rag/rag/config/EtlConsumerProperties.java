package com.smart.rag.rag.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * ETL 文档消费者配置属性
 * <p>
 * 对应 application.yml 中 app.etl.consumer.* 配置项。
 * 默认值与原硬编码一致（topic=rag_index_document, group=index-group,
 * batch=5, invisible=30min），行为不变。
 */
@Component
@ConfigurationProperties(prefix = "app.etl.consumer")
public class EtlConsumerProperties {

    /** 订阅主题 */
    private String topic = "rag_index_document";

    /** 消费者组 */
    private String group = "index-group";

    /** 单次拉取批量大小 */
    private int batchSize = 5;

    /** 失败消息重新可见时间（LLM 调用耗时不可预测，需覆盖最长处理时间） */
    private java.time.Duration invisibleDuration = java.time.Duration.ofMinutes(30);

    public String getTopic() { return topic; }
    public void setTopic(String topic) { this.topic = topic; }

    public String getGroup() { return group; }
    public void setGroup(String group) { this.group = group; }

    public int getBatchSize() { return batchSize; }
    public void setBatchSize(int batchSize) { this.batchSize = batchSize; }

    public java.time.Duration getInvisibleDuration() { return invisibleDuration; }
    public void setInvisibleDuration(java.time.Duration invisibleDuration) { this.invisibleDuration = invisibleDuration; }
}
