package com.smart.rag.config.time;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;

import java.io.IOException;
import java.time.Instant;

/**
 * {@link Instant} 的 Jackson 序列化器——委托 {@link TimeCodec}，
 * 输出 {@code yyyy-MM-dd HH:mm:ss} 无偏移串。
 * <p>
 * {@code Instant} 既出现在内部 {@code OutboxEntry}（epoch 语义），也直接出现在对外
 * {@code TraceEventVO}/{@code AgentEventVO}，两类场景由同一套全局序列化配置统一格式化。
 */
public final class InstantJsonSerializer extends JsonSerializer<Instant> {

    private final TimeCodec codec;

    public InstantJsonSerializer(TimeCodec codec) {
        this.codec = codec;
    }

    /**
     * 必须覆写：Spring Framework 6.2 的 {@code Jackson2ObjectMapperBuilder.serializers(...)}
     * 急切校验 {@code handledType()}（null 或 Object 即抛 "Unknown handled type"），
     * 而 {@code JsonSerializer<T>} 默认返回 {@code Object.class}，泛型参数不会自动解析。
     */
    @Override
    public Class<Instant> handledType() {
        return Instant.class;
    }

    @Override
    public void serialize(Instant value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
        gen.writeString(codec.format(value));
    }
}
