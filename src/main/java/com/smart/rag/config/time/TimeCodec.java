package com.smart.rag.config.time;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * 时间格式化与解析的唯一实现（单一事实来源）。
 * <p>
 * Jackson 序列化器/反序列化器与 Spring {@code Formatter} 全部委托本类，
 * 因此 {@code @RequestBody} 与 {@code @RequestParam} 接受的串语法、解析时区、
 * 回退顺序完全相同——这是代码级一致，不是声明。
 * <p>
 * 输出契约：任意时刻归一到配置时区后格式化为 {@code yyyy-MM-dd HH:mm:ss}，丢偏移。
 * <br>
 * 解析契约（双口径）：先尝试 ISO-8601 带偏移串（{@code 2026-06-20T14:30:00+08:00}），
 * 失败再按配置时区解释无偏移串（{@code 2026-06-20 14:30:00}）。两个分支落入同一
 * {@code Instant}，绝对时刻唯一。
 * <p>
 * 纯值类，不持有 Spring 依赖；由 {@link com.smart.rag.config.JacksonTimeConfig} 注册为单例 bean。
 */
public final class TimeCodec {

    private final DateTimeFormatter printFormatter;
    private final ZoneId zone;

    public TimeCodec(ZoneId zone, String pattern) {
        this.zone = zone;
        this.printFormatter = DateTimeFormatter.ofPattern(pattern);
    }

    /**
     * 输出：任意时刻归一到配置时区后格式化，丢偏移。
     *
     * @param instant 绝对时刻
     * @return 形如 {@code 2026-06-20 14:30:00} 的展示串
     */
    public String format(Instant instant) {
        return instant.atZone(zone).format(printFormatter);
    }

    /**
     * 配置时区。反序列化器/Formatter 还原 {@code OffsetDateTime} 时取偏移用。
     */
    public ZoneId zone() {
        return zone;
    }

    /**
     * 解析：带偏移串（ISO-8601）优先；无偏移串按配置时区补偏移。返回绝对时刻。
     *
     * @param text 输入串
     * @return 解析后的绝对时刻
     */
    public Instant parse(String text) {
        try {
            return OffsetDateTime.parse(text).toInstant();
        } catch (DateTimeParseException offset) {
            // 无偏移串（含 pattern "yyyy-MM-dd HH:mm:ss"，空格分隔）——用配置 formatter 而非
            // 默认 ISO_LOCAL_DATE_TIME（后者要求 'T' 分隔符）按配置时区补偏移。
            LocalDateTime ldt = LocalDateTime.parse(text, printFormatter);
            return ldt.atZone(zone).toInstant();
        }
    }
}
