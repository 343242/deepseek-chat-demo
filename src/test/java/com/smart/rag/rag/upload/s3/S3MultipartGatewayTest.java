package com.smart.rag.rag.upload.s3;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * encodeCopySource 单元测试：x-amz-copy-source 头的百分号编码约束。
 * 回归锚点：多字节字符（中文文件名）曾被逐 char 编成 %9E3F 这类非法序列，
 * MinIO 解码得乱码并以 XMinioInvalidObjectName 拒绝（生产 204015 对象存储复制失败）。
 */
class S3MultipartGatewayTest {

    @Test
    @DisplayName("非保留 ASCII 与 / 原样保留，空格 & 括号等转义为 %XX")
    void encodesUnreservedAsciiVerbatim() {
        assertThat(S3MultipartGateway.encodeCopySource("uploads/pending/1/abc-DEF_1.2~3.pdf"))
                .isEqualTo("uploads/pending/1/abc-DEF_1.2~3.pdf");
        assertThat(S3MultipartGateway.encodeCopySource("a b&c(d)"))
                .isEqualTo("a%20b%26c%28d%29");
    }

    @Test
    @DisplayName("多字节字符按 UTF-8 字节逐个转义（鸿 → %E9%B8%BF），不再是 %9E3F 非法序列")
    void encodesMultibyteAsUtf8Bytes() {
        assertThat(S3MultipartGateway.encodeCopySource("鸿蒙"))
                .isEqualTo("%E9%B8%BF%E8%92%99");
    }

    @Test
    @DisplayName("生产故障同形 key：编码仅含合法 %XX 段且可无损还原")
    void productionKeyRoundTrips() {
        String key = "uploads/pending/1/1613c3e6/eWs3kQQ7_HUAWEI MateBook 14 鸿蒙版 用户指南-(MNTXM-24A&24B&32A,HarmonyOS 6.1_01,zh-cn).pdf";
        String encoded = S3MultipartGateway.encodeCopySource(key);

        // 每个百分号后必须恰好两位 hex（旧实现此处产生 %9E3F）
        assertThat(encoded.replaceAll("%[0-9A-F]{2}", "")).doesNotContain("%");
        // 服务端按 UTF-8 解码后应还原为原始 key
        assertThat(URLDecoder.decode(encoded, StandardCharsets.UTF_8)).isEqualTo(key);
    }
}
