package com.smart.rag.rag.sse;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.smart.rag.rag.event.DocumentStatusChangedEvent;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.ByteBufInputStream;
import io.netty.buffer.ByteBufOutputStream;
import org.redisson.client.codec.BaseCodec;
import org.redisson.client.handler.State;
import org.redisson.client.protocol.Decoder;
import org.redisson.client.protocol.Encoder;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * 文档状态 SSE 广播专用 Redisson {@link org.redisson.client.codec.Codec}。
 * <p>
 * 解决全局 {@code JsonJacksonCodec}（application YAML）与 record 经 {@code RTopic} 往返不兼容的问题。
 * 全局 codec 开启 {@code DefaultTyping.NON_FINAL}（类型标记写为 {@code @class} 属性），而
 * {@link DocumentStatusChangedEvent} 是 record（隐式 {@code final}）—— {@code NON_FINAL} 跳过 final 类型，
 * 序列化时<strong>不写</strong> {@code @class}；但 {@code RTopic} 的 Pub/Sub decode 是无类型字节流、
 * 按 {@code Object} 解码、强依赖 {@code @class} 还原具体类，于是抛
 * {@code InvalidTypeIdException: missing type id property '@class'}，广播消息被静默丢弃、SSE 推送失效。
 * <p>
 * 本 codec 用一个<strong>不开 default typing</strong>的纯净 {@link ObjectMapper}，并把目标类型固化为
 * {@link DocumentStatusChangedEvent}：encode 按其 record 组件序列化为普通 JSON，decode 按其规范构造器还原，
 * 往返不依赖类型标记，record + 枚举原生支持。
 *
 * <h3>为何不复用 messaging 的 {@code MessagePayloadCodec}</h3>
 * 那是项目自定义接口（RedisStream 专用，带 envelope / 校验 / headers），与 Redisson {@code Codec}
 * 接口不兼容；{@code RTopic} 需要 Redisson {@code Codec}，故独立实现。
 *
 * @see DocumentSseRelay
 */
public final class DocumentStatusCodec extends BaseCodec {

    private final ObjectMapper objectMapper;

    public DocumentStatusCodec() {
        this.objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        // 关键：不调用 activateDefaultTyping —— record（final）既无需也不应写 @class
    }

    @Override
    public Decoder<Object> getValueDecoder() {
        return this::decode;
    }

    @Override
    public Encoder getValueEncoder() {
        return this::encode;
    }

    private DocumentStatusChangedEvent decode(ByteBuf buf, State state) throws IOException {
        try (InputStream is = new ByteBufInputStream(buf)) {
            return objectMapper.readValue(is, DocumentStatusChangedEvent.class);
        }
    }

    private ByteBuf encode(Object in) throws IOException {
        ByteBuf out = ByteBufAllocator.DEFAULT.buffer();
        try {
            ByteBufOutputStream os = new ByteBufOutputStream(out);
            // ByteBufOutputStream 同时实现 OutputStream 与 DataOutput，需显式 cast 消除 writeValue 重载歧义
            objectMapper.writeValue((OutputStream) os, in);
            return os.buffer();
        } catch (IOException | RuntimeException e) {
            out.release();
            throw e;
        }
    }
}
