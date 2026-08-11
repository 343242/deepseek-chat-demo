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

    @Override
    public void serialize(Instant value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
        gen.writeString(codec.format(value));
    }
}
