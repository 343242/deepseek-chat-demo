package com.smart.rag.infrastructure.messaging.redis;

import com.smart.rag.infrastructure.messaging.MessagingProperties;

/**
 * Redis Stream key 解析集中化（design §1，评审"通用性"）——统一解析主 stream / dlq /
 * retry-hash / retry-zset 四类 key，避免改 key 方案时多处修改。
 * <p>
 * key 维度（P2-10）：retry-zset / retry-hash / dlq key 一律含 {@code :{group}}——
 * 失败是 per-group-consumer 事件，PEL 本身就是 per-group，retry/dlq 维度对齐，多组不串扰。
 * <pre>
 *   stream:      stream:{prefix}{topic}
 *   dlq:         dlq:{prefix}{topic}:{group}
 *   retry-hash:  retry:{prefix}{topic}:{group}
 *   retry-zset:  retry-zset:{prefix}{topic}:{group}
 * </pre>
 */
public class RedisStreamKeys {

    private final MessagingProperties properties;

    public RedisStreamKeys(MessagingProperties properties) {
        this.properties = properties;
    }

    private String fullTopic(String topic) {
        return properties.topicPrefix() + topic;
    }

    /** 主 stream key：{@code stream:{topicPrefix}{topic}}。 */
    public String streamKey(String topic) {
        return properties.redis().streamPrefix() + fullTopic(topic);
    }

    /** 死信 stream key：{@code dlq:{topicPrefix}{topic}:{group}}（P2-10 含 group）。 */
    public String dlqKey(String topic, String group) {
        return properties.redis().dlqPrefix() + fullTopic(topic) + ":" + group;
    }

    /** 重试 payload hash key：{@code retry:{topicPrefix}{topic}:{group}}（P2-10 含 group）。 */
    public String retryHashKey(String topic, String group) {
        return properties.redis().retryPrefix() + fullTopic(topic) + ":" + group;
    }

    /** 延迟重试 zset key：{@code retry-zset:{topicPrefix}{topic}:{group}}（P2-10 含 group）。 */
    public String retryZsetKey(String topic, String group) {
        return properties.redis().retryZsetPrefix() + fullTopic(topic) + ":" + group;
    }
}
