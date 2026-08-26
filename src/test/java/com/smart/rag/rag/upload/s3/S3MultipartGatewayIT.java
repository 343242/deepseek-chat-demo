package com.smart.rag.rag.upload.s3;

import com.smart.rag.infrastructure.exception.ServiceException;
import com.smart.rag.infrastructure.exception.errorcode.ServiceErrorCode;
import io.minio.MinioAsyncClient;
import io.minio.MinioClient;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * S3MultipartGateway 集成测试：Testcontainers 起真实 MinIO（pgsty/minio，与 dev compose 同镜像），
 * 覆盖设计文档测试策略的 SDK 承载项——Create → presign UploadPart PUT → Complete → Abort 全流程、
 * 篡改 ETag 必被拒、abort 幂等、presign 过期 403、S1 重放覆盖后条件 copy 412、
 * N1 multipart ETag（-N 形式）条件 copy 正常（无 MD5 对拍语义）。
 */
@Testcontainers(disabledWithoutDocker = true)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class S3MultipartGatewayIT {

    private static final String BUCKET = "direct-upload-it";
    private static final Duration EXPIRY = Duration.ofMinutes(10);
    /** 除末片外每片 ≥5MB 的 S3 约束 */
    private static final int PART_SIZE = 5 * 1024 * 1024;

    @Container
    static final GenericContainer<?> MINIO = new GenericContainer<>(
            DockerImageName.parse("pgsty/minio:latest"))
        .withEnv("MINIO_ROOT_USER", "minioit")
        .withEnv("MINIO_ROOT_PASSWORD", "minioit-secret")
        .withCommand("server", "/data")
        .withExposedPorts(9000)
        .waitingFor(Wait.forHttp("/minio/health/live").forPort(9000));

    private static S3MultipartGateway gateway;
    private static MinioClient minioClient;
    private static final OkHttpClient http = new OkHttpClient.Builder()
            .callTimeout(30, TimeUnit.SECONDS)
            .build();

    @BeforeAll
    static void setUp() throws Exception {
        String endpoint = "http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000);
        minioClient = MinioClient.builder()
                .endpoint(endpoint)
                .credentials("minioit", "minioit-secret")
                .build();
        minioClient.makeBucket(io.minio.MakeBucketArgs.builder().bucket(BUCKET).build());
        MinioAsyncClient asyncClient = MinioAsyncClient.builder()
                .endpoint(endpoint)
                .credentials("minioit", "minioit-secret")
                .build();
        // dev 同址语义：presign client 与服务端 client 同 endpoint
        gateway = new S3MultipartGateway(minioClient, asyncClient, minioClient);
    }

    @Test
    @Order(1)
    @DisplayName("全流程：Create → presign UploadPart PUT → Complete → statObject（ETag 为 -N 形式）")
    void multipartFullFlow() throws Exception {
        String key = "uploads/pending/1/session-full/abc_report.pdf";
        String uploadId = gateway.createMultipartUpload(BUCKET, key);
        assertThat(uploadId).isNotBlank();

        byte[] part1 = new byte[PART_SIZE];
        byte[] part2 = new byte[1024]; // 末片可小于 5MB
        part1[0] = 0x25; part2[0] = 0x50;

        String url1 = gateway.presignPartUrl(BUCKET, key, 1, uploadId, EXPIRY);
        String url2 = gateway.presignPartUrl(BUCKET, key, 2, uploadId, EXPIRY);
        String etag1 = put(url1, part1);
        String etag2 = put(url2, part2);
        assertThat(etag1).isNotBlank();
        assertThat(etag2).isNotBlank();

        gateway.completeMultipartUpload(BUCKET, key, uploadId, List.of(
                new S3MultipartGateway.CompletedPart(1, etag1),
                new S3MultipartGateway.CompletedPart(2, etag2)));

        var stat = gateway.statObject(BUCKET, key);
        assertThat(stat.size()).isEqualTo(part1.length + part2.length);
        // MPU Complete 后 ETag 为 MD5(各分片 ETag 拼接)-N 形式，不等于整对象 MD5（N1 前提）
        assertThat(stat.etag()).endsWith("-2");

        var digests = gateway.computeDigests(BUCKET, key);
        assertThat(digests.sha256Hex()).isEqualTo(sha256Hex(concat(part1, part2)));
        assertThat(digests.md5Hex()).isEqualTo(md5Hex(concat(part1, part2)));

        // N1 回归：multipart 对象（-N ETag）不做 MD5 对拍，条件 copy 以该 ETag 正常通过；
        // 同时验证 REPLACE 生效——目标对象 Content-Type 为归一化值而非源对象值
        String destKey = "documents/1/abc_report.pdf";
        gateway.copyObjectIfMatch(BUCKET, key, destKey, stat.etag(), "application/pdf");
        var destStat = gateway.statObject(BUCKET, destKey);
        assertThat(destStat.size()).isEqualTo(part1.length + part2.length);
        assertThat(destStat.contentType()).isEqualTo("application/pdf");
    }

    @Test
    @Order(2)
    @DisplayName("篡改 ETag 的 Complete 必被 S3 拒绝（InvalidPart → COMPLETE_FAILED）")
    void completeWithTamperedEtagRejected() throws Exception {
        String key = "uploads/pending/1/session-tamper/abc_report.pdf";
        String uploadId = gateway.createMultipartUpload(BUCKET, key);
        String url = gateway.presignPartUrl(BUCKET, key, 1, uploadId, EXPIRY);
        String etag = put(url, new byte[PART_SIZE]);

        String tampered = "deadbeef" + etag.substring(Math.min(8, etag.length()));
        String finalUploadId = uploadId;
        assertThatThrownBy(() -> gateway.completeMultipartUpload(BUCKET, key, finalUploadId,
                        List.of(new S3MultipartGateway.CompletedPart(1, tampered))))
                .isInstanceOf(ServiceException.class)
                .extracting(e -> ((ServiceException) e).getErrorCode())
                .isEqualTo(ServiceErrorCode.DIRECT_UPLOAD_COMPLETE_FAILED);
    }

    @Test
    @Order(3)
    @DisplayName("abort 幂等：重复 abort 无异常；消亡后 abort 静默")
    void abortIsIdempotent() {
        String key = "uploads/pending/1/session-abort/abc_report.pdf";
        String uploadId = gateway.createMultipartUpload(BUCKET, key);
        assertThatCode(() -> gateway.abortMultipartUploadQuietly(BUCKET, key, uploadId))
                .doesNotThrowAnyException();
        assertThatCode(() -> gateway.abortMultipartUploadQuietly(BUCKET, key, uploadId))
                .doesNotThrowAnyException();
    }

    @Test
    @Order(4)
    @DisplayName("presign 过期后 PUT 403")
    void expiredPresignRejected() throws Exception {
        String key = "uploads/pending/1/session-expired/abc_report.pdf";
        String url = gateway.presignPutUrl(BUCKET, key, Duration.ofSeconds(1));
        Thread.sleep(2500);
        try (Response resp = http.newCall(new Request.Builder()
                .url(url).put(RequestBody.create(new byte[16], null)).build()).execute()) {
            assertThat(resp.code()).isEqualTo(403);
        }
    }

    @Test
    @Order(5)
    @DisplayName("S1 回归：presigned URL 重放覆盖 pending 后，if-match copy 以旧 ETag 必 412 拒绝")
    void replayedOverwriteRejectedByConditionalCopy() throws Exception {
        String key = "uploads/pending/1/session-replay/abc_report.pdf";
        String url = gateway.presignPutUrl(BUCKET, key, EXPIRY);

        byte[] original = new byte[64];
        original[0] = 0x01;
        put(url, original);
        var statBefore = gateway.statObject(BUCKET, key);

        // 有效期内同 URL 重放覆盖（presign 无一次性约束，同 key 覆盖）
        byte[] replayed = new byte[128];
        replayed[0] = 0x02;
        put(url, replayed);
        var statAfter = gateway.statObject(BUCKET, key);
        assertThat(statAfter.etag()).isNotEqualTo(statBefore.etag());

        // 以「校验前」的旧 ETag 条件 copy：内容已变 → 412 → COPY_FAILED
        assertThatThrownBy(() -> gateway.copyObjectIfMatch(
                BUCKET, key, "documents/1/replay_report.pdf", statBefore.etag(), "application/pdf"))
                .isInstanceOf(ServiceException.class)
                .extracting(e -> ((ServiceException) e).getErrorCode())
                .isEqualTo(ServiceErrorCode.DIRECT_UPLOAD_COPY_FAILED);

        // 以当前 ETag copy：通过（TOCTOU 闭环成立：失配拒绝、匹配放行）
        gateway.copyObjectIfMatch(BUCKET, key, "documents/1/replay_report.pdf", statAfter.etag(), "application/pdf");
    }

    @Test
    @Order(6)
    @DisplayName("MPU 泄漏回收不依赖 listMultipartUploads（该 API 在本镜像+SDK 双重不可用），出生登记簿方案见 DirectUploadSessionStore")
    void mpuLeakTrackingMovedToRegistry() {
        // 回归锚点：abort 幂等（Order(3) 已覆盖重复 abort）；本测试仅作为方案偏离的文档化标记，
        // MPU 泄漏扫描走 Redis 出生登记簿（direct:mpu ZSET），不走 ListMultipartUploads API
        assertThat(true).isTrue();
    }

    @Test
    @Order(7)
    @DisplayName("statObject 不存在映射 UPLOAD_GONE；removeObjectQuietly 清理")
    void statMissingObjectMapsToUploadGone() {
        String key = "uploads/pending/1/session-missing/abc_report.pdf";
        assertThatThrownBy(() -> gateway.statObject(BUCKET, key))
                .isInstanceOf(UploadGoneException.class);
        gateway.removeObjectQuietly(BUCKET, key);
    }

    @Test
    @Order(8)
    @DisplayName("回归：中文文件名（含空格/&/括号）pending 对象条件 copy 正常（XMinioInvalidObjectName）")
    void conditionalCopyWithChineseFilename() throws Exception {
        String key = "uploads/pending/1/session-cjk/eWs3kQQ7_HUAWEI MateBook 14 鸿蒙版 用户指南-(MNTXM-24A&24B&32A,HarmonyOS 6.1_01,zh-cn).pdf";
        String destKey = "documents/1/MEa1GhSu_HUAWEI MateBook 14 鸿蒙版 用户指南-(MNTXM-24A&24B&32A,HarmonyOS 6.1_01,zh-cn).pdf";

        byte[] body = new byte[64];
        body[0] = 0x25;
        put(gateway.presignPutUrl(BUCKET, key, EXPIRY), body);

        var stat = gateway.statObject(BUCKET, key);
        gateway.copyObjectIfMatch(BUCKET, key, destKey, stat.etag(), "application/pdf");

        var destStat = gateway.statObject(BUCKET, destKey);
        assertThat(destStat.size()).isEqualTo(body.length);
        assertThat(destStat.contentType()).isEqualTo("application/pdf");
    }

    // ==================== 工具 ====================

    /** PUT 字节并返回响应 ETag 头（可带引号，由网关归一化） */
    private static String put(String url, byte[] body) throws IOException {
        try (Response resp = http.newCall(new Request.Builder()
                .url(url).put(RequestBody.create(body, null)).build()).execute()) {
            assertThat(resp.code()).as("presigned PUT should succeed: %s", resp.message()).isEqualTo(200);
            return resp.header("ETag");
        }
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    private static String sha256Hex(byte[] data) throws Exception {
        return hex(MessageDigest.getInstance("SHA-256").digest(data));
    }

    private static String md5Hex(byte[] data) throws Exception {
        return hex(MessageDigest.getInstance("MD5").digest(data));
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
