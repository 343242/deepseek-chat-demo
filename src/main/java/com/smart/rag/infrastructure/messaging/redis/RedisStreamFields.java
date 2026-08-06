package com.smart.rag.infrastructure.messaging.redis;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Stream 消息字段格式工具（design §2/§3）：
 * <ul>
 *   <li>record 字段统一 String 值（{@code StreamRecords.ofStrings}）；null 字段写空串，读取时还原 null；</li>
 *   <li>{@code headers} 字段为 JSON（traceId 等随 XADD 写入，消费侧还原，TracePropagator.restore 用）；</li>
 *   <li>payload 为 JSON 文本。</li>
 * </ul>
 */
final class RedisStreamFields {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<Map<String, String>> STRING_MAP_TYPE = new TypeReference<>() {};

    private RedisStreamFields() {}

    /** 字段 map → 全 String map（null 值归一为空串，与 XADD ofStrings 契约一致）。 */
    static Map<String, String> toStringMap(Map<?, ?> fields) {
        Map<String, String> result = new HashMap<>(fields.size());
        fields.forEach((k, v) -> result.put(String.valueOf(k), v == null ? "" : String.valueOf(v)));
        return result;
    }

    static String str(Map<?, ?> fields, String key) {
        Object v = fields.get(key);
        return v == null ? "" : String.valueOf(v);
    }

    /** 空串 → null（send 侧 null 归一为空串，读取侧还原）。 */
    static String nullable(String s) {
        return s == null || s.isEmpty() ? null : s;
    }

    static String headersJson(Map<String, String> headers) {
        try {
            return JSON.writeValueAsString(headers);
        } catch (Exception e) {
            return "{}";
        }
    }

    static Map<String, String> parseHeaders(String json) {
        if (json == null || json.isEmpty()) {
            return Map.of();
        }
        try {
            Map<String, String> headers = JSON.readValue(json, STRING_MAP_TYPE);
            return headers == null ? Map.of() : Map.copyOf(headers);
        } catch (Exception e) {
            return Map.of();
        }
    }

    static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
