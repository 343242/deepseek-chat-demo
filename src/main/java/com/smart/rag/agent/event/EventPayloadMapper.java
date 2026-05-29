package com.smart.rag.agent.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smart.rag.agent.event.payload.GuardrailTriggeredPayload;
import com.smart.rag.agent.event.payload.IntentClassifiedPayload;
import com.smart.rag.agent.event.payload.IntermediateAnswerPayload;
import com.smart.rag.agent.event.payload.RetrievalStrategyPayload;
import com.smart.rag.agent.event.payload.SelfReflectionPayload;
import com.smart.rag.agent.event.payload.ToolCalledPayload;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 事件 payload 序列化/反序列化映射器
 * <p>
 * 提供类型安全的 JSON 序列化与反序列化，集中管理所有事件 payload 的转换逻辑。
 * 解析失败时 log.warn 并返回 null，保证事件系统不中断主流程。
 */
@Component
public class EventPayloadMapper {

    private static final Logger log = LoggerFactory.getLogger(EventPayloadMapper.class);

    private final ObjectMapper objectMapper;

    public EventPayloadMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    // === 序列化 ===

    public String toJson(IntentClassifiedPayload payload) {
        return serialize(payload);
    }

    public String toJson(IntermediateAnswerPayload payload) {
        return serialize(payload);
    }

    public String toJson(GuardrailTriggeredPayload payload) {
        return serialize(payload);
    }

    public String toJson(SelfReflectionPayload payload) {
        return serialize(payload);
    }

    public String toJson(RetrievalStrategyPayload payload) {
        return serialize(payload);
    }

    public String toJson(ToolCalledPayload payload) {
        return serialize(payload);
    }

    // === 反序列化 ===

    @Nullable
    public IntentClassifiedPayload toIntentClassified(String json) {
        return deserialize(json, IntentClassifiedPayload.class);
    }

    @Nullable
    public IntermediateAnswerPayload toIntermediateAnswer(String json) {
        return deserialize(json, IntermediateAnswerPayload.class);
    }

    @Nullable
    public GuardrailTriggeredPayload toGuardrailTriggered(String json) {
        return deserialize(json, GuardrailTriggeredPayload.class);
    }

    @Nullable
    public SelfReflectionPayload toSelfReflection(String json) {
        return deserialize(json, SelfReflectionPayload.class);
    }

    @Nullable
    public RetrievalStrategyPayload toRetrievalStrategy(String json) {
        return deserialize(json, RetrievalStrategyPayload.class);
    }

    @Nullable
    public ToolCalledPayload toToolCalled(String json) {
        return deserialize(json, ToolCalledPayload.class);
    }

    /**
     * 泛型反序列化，供外部按类型调用
     *
     * @param json  JSON 字符串
     * @param type  目标 payload 类型
     * @return 反序列化后的 payload，解析失败返回 null
     */
    @Nullable
    public <T> T fromJson(String json, Class<T> type) {
        return deserialize(json, type);
    }

    // === 内部辅助 ===

    private String serialize(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize payload: type={}", payload.getClass().getSimpleName(), e);
            // fallback: 返回空 JSON 对象，保证写入不中断
            return "{}";
        }
    }

    @Nullable
    private <T> T deserialize(String json, Class<T> type) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            log.warn("Failed to deserialize payload: type={}, json={}", type.getSimpleName(),
                truncate(json, 100), e);
            return null;
        }
    }

    private static String truncate(String text, int maxLength) {
        if (text == null) {
            return "null";
        }
        return text.length() <= maxLength ? text : text.substring(0, maxLength) + "...";
    }
}
