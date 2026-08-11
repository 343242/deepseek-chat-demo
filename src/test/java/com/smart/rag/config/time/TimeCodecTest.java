package com.smart.rag.config.time;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link TimeCodec} 单元测试——验证输出格式化与双口径解析的代码级一致性。
 */
@DisplayName("TimeCodec 格式化与解析")
class TimeCodecTest {

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final String PATTERN = "yyyy-MM-dd HH:mm:ss";

    private TimeCodec codec(ZoneId zone) {
        return new TimeCodec(zone, PATTERN);
    }

    // ── 输出：format ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("format — 归一到配置时区后丢偏移")
    class Format {

        @Test
        @DisplayName("UTC 时刻按东八区展示")
        void formatsUtcInstantToShanghai() {
            TimeCodec c = codec(SHANGHAI);
            // 2026-06-20T06:30:00Z == 2026-06-20 14:30:00 +08:00
            Instant instant = Instant.parse("2026-06-20T06:30:00Z");
            assertThat(c.format(instant)).isEqualTo("2026-06-20 14:30:00");
        }

        @Test
        @DisplayName("改配置时区后输出随之变化（验证未写死）")
        void outputChangesWithConfiguredZone() {
            Instant instant = Instant.parse("2026-06-20T06:30:00Z");
            assertThat(codec(SHANGHAI).format(instant)).isEqualTo("2026-06-20 14:30:00");
            assertThat(codec(ZoneId.of("UTC")).format(instant)).isEqualTo("2026-06-20 06:30:00");
            assertThat(codec(ZoneId.of("America/New_York")).format(instant))
                    .isEqualTo("2026-06-20 02:30:00");
        }

        @Test
        @DisplayName("不同偏移的同一绝对时刻输出相同")
        void sameInstantDifferentOffsetFormatsIdentically() {
            TimeCodec c = codec(SHANGHAI);
            Instant fromUtc = Instant.parse("2026-06-20T06:30:00Z");
            Instant fromOffset = Instant.parse("2026-06-20T14:30:00+08:00");
            assertThat(c.format(fromUtc)).isEqualTo(c.format(fromOffset));
        }
    }

    // ── 解析：parse（双口径）──────────────────────────────────────────────

    @Nested
    @DisplayName("parse — 带偏移优先，无偏移按配置时区补")
    class Parse {

        @Test
        @DisplayName("带偏移 ISO-8601 串解析为绝对时刻")
        void parsesOffsetString() {
            TimeCodec c = codec(SHANGHAI);
            Instant parsed = c.parse("2026-06-20T14:30:00+08:00");
            assertThat(parsed).isEqualTo(Instant.parse("2026-06-20T06:30:00Z"));
        }

        @Test
        @DisplayName("无偏移串按配置时区补偏移")
        void parsesNoOffsetStringByConfiguredZone() {
            TimeCodec c = codec(SHANGHAI);
            Instant parsed = c.parse("2026-06-20 14:30:00");
            assertThat(parsed).isEqualTo(Instant.parse("2026-06-20T06:30:00Z"));
        }

        @Test
        @DisplayName("带偏移与无偏移两种口径解析到同一绝对时刻")
        void dualCaliberResolvesToSameInstant() {
            TimeCodec c = codec(SHANGHAI);
            Instant fromOffset = c.parse("2026-06-20T14:30:00+08:00");
            Instant fromNoOffset = c.parse("2026-06-20 14:30:00");
            assertThat(fromOffset).isEqualTo(fromNoOffset);
        }

        @Test
        @DisplayName("带不同偏移的串解析为正确绝对时刻（不依赖配置时区）")
        void offsetStringIgnoresConfiguredZone() {
            // 带偏移串的绝对时刻由串自身决定，与配置时区无关
            TimeCodec shanghai = codec(SHANGHAI);
            TimeCodec utc = codec(ZoneId.of("UTC"));
            Instant expected = Instant.parse("2026-06-20T06:30:00Z");

            assertThat(shanghai.parse("2026-06-20T14:30:00+08:00")).isEqualTo(expected);
            assertThat(utc.parse("2026-06-20T14:30:00+08:00")).isEqualTo(expected);
        }

        @Test
        @DisplayName("无偏移串在不同配置时区下解析为不同绝对时刻")
        void noOffsetStringDependsOnConfiguredZone() {
            // 同一个无偏移串 "14:30:00"，东八区解释为 06:30:00Z，UTC 解释为 14:30:00Z
            assertThat(codec(SHANGHAI).parse("2026-06-20 14:30:00"))
                    .isEqualTo(Instant.parse("2026-06-20T06:30:00Z"));
            assertThat(codec(ZoneOffset.UTC).parse("2026-06-20 14:30:00"))
                    .isEqualTo(Instant.parse("2026-06-20T14:30:00Z"));
        }
    }

    // ── 一致性：format ∘ parse round-trip ──────────────────────────────────

    @Nested
    @DisplayName("round-trip")
    class RoundTrip {

        @Test
        @DisplayName("parse 再 format 回到原无偏移串")
        void parseThenFormatRoundTrips() {
            TimeCodec c = codec(SHANGHAI);
            String original = "2026-06-20 14:30:00";
            String roundTripped = c.format(c.parse(original));
            assertThat(roundTripped).isEqualTo(original);
        }
    }
}
