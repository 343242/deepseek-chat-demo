package com.smart.rag.infrastructure.messaging.redis;

import com.smart.rag.infrastructure.exception.MessagingException;
import com.smart.rag.infrastructure.exception.ServiceException;
import com.smart.rag.infrastructure.exception.errorcode.MessagingErrorCode;
import com.smart.rag.infrastructure.exception.errorcode.ServiceErrorCode;
import com.smart.rag.infrastructure.messaging.DeadLetterOperations;
import com.smart.rag.infrastructure.messaging.MessageEnvelope;
import com.smart.rag.infrastructure.messaging.MessagePayloadCodec;
import com.smart.rag.infrastructure.messaging.MessagingProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.Limit;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Redis Stream DLQ 落地（design §6，R5——迁移前是 UNSUPPORTED 桩，本任务首次真正实现）。
 * <p>
 * 死信独立 stream：{@code dlq:{prefix}{topic}:{group}}（P2-10 含 group，多组不串扰）；
 * 写入统一带 {@code MAXLEN ~ dlq-trim-threshold}（P2-8，经 {@link RedisStreamDeadLetterWriter}）。
 * <p>
 * <b>多组扩展点</b>（design §6）：当前 1:1 拓扑下 public SPI 经 {@link #groupResolver}
 * （"该 topic 唯一 group"）解析 key；未来多组场景需把 API 扩展为带 group 参数——
 * 私有重载 {@code scanDeadLetters(topic, group, count)} 已预留。
 * <p>
 * 实例由 {@link RedisStreamMessageBus} 内部持有（group 解析器引用 bus 的 topic→group 注册表），
 * 非 Spring bean——避免与 bus 的循环依赖。
 */
public class RedisStreamDeadLetterOperations implements DeadLetterOperations {

    private static final Logger log = LoggerFactory.getLogger(RedisStreamDeadLetterOperations.class);

    private final RedisStreamDeadLetterWriter writer;
    private final StringRedisTemplate redisTemplate;
    private final MessagePayloadCodec codec;
    private final RedisStreamKeys keys;
    private final MessagingProperties properties;
    private final Function<String, String> groupResolver;

    public RedisStreamDeadLetterOperations(RedisStreamDeadLetterWriter writer,
                                           StringRedisTemplate redisTemplate,
                                           MessagePayloadCodec codec,
                                           RedisStreamKeys keys,
                                           MessagingProperties properties,
                                           Function<String, String> groupResolver) {
        this.writer = writer;
        this.redisTemplate = redisTemplate;
        this.codec = codec;
        this.keys = keys;
        this.properties = properties;
        this.groupResolver = groupResolver;
    }

    // ==================== DeadLetterOperations SPI ====================

    @Override
    public List<MessageEnvelope<?>> scanDeadLetters(String topic, int count) {
        return scanDeadLetters(topic, resolveGroup(topic), count);
    }

    @Override
    public void replayDeadLetter(String topic, String messageId) {
        replayDeadLetter(topic, resolveGroup(topic), messageId);
    }

    @Override
    public int deadLetterCount(String topic) {
        return deadLetterCount(topic, resolveGroup(topic));
    }

    /** 死信写入（P2-8 MAXLEN）。供 runner/sweeper 用；失败返回 false（调用方不得 XACK）。 */
    public boolean sendToDeadLetter(String topic, String group,
                                    Map<String, String> originalFields, String reason) {
        return writer.sendToDeadLetter(keys.dlqKey(topic, group), topic, group, originalFields, reason);
    }

    // ==================== 多组扩展点（私有重载） ====================

    private List<MessageEnvelope<?>> scanDeadLetters(String topic, String group, int count) {
        String dlqKey = keys.dlqKey(topic, group);
        try {
            List<MapRecord<String, Object, Object>> records = redisTemplate.opsForStream()
                .reverseRange(dlqKey, Range.unbounded(), Limit.limit().count(count));
            List<MessageEnvelope<?>> result = new ArrayList<>(records.size());
            for (MapRecord<String, Object, Object> record : records) {
                result.add(decodeDeadLetter(topic, record));
            }
            return result;
        } catch (Exception e) {
            throw new MessagingException(MessagingErrorCode.STREAM_OPERATION_FAILED,
                "DLQ 扫描失败: " + dlqKey, e);
        }
    }

    private void replayDeadLetter(String topic, String group, String messageId) {
        String dlqKey = keys.dlqKey(topic, group);
        try {
            List<MapRecord<String, Object, Object>> records = redisTemplate.opsForStream()
                .range(dlqKey, Range.closed(messageId, messageId));
            if (records.isEmpty()) {
                throw new ServiceException(ServiceErrorCode.NOT_FOUND,
                    "死信消息不存在: dlqKey=" + dlqKey + ", messageId=" + messageId);
            }
            Map<String, String> original = new LinkedHashMap<>(
                RedisStreamFields.toStringMap(records.getFirst().getValue()));
            original.remove("originalTopic");
            original.remove("reason");
            original.remove("failedAt");
            original.remove("originGroup");
            // 回灌主 stream 不带 MAXLEN（P1-5：物理裁剪由 StreamTrimTask MINID 负责）
            redisTemplate.opsForStream().add(MapRecord.create(keys.streamKey(topic), original));
            // 不从 DLQ 删除（审计保留，靠 MAXLEN 控制）
            log.info("Dead letter replayed: topic={}, dlqKey={}, messageId={}", topic, dlqKey, messageId);
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            throw new MessagingException(MessagingErrorCode.STREAM_OPERATION_FAILED,
                "DLQ 重放失败: " + dlqKey + ", messageId=" + messageId, e);
        }
    }

    private int deadLetterCount(String topic, String group) {
        String dlqKey = keys.dlqKey(topic, group);
        try {
            Long size = redisTemplate.opsForStream().size(dlqKey);
            return size == null ? 0 : size.intValue();
        } catch (Exception e) {
            throw new MessagingException(MessagingErrorCode.STREAM_OPERATION_FAILED,
                "DLQ 计数失败: " + dlqKey, e);
        }
    }

    // ==================== Helpers ====================

    private String resolveGroup(String topic) {
        String group = groupResolver.apply(topic);
        if (group == null) {
            throw new ServiceException(MessagingErrorCode.INVALID_GROUP,
                "该 topic 无已注册消费组（当前 1:1 拓扑需先 subscribe）: " + topic);
        }
        return group;
    }

    private MessageEnvelope<?> decodeDeadLetter(String topic, MapRecord<String, Object, Object> record) {
        Map<?, ?> fields = record.getValue();
        String payloadJson = RedisStreamFields.str(fields, "payload");
        Object payload;
        try {
            payload = codec.decode(RedisStreamFields.utf8(payloadJson), Object.class);
        } catch (Exception e) {
            payload = payloadJson;   // 无法解码时展示原始 JSON，不阻断扫描
        }
        String originalTopic = RedisStreamFields.nullable(RedisStreamFields.str(fields, "originalTopic"));
        long failedAt = parseLong(RedisStreamFields.str(fields, "failedAt"), 0);
        return new MessageEnvelope<>(
            record.getId().getValue(),
            originalTopic != null ? originalTopic : topic,
            null,
            payload,
            null,
            null,
            RedisStreamFields.parseHeaders(RedisStreamFields.str(fields, "headers")),
            failedAt);
    }

    private static long parseLong(String s, long fallback) {
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
