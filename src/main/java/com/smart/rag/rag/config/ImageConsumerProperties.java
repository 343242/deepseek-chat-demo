package com.smart.rag.rag.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 图片提取消费者配置属性（design §6.9）。
 * <p>
 * 对应 application.yml 的 {@code app.etl.image.consumer.*}。
 * 并发不在此配置——{@code DocumentProperties.odlImageConcurrency}（§6.6）
 * 1:1 映射 {@code ConsumerConfig.concurrency}（runner 处理池线程数 = in-flight 许可数）。
 * <p>
 * {@code rag_extract_images} 不纳入 {@code messaging.ordered-topics} 白名单
 * （§6.9 决策：白名单是声明式纪律非运行时机制；总线不分区，同 documentId 的
 * 实际串行由 image:lock 保证，不纳入换取 relay 并行投递吞吐）。
 */
@Component
@ConfigurationProperties(prefix = "app.etl.image.consumer")
public class ImageConsumerProperties {

    /** 订阅主题 */
    private String topic = "rag_extract_images";

    /** 消费者组 */
    private String group = "image-group";

    /** 逐条消费：消息仅是触发器（manifest 在 DB），批语义无收益 */
    private int batchSize = 1;

    /** 失败消息重新可见时间。调大 >30m 须同步上调 MESSAGING_PEL_MIN_IDLE_MS（§6.9 v1.7 中-1） */
    private Duration invisibleDuration = Duration.ofMinutes(30);

    public String getTopic() { return topic; }
    public void setTopic(String topic) { this.topic = topic; }
    public String getGroup() { return group; }
    public void setGroup(String group) { this.group = group; }
    public int getBatchSize() { return batchSize; }
    public void setBatchSize(int batchSize) { this.batchSize = batchSize; }
    public Duration getInvisibleDuration() { return invisibleDuration; }
    public void setInvisibleDuration(Duration invisibleDuration) { this.invisibleDuration = invisibleDuration; }
}
