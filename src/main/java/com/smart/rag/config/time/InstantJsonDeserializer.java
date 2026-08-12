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

    /**
     * 必须覆写：Spring Framework 6.2 的 {@code Jackson2ObjectMapperBuilder.deserializers(...)}
     * 急切校验 {@code handledType()}（null 或 Object 即抛 "Unknown handled type"），
     * 而 {@code JsonDeserializer<T>} 默认返回 {@code Object.class}，泛型参数不会自动解析。
     */
    @Override
    public Class<Instant> handledType() {
        return Instant.class;
    }

    @Override
    public Instant deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        return codec.parse(p.getValueAsString());
    }
}
