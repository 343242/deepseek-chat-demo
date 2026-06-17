package com.smart.rag.common.uuid;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.UUID;

/**
 * UUIDv7 生成器（RFC 9562）
 * <p>
 * 基于 Unix 时间戳（毫秒精度）的有序 UUID，兼顾唯一性和时间排序。
 * <p>
 * Bit 布局（共 128 位）：
 * <pre>
 * ┌─────────────────────────────────────────────────────────────┐
 * │ 48b unix_ms │ 4b ver(7) │ 12b rand_a │ 2b var(10) │ 62b rand_b │
 * └─────────────────────────────────────────────────────────────┘
 * </pre>
 * <p>
 * 特性：
 * <ul>
 *   <li>时间有序：前 48 位是毫秒级 Unix 时间戳，生成的 UUID 按时间递增</li>
 *   <li>全局唯一：时间戳 + 随机数，冲突概率可忽略</li>
 *   <li>兼容标准：version=7, variant=10，符合 RFC 9562</li>
 *   <li>可排序：作为数据库主键或索引键时性能优于 UUIDv4</li>
 * </ul>
 */
public final class UuidV7 {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private UuidV7() {}

    /**
     * 生成一个新的 UUIDv7
     *
     * @return 基于当前时间戳的 UUIDv7
     */
    public static UUID generate() {
        return generate(Instant.now());
    }

    /**
     * 生成指定时间的 UUIDv7（便于测试）
     */
    static UUID generate(Instant instant) {
        long unixMs = instant.toEpochMilli();

        // 生成 74 位随机数
        byte[] randomBytes = new byte[10]; // 80 bits, 只用 74 bits
        SECURE_RANDOM.nextBytes(randomBytes);

        // 构建 MSB：48 位时间戳 | 4 位 version(0111) | 12 位 random_a
        long msb = (unixMs & 0xFFFFFFFFFFFFL) << 16;       // 48 位时间戳左移 16 位
        msb |= 0x7000L;                                      // version = 7
        msb |= (randomBytes[0] & 0x0FL) << 8;               // random_a 高 4 位
        msb |= (randomBytes[1] & 0xFFL);                     // random_a 低 8 位

        // 构建 LSB：2 位 variant(10) | 62 位 random_b
        long lsb = ((randomBytes[2] & 0x3FL) << 56);         // variant=10 + random_b 高 6 位
        for (int i = 3; i < 10; i++) {
            lsb |= ((long) (randomBytes[i] & 0xFF)) << (8 * (9 - i));
        }

        return new UUID(msb, lsb);
    }

    /**
     * 从 UUIDv7 中提取 Unix 毫秒时间戳
     *
     * @param uuid UUIDv7
     * @return Unix 毫秒时间戳，如果不是 UUIDv7 返回 null
     */
    public static Long extractTimestamp(UUID uuid) {
        if (uuid.version() != 7) {
            return null;
        }
        return uuid.getMostSignificantBits() >>> 16;
    }

    /**
     * 生成 UUIDv7 的字符串表示（无连字符，32 位十六进制）
     * <p>
     * 适合用作 URL 路径参数或数据库索引键，比带连字符的格式更紧凑。
     */
    public static String generateCompact() {
        return generate().toString().replace("-", "");
    }
}
