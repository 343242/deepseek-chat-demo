package com.smart.rag.config;

import com.smart.rag.config.time.InstantJsonDeserializer;
import com.smart.rag.config.time.InstantJsonSerializer;
import com.smart.rag.config.time.OffsetDateTimeJsonDeserializer;
import com.smart.rag.config.time.OffsetDateTimeJsonSerializer;
import com.smart.rag.config.time.TimeCodec;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.TimeZone;

/**
 * 全局 Jackson 时间格式化配置。
 * <p>
 * Spring Boot 3.5 的 {@code JacksonAutoConfiguration} 已自动注册 {@code JavaTimeModule}
 * （这是当前 {@code OffsetDateTime}/{@code Instant} 能以 ISO 串输出的原因）。本类不重复
 * 注册 module——真正缺的是<b>输出格式</b>：默认输出带偏移的 ISO-8601（{@code +08:00}/{@code Z}），
 * 目标是丢弃偏移的 {@code yyyy-MM-dd HH:mm:ss}。因此本类的职责是覆盖 serializer/deserializer。
 * <p>
 * 作用域：Spring 主 {@code ObjectMapper}，覆盖所有 {@code @ResponseBody} JSON 响应及
 * SSE 对象帧（{@code SseEmitter.event().data(payload)} 经同一主 mapper 序列化）。
 * <p>
 * <b>不</b>注册 {@code LocalDateTime} 的 serializer/deserializer——该类型在全栈清理后彻底消失，
 * 留它的序列化分支本身就是"为旧形态保留的兼容层"（设计文档 §6.2）。
 */
@Configuration
public class JacksonTimeConfig {

    @Bean
    TimeCodec timeCodec(AppProperties app) {
        return new TimeCodec(app.timeZone(), app.dateFormat());
    }

    @Bean
    Jackson2ObjectMapperBuilderCustomizer javaTimeCustomizer(TimeCodec codec, AppProperties app) {
        return builder -> builder
                .serializers(
                        new OffsetDateTimeJsonSerializer(codec),
                        new InstantJsonSerializer(codec))
                .deserializers(
                        new OffsetDateTimeJsonDeserializer(codec),
                        new InstantJsonDeserializer(codec))
                .timeZone(TimeZone.getTimeZone(app.timeZone()));
    }
}
