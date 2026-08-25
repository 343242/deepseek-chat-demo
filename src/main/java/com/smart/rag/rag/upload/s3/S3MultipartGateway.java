package com.smart.rag.rag.upload.s3;

import com.smart.rag.infrastructure.exception.AbstractException;
import com.smart.rag.infrastructure.exception.ServiceException;
import com.smart.rag.infrastructure.exception.errorcode.ServiceErrorCode;
import io.minio.AbortMultipartUploadArgs;
import io.minio.CompleteMultipartUploadArgs;
import io.minio.CreateMultipartUploadArgs;
import io.minio.GetObjectArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.Http;
import io.minio.MinioAsyncClient;
import io.minio.MinioClient;
import io.minio.RemoveObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.errors.ErrorResponseException;
import io.minio.messages.Part;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * S3 Multipart Upload / presigned URL 网关（presigned 直传路径专用）。
 * <p>
 * 三个 client 分工（见 docs/design/presigned-direct-upload.md「SDK 承载」）：
 * <ul>
 *   <li>{@code MinioAsyncClient}（内网）：MPU 原语——SDK 8.5.15 起 public、9.0.3 位于
 *       {@code BaseS3Client}，返回 {@link CompletableFuture}，本网关内同步化；</li>
 *   <li>{@code presignMinioClient}（external-endpoint）：仅 presign——Host 参与签名，
 *       必须以浏览器可达主机名签发；</li>
 *   <li>{@code MinioClient}（内网，同步）：statObject / copyObject / removeObject 等复核读写。</li>
 * </ul>
 * 不手写 SigV4，签名/编码全部由 SDK 承担。
 * <p>
 * 异常分类：{@code NoSuchUpload}（uploadId 已消亡：cleaner abort / 并发 Complete / 不存在）
 * 统一映射 {@link UploadGoneException}，由服务层引导前端重新 init；其余存储故障抛
 * {@link ServiceException}（INTERNAL_ERROR 或直传专用码），携带原始异常为 cause。
 */
@Component
public class S3MultipartGateway {

    private static final Logger log = LoggerFactory.getLogger(S3MultipartGateway.class);

    /** S3 错误码：uploadId 已消亡（abort/Complete/不存在） */
    private static final String ERROR_NO_SUCH_UPLOAD = "NoSuchUpload";
    /** S3 错误码：对象不存在（pending 被 cleaner 清除等） */
    private static final String ERROR_NO_SUCH_KEY = "NoSuchKey";

    /** 服务端条件 copy 专用（presigned PUT + 手工 copy-source 头），短超时防 cleaner 线程阻塞 */
    private static final OkHttpClient COPY_HTTP_CLIENT = new OkHttpClient.Builder()
            .callTimeout(60, TimeUnit.SECONDS)
            .connectTimeout(5, TimeUnit.SECONDS)
            .build();

    private final MinioClient minioClient;
    private final MinioAsyncClient asyncClient;
    private final MinioClient presignClient;

    public S3MultipartGateway(MinioClient minioClient,
                              MinioAsyncClient asyncClient,
                              @Qualifier("presignMinioClient") MinioClient presignClient) {
        this.minioClient = minioClient;
        this.asyncClient = asyncClient;
        this.presignClient = presignClient;
    }

    // ==================== MPU 原语（asyncClient 同步封装） ====================

    /**
     * 发起 multipart upload，返回 uploadId（commit 前分片隐藏存储，LIST 不可见）。
     */
    public String createMultipartUpload(String bucket, String objectKey) {
        try {
            return await(asyncClient.createMultipartUpload(
                            CreateMultipartUploadArgs.builder().bucket(bucket).object(objectKey).build()),
                    "createMultipartUpload").result().uploadId();
        } catch (RuntimeException e) {
            throw asGatewayException("发起分片上传", e, bucket, objectKey);
        }
    }

    /**
     * Complete multipart upload。分片 ETag 由前端从 UploadPart 响应头回传，S3 侧逐片校验，
     * 伪造/失配即 InvalidPart 拒绝；Complete 内嵌 Error（200 但 body 报错）由 SDK 解析层兜底。
     *
     * @param parts 前端回传的 (partNumber, etag) 列表，须覆盖全部分片
     */
    public void completeMultipartUpload(String bucket, String objectKey, String uploadId, List<CompletedPart> parts) {
        Part[] sdkParts = parts.stream()
                .map(p -> new Part(p.partNumber(), normalizeEtag(p.etag())))
                .toArray(Part[]::new);
        try {
            await(asyncClient.completeMultipartUpload(CompleteMultipartUploadArgs.builder()
                            .bucket(bucket).object(objectKey).uploadId(uploadId).parts(sdkParts).build()),
                    "completeMultipartUpload");
        } catch (UploadGoneException e) {
            throw e;
        } catch (RuntimeException e) {
            // InvalidPart 等 Complete 侧失败（不含 copy 阶段）——含 ETag 校验不符
            throw new ServiceException(ServiceErrorCode.DIRECT_UPLOAD_COMPLETE_FAILED, "分片合并失败", e);
        }
    }

    /**
     * 取消 multipart upload。幂等：uploadId 已消亡（NoSuchUpload）不抛异常，静默返回。
     */
    public void abortMultipartUploadQuietly(String bucket, String objectKey, String uploadId) {
        try {
            await(asyncClient.abortMultipartUpload(AbortMultipartUploadArgs.builder()
                            .bucket(bucket).object(objectKey).uploadId(uploadId).build()),
                    "abortMultipartUpload");
            log.debug("Aborted multipart upload: bucket={}, object={}, uploadId={}", bucket, objectKey, uploadId);
        } catch (UploadGoneException e) {
            log.debug("Multipart upload already gone on abort (idempotent): bucket={}, uploadId={}", bucket, uploadId);
        } catch (RuntimeException e) {
            log.error("MinIO abortMultipartUpload error: bucket={}, object={}, uploadId={}", bucket, objectKey, uploadId, e);
        }
    }

    // 说明：不做 listMultipartUploads 扫描。实测（pgsty/minio + SDK 9.0.3，Testcontainers 验证）
    // 双重不可用：① 服务端 ListMultipartUploads 的 prefix 参数损坏（带 prefix 一律返回空清单）；
    // ② 不带 prefix 时 MinIO 返回空 <StorageClass></StorageClass>，SDK simpleframework 解析器
    // required=true 直接抛 XmlParserException。MPU 泄漏回收改由 Redis 出生登记簿驱动
    // （DirectUploadSessionStore 的 mpu:* ZSET），create 登记 / complete+abort 注销 /
    // cleaner 按阈值取超龄项 abort（幂等，本方法已验证）。

    // ==================== presign（presignClient，external-endpoint） ====================

    /**
     * 签发 single 模式（≤5MB 整文件）presigned PUT URL。
     */
    public String presignPutUrl(String bucket, String objectKey, Duration expiry) {
        return presign(bucket, objectKey, null, null, expiry);
    }

    /**
     * 签发 multipart 模式 UploadPart presigned PUT URL（携带 partNumber/uploadId 查询参数）。
     */
    public String presignPartUrl(String bucket, String objectKey, int partNumber, String uploadId, Duration expiry) {
        return presign(bucket, objectKey, partNumber, uploadId, expiry);
    }

    private String presign(String bucket, String objectKey, Integer partNumber, String uploadId, Duration expiry) {
        try {
            var builder = GetPresignedObjectUrlArgs.builder()
                    .method(Http.Method.PUT)
                    .bucket(bucket)
                    .object(objectKey)
                    .expiry((int) Math.max(1, expiry.toSeconds()), TimeUnit.SECONDS);
            if (partNumber != null) {
                builder.extraQueryParams(Map.of(
                        "partNumber", String.valueOf(partNumber),
                        "uploadId", uploadId));
            }
            return presignClient.getPresignedObjectUrl(builder.build());
        } catch (Exception e) {
            log.error("MinIO presign error: bucket={}, object={}", bucket, objectKey, e);
            throw new ServiceException(ServiceErrorCode.INTERNAL_ERROR, "签发直传地址失败", e);
        }
    }

    // ==================== 复核与中转（minioClient，同步） ====================

    /**
     * statObject 取 pending 对象的 ETag/尺寸/Content-Type。
     * 对象不存在（cleaner 已清等）映射 {@link UploadGoneException}。
     */
    public PendingObjectStat statObject(String bucket, String objectKey) {
        try {
            var stat = minioClient.statObject(StatObjectArgs.builder().bucket(bucket).object(objectKey).build());
            return new PendingObjectStat(normalizeEtag(stat.etag()), stat.size(), stat.contentType());
        } catch (ErrorResponseException e) {
            if (ERROR_NO_SUCH_KEY.equals(e.errorResponse().code())) {
                throw new UploadGoneException("直传对象不存在或已清理", e);
            }
            throw new ServiceException(ServiceErrorCode.INTERNAL_ERROR, "读取直传对象元数据失败", e);
        } catch (Exception e) {
            throw new ServiceException(ServiceErrorCode.INTERNAL_ERROR, "读取直传对象元数据失败", e);
        }
    }

    /**
     * 单遍流式读取计算整对象 SHA-256 与 MD5（commit 复核：SHA-256 对拍声明值；
     * MD5 仅 single 模式与 ETag 对拍，防 presigned URL 重放覆盖的 TOCTOU）。
     */
    public ObjectDigests computeDigests(String bucket, String objectKey) {
        try (InputStream is = minioClient.getObject(
                GetObjectArgs.builder().bucket(bucket).object(objectKey).build())) {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            MessageDigest md5 = MessageDigest.getInstance("MD5");
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = is.read(buffer)) != -1) {
                sha256.update(buffer, 0, read);
                md5.update(buffer, 0, read);
            }
            return new ObjectDigests(toHex(sha256.digest()), toHex(md5.digest()));
        } catch (AbstractException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to compute digests from MinIO: bucket={}, object={}", bucket, objectKey, e);
            throw new ServiceException(ServiceErrorCode.INTERNAL_ERROR, "读取直传对象计算校验和失败", e);
        }
    }

    /**
     * 条件复制 pending → 最终 key：携带 {@code x-amz-copy-source-if-match}（两种模式统一以
     * statObject 时刻的 ETag 对拍，任一环节失配即 412 拒绝，未校验内容永不进入 documents/），
     * 同时以 Tika 归一化结果 REPLACE 覆盖 Content-Type 元数据（防浏览器伪造值随 copy 保留）。
     * <p>
     * 实测（Testcontainers 验证）：MinIO 9.0.x SDK 的 {@code copyObject} 不透传
     * {@code headers(Map)} / {@code extraHeaders(Map)}（条件头被静默丢弃，stale ETag 照样复制），
     * 而 MinIO 服务端本身完整执行条件 copy（stale ETag → 412，实测）。故此处改为
     * 「SDK presign 内部 PUT（签名仍由 SDK 承担）+ okhttp 携带未签名的 copy-source 条件头」：
     * presigned URL 仅在服务端内存中存在（内网、10s 有效期），条件头不参与签名但服务端照常执行。
     */
    public void copyObjectIfMatch(String bucket, String sourceKey, String destKey, String etag, String contentType) {
        try {
            String presignedPut = minioClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(Http.Method.PUT)
                    .bucket(bucket)
                    .object(destKey)
                    .expiry(60, TimeUnit.SECONDS)
                    .build());
            Request request = new Request.Builder()
                    .url(presignedPut)
                    .header("x-amz-copy-source", "/" + bucket + "/" + encodeCopySource(sourceKey))
                    .header("x-amz-copy-source-if-match", normalizeEtag(etag))
                    .header("x-amz-metadata-directive", "REPLACE")
                    .header("Content-Type", contentType)
                    .put(RequestBody.create(new byte[0], null))
                    .build();
            try (Response resp = COPY_HTTP_CLIENT.newCall(request).execute()) {
                if (resp.code() == 412) {
                    log.warn("Conditional copy rejected (412): object changed after verification, bucket={}, from={}, to={}",
                            bucket, sourceKey, destKey);
                    throw new ServiceException(ServiceErrorCode.DIRECT_UPLOAD_COPY_FAILED, "对象在校验后被修改，复制已拒绝");
                }
                if (!resp.isSuccessful()) {
                    String body = resp.body() != null ? resp.body().string() : "";
                    log.error("Conditional copy failed: bucket={}, from={}, to={}, status={}, body={}",
                            bucket, sourceKey, destKey, resp.code(), body);
                    throw new ServiceException(ServiceErrorCode.DIRECT_UPLOAD_COPY_FAILED, "对象存储复制失败");
                }
            }
            log.info("Copied direct-upload object: {}/{} -> {} (etag matched)", bucket, sourceKey, destKey);
        } catch (AbstractException e) {
            throw e;
        } catch (Exception e) {
            log.error("MinIO conditional copyObject error: bucket={}, from={}, to={}", bucket, sourceKey, destKey, e);
            throw new ServiceException(ServiceErrorCode.DIRECT_UPLOAD_COPY_FAILED, "对象存储复制失败", e);
        }
    }

    /** x-amz-copy-source 的 key 需 URL 编码（保留 / 分隔；空格等字符 okhttp 头值不接受裸传输） */
    private static String encodeCopySource(String key) {
        StringBuilder sb = new StringBuilder(key.length() + 16);
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            if (c == '/' || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '.' || c == '_' || c == '~') {
                sb.append(c);
            } else {
                sb.append('%').append(String.format("%02X", (int) c));
            }
        }
        return sb.toString();
    }

    /**
     * 打开对象读取流（commit 期 Tika 类型校验用；调用方负责 close）。
     */
    public InputStream getObject(String bucket, String objectKey) {
        try {
            return minioClient.getObject(GetObjectArgs.builder().bucket(bucket).object(objectKey).build());
        } catch (AbstractException e) {
            throw e;
        } catch (Exception e) {
            log.error("MinIO getObject error: bucket={}, object={}", bucket, objectKey, e);
            throw new ServiceException(ServiceErrorCode.INTERNAL_ERROR, "读取直传对象失败", e);
        }
    }

    /**
     * 尽力删除对象；失败仅记录日志（cleaner 兜底回收）。
     */
    public void removeObjectQuietly(String bucket, String objectKey) {
        try {
            minioClient.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(objectKey).build());
        } catch (Exception e) {
            log.error("Failed to delete from MinIO: bucket={}, object={}", bucket, objectKey, e);
        }
    }

    // ==================== 内部工具 ====================

    /** presigned URL 在有效期内可重放覆盖 pending key，日志/异常须打码签名参数 */
    public static String sanitizePresignedUrl(String url) {
        if (url == null) {
            return null;
        }
        return url.replaceAll("([?&])X-Amz-Signature=[^&]*", "$1X-Amz-Signature=***")
                .replaceAll("([?&])X-Amz-Credential=[^&]*", "$1X-Amz-Credential=***");
    }

    /** 去除 S3 ETag 外层引号（浏览器回传与 statObject 均可能带引号） */
    static String normalizeEtag(String etag) {
        if (etag == null) {
            return null;
        }
        String trimmed = etag.trim();
        if (trimmed.length() >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
            return trimmed.substring(1, trimmed.length() - 1);
        }
        return trimmed;
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    private <T> T await(CompletableFuture<T> future, String action) {
        try {
            return future.get();
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof ErrorResponseException er && ERROR_NO_SUCH_UPLOAD.equals(er.errorResponse().code())) {
                throw new UploadGoneException("分片上传会话已失效", er);
            }
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            throw new ServiceException(ServiceErrorCode.INTERNAL_ERROR, "存储服务" + action + "失败", cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ServiceException(ServiceErrorCode.INTERNAL_ERROR, "存储服务" + action + "被中断", e);
        }
    }

    private RuntimeException asGatewayException(String action, RuntimeException e, String bucket, String key) {
        if (e instanceof AbstractException) {
            return e;
        }
        log.error("MinIO {} error: bucket={}, object={}", action, bucket, key, e);
        return new ServiceException(ServiceErrorCode.INTERNAL_ERROR, "存储服务" + action + "失败", e);
    }

    // ==================== 值类型 ====================

    /** 前端回传的已完成分片（partNumber + UploadPart 响应 ETag） */
    public record CompletedPart(int partNumber, String etag) {}

    /** pending 对象元数据（commit 校验用） */
    public record PendingObjectStat(String etag, long size, String contentType) {}

    /** 整对象摘要对（SHA-256 对拍声明值；MD5 仅 single 模式与 ETag 对拍） */
    public record ObjectDigests(String sha256Hex, String md5Hex) {}
}
