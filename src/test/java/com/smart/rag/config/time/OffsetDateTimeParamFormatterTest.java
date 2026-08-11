package com.smart.rag.config.time;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link OffsetDateTimeParamFormatter} 测试（设计文档 §10 / §6.3）。
 * <p>
 * 验证 {@code @RequestParam} 入参绑定复用 {@link TimeCodec}，
 * 与 Jackson {@code deserialize} 解析口径代码级一致（双口径：带偏移/无偏移）。
 */
@DisplayName("@RequestParam OffsetDateTime 绑定（全局 Formatter）")
class OffsetDateTimeParamFormatterTest {

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    private OffsetDateTimeParamFormatter formatter() {
        return new OffsetDateTimeParamFormatter(new TimeCodec(SHANGHAI, "yyyy-MM-dd HH:mm:ss"));
    }

    @Nested
    @DisplayName("parse")
    class Parse {

        @Test
        @DisplayName("无偏移查询参数按配置时区补偏移")
        void parsesNoOffsetParam() throws Exception {
            OffsetDateTime result = formatter().parse("2026-06-20 14:30:00", Locale.getDefault());
            assertThat(result.toInstant()).isEqualTo(OffsetDateTime.parse("2026-06-20T14:30:00+08:00").toInstant());
        }

        @Test
        @DisplayName("带偏移查询参数直接解析")
        void parsesOffsetParam() throws Exception {
            OffsetDateTime result = formatter().parse("2026-06-20T14:30:00+08:00", Locale.getDefault());
            assertThat(result.toInstant()).isEqualTo(OffsetDateTime.parse("2026-06-20T14:30:00+08:00").toInstant());
        }

        @Test
        @DisplayName("双口径解析到同一绝对时刻（与反序列化器一致）")
        void dualCaliberConsistency() throws Exception {
            OffsetDateTimeParamFormatter f = formatter();
            OffsetDateTime fromOffset = f.parse("2026-06-20T14:30:00+08:00", Locale.getDefault());
            OffsetDateTime fromNoOffset = f.parse("2026-06-20 14:30:00", Locale.getDefault());
            assertThat(fromOffset.toInstant()).isEqualTo(fromNoOffset.toInstant());
        }
    }

    @Nested
    @DisplayName("print")
    class Print {

        @Test
        @DisplayName("输出无偏移 yyyy-MM-dd HH:mm:ss")
        void printsNoOffsetString() {
            String result = formatter().print(
                    OffsetDateTime.parse("2026-06-20T06:30:00Z"), Locale.getDefault());
            assertThat(result).isEqualTo("2026-06-20 14:30:00");
        }
    }
}
