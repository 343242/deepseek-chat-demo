package com.smart.rag.config.time;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;

import java.io.IOException;
import java.time.Instant;

/**
 * {@link Instant} 的 Jackson 反序列化器——委托 {@link TimeCodec#parse}，
 * 双口径解析（带偏移 ISO-8601 优先，无偏移按配置时区补），返回绝对时刻。
 */
public final class InstantJsonDeserializer extends JsonDeserializer<Instant> {

    private final TimeCodec codec;

    public InstantJsonDeserializer(TimeCodec codec) {
        this.codec = codec;
    }

    @Override
    public Instant deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        return codec.parse(p.getValueAsString());
    }
}
