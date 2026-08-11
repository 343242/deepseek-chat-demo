package com.smart.rag.config;

import com.smart.rag.config.time.OffsetDateTimeParamFormatter;
import com.smart.rag.config.time.TimeCodec;
import org.springframework.context.annotation.Configuration;
import org.springframework.format.FormatterRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 注册全局 {@code Formatter<OffsetDateTime>}，覆盖 {@code @RequestParam} 入参绑定。
 * <p>
 * {@code UsageController} 的查询参数经 Spring {@code ConversionService}/{@code Formatter} 绑定，
 * 不走 Jackson 反序列化。本配置注册的 {@link OffsetDateTimeParamFormatter} 复用 {@link TimeCodec}，
 * 使 {@code @RequestParam} 与 {@code @RequestBody} 解析口径代码级一致。
 */
@Configuration
public class TimeFormattersConfig implements WebMvcConfigurer {

    private final TimeCodec codec;

    public TimeFormattersConfig(TimeCodec codec) {
        this.codec = codec;
    }

    @Override
    public void addFormatters(FormatterRegistry registry) {
        registry.addFormatter(new OffsetDateTimeParamFormatter(codec));
    }
}
