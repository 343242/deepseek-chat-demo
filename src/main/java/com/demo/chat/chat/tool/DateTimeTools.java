package com.demo.chat.chat.tool;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.Locale;

/**
 * 日期时间工具集
 * <p>
 * 提供当前日期时间、星期几等信息的查询，弥补模型无法获取实时信息的不足。
 * 自动使用请求方的时区（通过 {@link LocaleContextHolder} 获取）。
 */
@Component
public class DateTimeTools {

    private static final DateTimeFormatter DATE_TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    @Tool(description = "获取当前日期和时间，包含时区信息。当需要知道「现在几点」「今天几号」等实时信息时使用此工具。")
    public String getCurrentDateTime() {
        ZoneId zoneId = LocaleContextHolder.getTimeZone().toZoneId();
        LocalDateTime now = LocalDateTime.now(zoneId);
        return now.format(DATE_TIME_FMT) + " (" + zoneId.getId() + ")";
    }

    @Tool(description = "获取当前是星期几。当需要知道「今天是周几」「明天是周几」时使用。")
    public String getCurrentWeekday(
            @ToolParam(description = "偏移天数，0=今天，1=明天，-1=昨天，默认 0") int offsetDays) {
        ZoneId zoneId = LocaleContextHolder.getTimeZone().toZoneId();
        LocalDateTime target = LocalDateTime.now(zoneId).plusDays(offsetDays);
        DayOfWeek dayOfWeek = target.getDayOfWeek();
        String weekday = dayOfWeek.getDisplayName(TextStyle.FULL, Locale.CHINESE);
        return target.format(DATE_FMT) + " 是" + weekday;
    }

    @Tool(description = "计算两个日期之间的天数差。当需要知道「距离某天还有多少天」「两个日期相差几天」时使用。")
    public String daysBetween(
            @ToolParam(description = "起始日期，格式 yyyy-MM-dd") String startDate,
            @ToolParam(description = "结束日期，格式 yyyy-MM-dd") String endDate) {
        var start = java.time.LocalDate.parse(startDate);
        var end = java.time.LocalDate.parse(endDate);
        long days = java.time.temporal.ChronoUnit.DAYS.between(start, end);
        return "从 " + startDate + " 到 " + endDate + " 相差 " + days + " 天";
    }
}
