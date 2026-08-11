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

    @Override
    public void serialize(OffsetDateTime value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
        gen.writeString(codec.format(value.toInstant()));
    }
}
