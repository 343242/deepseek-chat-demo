package com.smart.rag.infrastructure.messaging.outbox;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;

/**
 * outbox 表实体（publisher 侧持久化缓冲，MyBatis-Plus）。
 * <p>
 * {@code payload}/{@code headers} 为 JSONB 列的文本形态（codec.encode / headers JSON 序列化结果）。
 * {@code payloadType} 驱动 relay 反序列化（{@code Class.forName} + {@code JacksonMessageCodec.decode}，
 * 见 design §4）；{@code tag}/{@code hashKey} 供 relay 重建 envelope 时恢复传输元数据。
 */
@TableName("outbox")
public class OutboxEntry {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String topic;
    private String payload;
    private String payloadType;
    private String tag;
    private String dedupKey;
    private String hashKey;
    private String headers;
    private String status;
    private Integer attempts;
    private Instant nextRetryAt;
    private String failureReason;
    private Instant createdAt;
    private Instant updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getTopic() { return topic; }
    public void setTopic(String topic) { this.topic = topic; }
    public String getPayload() { return payload; }
    public void setPayload(String payload) { this.payload = payload; }
    public String getPayloadType() { return payloadType; }
    public void setPayloadType(String payloadType) { this.payloadType = payloadType; }
    public String getTag() { return tag; }
    public void setTag(String tag) { this.tag = tag; }
    public String getDedupKey() { return dedupKey; }
    public void setDedupKey(String dedupKey) { this.dedupKey = dedupKey; }
    public String getHashKey() { return hashKey; }
    public void setHashKey(String hashKey) { this.hashKey = hashKey; }
    public String getHeaders() { return headers; }
    public void setHeaders(String headers) { this.headers = headers; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Integer getAttempts() { return attempts; }
    public void setAttempts(Integer attempts) { this.attempts = attempts; }
    public Instant getNextRetryAt() { return nextRetryAt; }
    public void setNextRetryAt(Instant nextRetryAt) { this.nextRetryAt = nextRetryAt; }
    public String getFailureReason() { return failureReason; }
    public void setFailureReason(String failureReason) { this.failureReason = failureReason; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
