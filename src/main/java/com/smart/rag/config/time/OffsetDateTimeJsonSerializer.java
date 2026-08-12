package com.smart.rag.config.time;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;

import java.io.IOException;
import java.time.OffsetDateTime;

/**
 * {@link OffsetDateTime} 的 Jackson 序列化器——委托 {@link TimeCodec}，
 * 输出 {@code yyyy-MM-dd HH:mm:ss} 无偏移串（atZoneSameInstant 在 codec 内完成）。
 */
public final class OffsetDateTimeJsonSerializer extends JsonSerializer<OffsetDateTime> {

    private final TimeCodec codec;

    public OffsetDateTimeJsonSerializer(TimeCodec codec) {
        this.codec = codec;
    }

    /**
     * 必须覆写：Spring Framework 6.2 的 {@code Jackson2ObjectMapperBuilder.serializers(...)}
     * 急切校验 {@code handledType()}（null 或 Object 即抛 "Unknown handled type"），
     * 而 {@code JsonSerializer<T>} 默认返回 {@code Object.class}，泛型参数不会自动解析。
     */
    @Override
    public Class<OffsetDateTime> handledType() {
        return OffsetDateTime.class;
    }

    @Override
    public void serialize(OffsetDateTime value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
        gen.writeString(codec.format(value.toInstant()));
    }
}
