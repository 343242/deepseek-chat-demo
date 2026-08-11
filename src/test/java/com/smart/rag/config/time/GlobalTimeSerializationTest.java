package com.smart.rag.config.time;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.TimeZone;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 全局 Jackson 时间序列化测试（设计文档 §10）。
 * <p>
 * 模拟 {@link com.smart.rag.config.JacksonTimeConfig} 的 customizer 装配方式，
 * 验证 {@code OffsetDateTime} 与 {@code Instant} 两类字段输出均为 {@code yyyy-MM-dd HH:mm:ss}
 * 无偏移，且按配置时区取值。反序列化器双口径（带偏移/无偏移）解析到同一绝对时刻。
 * <p>
 * 不含 {@code LocalDateTime} 用例——该类型已从全栈清理，customizer 不注册其序列化器。
 */
@DisplayName("全局时间序列化：OffsetDateTime / Instant → yyyy-MM-dd HH:mm:ss")
class GlobalTimeSerializationTest {

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    /** 模拟 JacksonTimeConfig 的 customizer 效果——手工装配同一套 serializer/deserializer。 */
    private ObjectMapper buildMapper(TimeCodec codec, ZoneId zone) {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mapper.setTimeZone(TimeZone.getTimeZone(zone));
        var module = new com.fasterxml.jackson.databind.module.SimpleModule();
        module.addSerializer(OffsetDateTime.class, new OffsetDateTimeJsonSerializer(codec));
        module.addSerializer(Instant.class, new InstantJsonSerializer(codec));
        module.addDeserializer(OffsetDateTime.class, new OffsetDateTimeJsonDeserializer(codec));
        module.addDeserializer(Instant.class, new InstantJsonDeserializer(codec));
        mapper.registerModule(module);
        return mapper;
    }

    // ── 输出：序列化 ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("序列化输出格式")
    class Serialization {

        @Test
        @DisplayName("OffsetDateTime 输出无偏移 yyyy-MM-dd HH:mm:ss")
        void offsetDateTimeSerializesToNoOffsetString() throws Exception {
            TimeCodec codec = new TimeCodec(SHANGHAI, "yyyy-MM-dd HH:mm:ss");
            ObjectMapper mapper = buildMapper(codec, SHANGHAI);
            OffsetDateTime value = OffsetDateTime.parse("2026-06-20T06:30:00Z");

            String json = mapper.writeValueAsString(value);

            assertThat(json).isEqualTo("\"2026-06-20 14:30:00\"");
        }

        @Test
        @DisplayName("Instant 输出与 OffsetDateTime 同格式")
        void instantSerializesToSameFormat() throws Exception {
            TimeCodec codec = new TimeCodec(SHANGHAI, "yyyy-MM-dd HH:mm:ss");
            ObjectMapper mapper = buildMapper(codec, SHANGHAI);
            Instant value = Instant.parse("2026-06-20T06:30:00Z");

            String json = mapper.writeValueAsString(value);

            assertThat(json).isEqualTo("\"2026-06-20 14:30:00\"");
        }

        @Test
        @DisplayName("改配置时区后输出展示时区同步变化")
        void changingZoneChangesOutput() throws Exception {
            Instant value = Instant.parse("2026-06-20T06:30:00Z");

            TimeCodec shanghaiCodec = new TimeCodec(SHANGHAI, "yyyy-MM-dd HH:mm:ss");
            String shanghaiJson = buildMapper(shanghaiCodec, SHANGHAI).writeValueAsString(value);

            TimeCodec utcCodec = new TimeCodec(ZoneId.of("UTC"), "yyyy-MM-dd HH:mm:ss");
            String utcJson = buildMapper(utcCodec, ZoneId.of("UTC")).writeValueAsString(value);

            assertThat(shanghaiJson).isEqualTo("\"2026-06-20 14:30:00\"");
            assertThat(utcJson).isEqualTo("\"2026-06-20 06:30:00\"");
        }
    }

    // ── 输入：反序列化双口径（设计文档 §10 / P2-13）─────────────────────────

    @Nested
    @DisplayName("反序列化双口径")
    class Deserialization {

        @Test
        @DisplayName("@RequestBody 接受带偏移 ISO-8601 串")
        void deserializesOffsetString() throws Exception {
            TimeCodec codec = new TimeCodec(SHANGHAI, "yyyy-MM-dd HH:mm:ss");
            ObjectMapper mapper = buildMapper(codec, SHANGHAI);

            OffsetDateTime result = mapper.readValue("\"2026-06-20T14:30:00+08:00\"", OffsetDateTime.class);

            assertThat(result.toInstant()).isEqualTo(Instant.parse("2026-06-20T06:30:00Z"));
        }

        @Test
        @DisplayName("@RequestBody 接受无偏移串（按配置时区补）")
        void deserializesNoOffsetString() throws Exception {
            TimeCodec codec = new TimeCodec(SHANGHAI, "yyyy-MM-dd HH:mm:ss");
            ObjectMapper mapper = buildMapper(codec, SHANGHAI);

            OffsetDateTime result = mapper.readValue("\"2026-06-20 14:30:00\"", OffsetDateTime.class);

            assertThat(result.toInstant()).isEqualTo(Instant.parse("2026-06-20T06:30:00Z"));
        }

        @Test
        @DisplayName("带偏移与无偏移两种串解析到同一绝对时刻")
        void dualCaliberResolvesToSameInstant() throws Exception {
            TimeCodec codec = new TimeCodec(SHANGHAI, "yyyy-MM-dd HH:mm:ss");
            ObjectMapper mapper = buildMapper(codec, SHANGHAI);

            OffsetDateTime fromOffset = mapper.readValue("\"2026-06-20T14:30:00+08:00\"", OffsetDateTime.class);
            OffsetDateTime fromNoOffset = mapper.readValue("\"2026-06-20 14:30:00\"", OffsetDateTime.class);

            assertThat(fromOffset.toInstant()).isEqualTo(fromNoOffset.toInstant());
        }

        @Test
        @DisplayName("Instant 反序列化同样支持双口径")
        void instantDualCaliber() throws Exception {
            TimeCodec codec = new TimeCodec(SHANGHAI, "yyyy-MM-dd HH:mm:ss");
            ObjectMapper mapper = buildMapper(codec, SHANGHAI);

            Instant fromOffset = mapper.readValue("\"2026-06-20T14:30:00+08:00\"", Instant.class);
            Instant fromNoOffset = mapper.readValue("\"2026-06-20 14:30:00\"", Instant.class);

            assertThat(fromOffset).isEqualTo(fromNoOffset);
            assertThat(fromOffset).isEqualTo(Instant.parse("2026-06-20T06:30:00Z"));
        }
    }

    // ── DTO 仿真：record 含 OffsetDateTime + Instant 字段 ──────────────────

    record TimeEnvelope(OffsetDateTime createdAt, Instant eventTime) {}

    @Nested
    @DisplayName("DTO 字段序列化")
    class DtoFields {

        @Test
        @DisplayName("record 中 OffsetDateTime 与 Instant 字段均输出无偏移串")
        void recordFieldsSerializeCorrectly() throws Exception {
            TimeCodec codec = new TimeCodec(SHANGHAI, "yyyy-MM-dd HH:mm:ss");
            ObjectMapper mapper = buildMapper(codec, SHANGHAI);

            TimeEnvelope env = new TimeEnvelope(
                    OffsetDateTime.parse("2026-06-20T06:30:00Z"),
                    Instant.parse("2026-06-20T06:30:00Z"));

            String json = mapper.writeValueAsString(env);

            assertThat(json).contains("\"createdAt\":\"2026-06-20 14:30:00\"");
            assertThat(json).contains("\"eventTime\":\"2026-06-20 14:30:00\"");
        }
    }
}
