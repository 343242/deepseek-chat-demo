package com.smart.rag.infrastructure.messaging.idempotent;

import com.smart.rag.infrastructure.exception.ServiceException;
import com.smart.rag.infrastructure.exception.errorcode.ServiceErrorCode;
import com.smart.rag.infrastructure.messaging.MessageHandler;
import com.smart.rag.infrastructure.messaging.MessagingMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;

/**
 * Redis-based idempotent consumption wrapper.
 * <p>
 * Uses Lua SETNX to detect and skip duplicate messages within a configurable TTL window.
 * Falls back to pass-through when Redis is unavailable.
 * <p>
 * <b>At-least-once tradeoff:</b> if Redis becomes unavailable after a successful SETNX
 * but before the handler completes, the idempotent key remains in Redis. On message
 * redelivery, the key will be detected as duplicate and the message silently skipped.
 * This is an inherent tradeoff of at-least-once delivery semantics — during Redis
 * instability windows, a small number of messages may be dropped in favor of preventing
 * duplicates.
 */
public class IdempotentHandler {

    private static final Logger log = LoggerFactory.getLogger(IdempotentHandler.class);

    private static final RedisScript<Long> IDEMPOTENT_MARK = new DefaultRedisScript<>(
        "local result = redis.call('SET', KEYS[1], '1', 'NX', 'EX', ARGV[1]) " +
        "if result then return 0 end " +
        "return 1",
        Long.class
    );

    private IdempotentHandler() {}

    public static <T> MessageHandler<T> wrap(MessageHandler<T> handler, String topic,
                                       StringRedisTemplate redis, long ttlSeconds,
                                       MessagingMetrics metrics) {
        return msg -> {
            String idempotentKey = msg.deduplicationKey();
            if (idempotentKey == null || idempotentKey.isEmpty()) {
                handler.onMessage(msg);
                return;
            }
            String redisKey = "messaging:idempotent:" + topic + ":" + idempotentKey;
            boolean marked = false;
            try {
                Long isDuplicate = redis.execute(
                    IDEMPOTENT_MARK,
                    List.of(redisKey),
                    String.valueOf(ttlSeconds));
                if (isDuplicate != null && isDuplicate == 1L) {
                    log.info("Duplicate message skipped: topic={}, key={}", topic, idempotentKey);
                    return;
                }
                marked = true;
                handler.onMessage(msg);
            } catch (Exception e) {
                if (marked) {
                    try { redis.delete(redisKey); } catch (Exception de) {
                        log.warn("Failed to delete idempotent key after handler failure: key={}", redisKey, de);
                    }
                    throw (e instanceof RuntimeException re) ? re
                        : new ServiceException(ServiceErrorCode.INTERNAL_ERROR, "消息处理失败", e);
                }
                log.warn("Idempotent check failed (Redis unavailable), delegating to business-layer: topic={}",
                    topic, e);
                metrics.recordIdempotentDegraded(topic);
                try {
                    handler.onMessage(msg);
                } catch (Exception listenerEx) {
                    log.error("Listener failed during Redis-degraded path: topic={}, key={}",
                        topic, idempotentKey, listenerEx);
                    throw listenerEx;
                }
            }
        };
    }
}
