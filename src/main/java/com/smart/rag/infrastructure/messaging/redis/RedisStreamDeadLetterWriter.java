package com.smart.rag.infrastructure.messaging.redis;

import com.smart.rag.infrastructure.messaging.MessagingProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.RedisStreamCommands;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * DLQ 死信写入（design §3/§6，P2-8）——所有 DLQ XADD 统一带 {@code MAXLEN ~ dlq-trim-threshold}
 * （审计保留期，避免无界增长）。字段 = 原消息全字段 + 元数据（originalTopic / reason / failedAt /
 * originGroup），供 scan/replay 还原。
 * <p>
 * 自治语义：XADD 失败不抛业务异常（仅记日志 + 返回 false），调用方据此决定是否 XACK——
 * 失败时不 XACK，消息留 PEL 由 PelRecoverySweeper 兜底，杜绝丢消息。
 * <p>
 * 独立于 {@link RedisStreamDeadLetterOperations}（SPI scan/replay/count 实例在 bus 内持有，
 * 含 group 解析器）：本类无状态，runner / RetrySweeper 直接注入，避免 bus ↔ ops 循环依赖。
 */
public class RedisStreamDeadLetterWriter {

    private static final Logger log = LoggerFactory.getLogger(RedisStreamDeadLetterWriter.class);

    private final StringRedisTemplate redisTemplate;
    private final MessagingProperties properties;

    public RedisStreamDeadLetterWriter(StringRedisTemplate redisTemplate,
                                       MessagingProperties properties) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
    }

    /**
     * @return XADD 是否成功；失败时调用方不得 XACK（消息留 PEL，PelRecoverySweeper 兜底）
     */
    public boolean sendToDeadLetter(String dlqKey, String topic, String group,
                                    Map<String, String> originalFields, String reason) {
        try {
            Map<String, String> fields = new LinkedHashMap<>(originalFields);
            fields.put("originalTopic", topic);
            fields.put("reason", reason == null ? "UNKNOWN" : reason);
            fields.put("failedAt", String.valueOf(System.currentTimeMillis()));
            fields.put("originGroup", group);
            RecordId id = redisTemplate.opsForStream().add(
                MapRecord.create(dlqKey, fields),
                RedisStreamCommands.XAddOptions.maxlen(properties.redis().dlqTrimThreshold())
                    .approximateTrimming(true));
            log.warn("Message forwarded to DLQ: dlqKey={}, entryId={}, reason={}", dlqKey, id.getValue(), reason);
            return true;
        } catch (Exception e) {
            log.error("DLQ XADD failed: dlqKey={}, reason={}", dlqKey, reason, e);
            return false;
        }
    }
}
