package com.smart.rag.config.time;

import org.springframework.format.Formatter;

import java.text.ParseException;
import java.util.Locale;
import java.time.OffsetDateTime;

/**
 * {@link OffsetDateTime} 的全局 Spring {@code Formatter}——覆盖 {@code @RequestParam} 绑定。
 * <p>
 * <b>复用 {@link TimeCodec}</b>（不复制解析逻辑）：{@code parse} 与 Jackson {@code deserialize}
 * 委托同一个 {@code codec.parse}，因此 {@code @RequestBody} 与 {@code @RequestParam} 接受的
 * 串语法、解析时区、回退顺序完全相同。
 */
public final class OffsetDateTimeParamFormatter implements Formatter<OffsetDateTime> {

    private final TimeCodec codec;

    public OffsetDateTimeParamFormatter(TimeCodec codec) {
        this.codec = codec;
    }

    @Override
    public String print(OffsetDateTime object, Locale locale) {
        return codec.format(object.toInstant());
    }

    @Override
    public OffsetDateTime parse(String text, Locale locale) throws ParseException {
        return OffsetDateTime.ofInstant(codec.parse(text), codec.zone());
    }
}
