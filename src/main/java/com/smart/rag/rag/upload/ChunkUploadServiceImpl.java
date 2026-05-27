package com.smart.rag.rag.upload;

import com.smart.rag.common.errorcode.ErrorCode;
import com.smart.rag.exception.BusinessException;
import com.smart.rag.rag.config.DocumentProperties;
import com.smart.rag.rag.event.DocumentCreatedEvent;
import com.smart.rag.rag.entity.RagDocument;
import com.smart.rag.rag.etl.EtlStatus;
import com.smart.rag.rag.mapper.RagDocumentMapper;
import com.smart.rag.rag.service.FileStorageService;
import com.smart.rag.rag.service.EtlDispatchService;
import com.smart.rag.rag.service.impl.DocumentValidator;
import com.smart.rag.security.util.SecurityUtils;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.smart.rag.common.team.TeamStatusService;
import io.minio.*;
import io.minio.SourceObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.unit.DataSize;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.io.ClassPathResource;
import org.jspecify.annotations.Nullable;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 分片上传服务实现。
 * <p>
 * 编排 Redis（会话状态 + Lua 原子操作）+ MinIO（putObject 分片 + composeObject 合并）+ DB（文档元数据）。
 * <p>
 * MinIO SDK 9.0.0 不对外暴露 Multipart Upload API（createMultipartUpload/uploadPart/completeMultipartUpload），
 * 因此采用 composeObject 方案：
 * <ol>
 *   <li>每个分片 putObject 到临时路径 chunks/{uploadId}/part-{chunkIndex}</li>
 *   <li>所有分片上传完后，composeObject 合并所有临时对象为目标对象</li>
 *   <li>合并后删除临时对象</li>
 * </ol>
 */
@Service
public class ChunkUploadServiceImpl implements ChunkUploadService {

    private static final Logger log = LoggerFactory.getLogger(ChunkUploadServiceImpl.class);

    private final StringRedisTemplate redisTemplate;
    private final MinioClient minioClient;
    private final BucketResolver bucketResolver;
    private final ChunkSizeStrategy chunkSizeStrategy;
    private final DocumentProperties documentProperties;
    private final DocumentValidator documentValidator;
    private final FileStorageService fileStorageService;
    private final RagDocumentMapper ragDocumentMapper;
    private final EtlDispatchService etlDispatchService;
    private final TeamStatusService teamStatusService;
    private final DefaultRedisScript<List> atomicChunkUploadScript;
    private final ThreadPoolTaskExecutor mergeExecutor;
    private final ApplicationEventPublisher eventPublisher;

    public ChunkUploadServiceImpl(
            StringRedisTemplate redisTemplate,
            MinioClient minioClient,
            BucketResolver bucketResolver,
            ChunkSizeStrategy chunkSizeStrategy,
            DocumentProperties documentProperties,
            DocumentValidator documentValidator,
            FileStorageService fileStorageService,
            RagDocumentMapper ragDocumentMapper,
            EtlDispatchService etlDispatchService,
            TeamStatusService teamStatusService,
            ThreadPoolTaskExecutor mergeExecutor,
            ApplicationEventPublisher eventPublisher
    ) {
        this.redisTemplate = redisTemplate;
        this.minioClient = minioClient;
        this.bucketResolver = bucketResolver;
        this.chunkSizeStrategy = chunkSizeStrategy;
        this.documentProperties = documentProperties;
        this.documentValidator = documentValidator;
        this.fileStorageService = fileStorageService;
        this.ragDocumentMapper = ragDocumentMapper;
        this.etlDispatchService = etlDispatchService;
        this.teamStatusService = teamStatusService;
        this.mergeExecutor = mergeExecutor;
        this.eventPublisher = eventPublisher;

        this.atomicChunkUploadScript = new DefaultRedisScript<>();
        this.atomicChunkUploadScript.setLocation(new ClassPathResource("scripts/atomic_chunk_upload.lua"));
        this.atomicChunkUploadScript.setResultType(List.class);
    }

    // ==================== init ====================

    @Override
    public ChunkUploadResult init(ChunkUploadInitRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();

        validateMimeType(request.mimeType());
        validateFileSize(request.fileSize());

        // init 端点速率限制
        checkRateLimit(userId);

        // 秒传检查
        RagDocument existing = findExistingForQuickUpload(request.fileMd5(), userId);
        if (existing != null) {
            log.info("Quick upload hit: fileMd5={}, userId={}, docId={}", request.fileMd5(), userId, existing.getId());
            return ChunkUploadResult.quickUploaded(existing.getId(), existing.getFileName());
        }

        // 续传检查
        String existingUploadId = redisTemplate.opsForValue().get(UploadRedisConstants.fileKey(userId, request.fileMd5()));
        if (existingUploadId != null) {
            ChunkUploadResult resumed = tryResume(existingUploadId, request, userId);
            if (resumed != null) {
                return resumed;
            }
        }

        return createNewSession(request, userId);
    }

    // ==================== uploadChunk ====================

    @Override
    public ChunkUploadResponse uploadChunk(String uploadId, int chunkIndex, String chunkMd5, byte[] chunkData) {
        Long userId = SecurityUtils.getCurrentUserId();
        Map<String, String> session = validateSession(uploadId, userId);

        int totalChunks = Integer.parseInt(session.get("totalChunks"));

        if (chunkIndex < 0 || chunkIndex >= totalChunks) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "分片序号超出范围: " + chunkIndex);
        }
        if (chunkData.length > 50 * 1024 * 1024) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "单分片大小不能超过50MB");
        }

        // 幂等检查
        Boolean exists = redisTemplate.opsForHash().hasKey(
                UploadRedisConstants.partsKey(uploadId), String.valueOf(chunkIndex));
        if (Boolean.TRUE.equals(exists)) {
            log.debug("Chunk already uploaded: uploadId={}, index={}", uploadId, chunkIndex);
            return ChunkUploadResponse.uploaded(uploadId, chunkIndex);
        }

        // 分片 MD5 校验
        String actualChunkMd5 = md5Hex(chunkData);
        if (!actualChunkMd5.equalsIgnoreCase(chunkMd5)) {
            log.warn("Chunk MD5 mismatch: uploadId={}, index={}, expected={}, actual={}",
                    uploadId, chunkIndex, chunkMd5, actualChunkMd5);
            throw new BusinessException(ErrorCode.UPLOAD_CHUNK_MD5_MISMATCH);
        }

        // 上传分片到 MinIO 临时路径
        String bucket = session.get("bucket");
        String chunkObjectKey = session.get("objectName") + "/part-" + chunkIndex;
        putObjectToMinio(bucket, chunkObjectKey, chunkData, session.get("mimeType"));

        // ETag 用分片 MD5 代替（composeObject 不需要 S3 ETag）
        String etag = actualChunkMd5;

        // Lua 原子操作
        List result = redisTemplate.execute(
                atomicChunkUploadScript,
                List.of(UploadRedisConstants.partsKey(uploadId)),
                String.valueOf(chunkIndex), etag,
                String.valueOf(totalChunks), UploadRedisConstants.MERGING_FIELD
        );

        boolean shouldMerge = false;
        if (result != null && !result.isEmpty()) {
            long trigger = ((Number) result.get(0)).longValue();
            shouldMerge = trigger == 1;
        }

        if (shouldMerge) {
            log.info("All chunks uploaded, triggering async merge: uploadId={}", uploadId);
            mergeExecutor.execute(() -> {
                try {
                    performMerge(uploadId);
                } catch (BusinessException e) {
                    // 业务异常（如团队解散）：清理 __merging 标记，前端可感知
                    log.warn("Auto-merge rejected: uploadId={}, reason={}", uploadId, e.getMessage());
                    redisTemplate.opsForHash().delete(UploadRedisConstants.partsKey(uploadId), UploadRedisConstants.MERGING_FIELD);
                } catch (Exception e) {
                    log.error("Auto-merge failed: uploadId={}", uploadId, e);
                    // 不清除 __merging 标记，允许客户端通过 complete 接口手动重试
                }
            });
            return ChunkUploadResponse.merging(uploadId, chunkIndex);
        }

        return ChunkUploadResponse.uploaded(uploadId, chunkIndex);
    }

    // ==================== status ====================

    @Override
    public ChunkUploadStatusResponse status(String uploadId) {
        Long userId = SecurityUtils.getCurrentUserId();
        Map<String, String> session = validateSession(uploadId, userId);

        String partsKey = UploadRedisConstants.partsKey(uploadId);
        Set<Object> keys = redisTemplate.opsForHash().keys(partsKey);

        List<Integer> uploadedChunks = keys.stream()
                .map(Object::toString)
                .filter(k -> !UploadRedisConstants.MERGING_FIELD.equals(k))
                .map(Integer::parseInt)
                .sorted()
                .collect(Collectors.toList());

        int totalChunks = Integer.parseInt(session.get("totalChunks"));
        boolean completed = uploadedChunks.size() == totalChunks;
        Boolean merging = redisTemplate.opsForHash().hasKey(partsKey, UploadRedisConstants.MERGING_FIELD);

        return new ChunkUploadStatusResponse(
                uploadId, session.get("fileName"), totalChunks,
                uploadedChunks, completed, Boolean.TRUE.equals(merging), null
        );
    }

    // ==================== complete ====================

    @Override
    public Long complete(String uploadId, String fileMd5) {
        Long userId = SecurityUtils.getCurrentUserId();

        String sessionKey = UploadRedisConstants.sessionKey(uploadId);
        Map<Object, Object> rawSession = redisTemplate.opsForHash().entries(sessionKey);

        if (rawSession.isEmpty()) {
            RagDocument doc = findExistingForQuickUpload(fileMd5, userId);
            if (doc != null) {
                log.info("Complete idempotent: uploadId={} already merged as docId={}", uploadId, doc.getId());
                return doc.getId();
            }
            throw new BusinessException(ErrorCode.UPLOAD_SESSION_NOT_FOUND);
        }

        Map<String, String> session = toStringMap(rawSession);
        validateOwner(session, userId);

        if (!fileMd5.equalsIgnoreCase(session.get("fileMd5"))) {
            throw new BusinessException(ErrorCode.UPLOAD_FILE_MD5_MISMATCH, "声明的文件MD5与会话不匹配");
        }

        // 防止 autoMerge 进行中重复触发：检查 __merging 标记
        String partsKey = UploadRedisConstants.partsKey(uploadId);
        Boolean merging = redisTemplate.opsForHash().hasKey(partsKey, UploadRedisConstants.MERGING_FIELD);
        if (Boolean.TRUE.equals(merging)) {
            // autoMerge 正在进行中，等待完成后再查结果
            log.info("Complete deferred: auto-merge in progress, uploadId={}", uploadId);
            RagDocument existing = findExistingForQuickUpload(fileMd5, userId);
            if (existing != null) {
                return existing.getId();
            }
            throw new BusinessException(ErrorCode.BAD_REQUEST, "文件合并正在进行中，请稍后重试");
        }

        performMerge(uploadId);

        RagDocument doc = findExistingForQuickUpload(fileMd5, userId);
        return doc != null ? doc.getId() : null;
    }

    // ==================== abort ====================

    @Override
    public void abort(String uploadId) {
        Long userId = SecurityUtils.getCurrentUserId();
        Map<String, String> session = validateSession(uploadId, userId);

        String bucket = session.get("bucket");
        String basePath = session.get("objectName");

        // 删除所有已上传的临时分片
        try {
            int totalChunks = Integer.parseInt(session.get("totalChunks"));
            for (int i = 0; i < totalChunks; i++) {
                try {
                    String chunkKey = basePath + "/part-" + i;
                    Boolean exists = redisTemplate.opsForHash().hasKey(
                            UploadRedisConstants.partsKey(uploadId), String.valueOf(i));
                    if (Boolean.TRUE.equals(exists)) {
                        minioClient.removeObject(RemoveObjectArgs.builder()
                                .bucket(bucket).object(chunkKey).build());
                    }
                } catch (Exception e) {
                    log.warn("Failed to delete chunk {} during abort: {}", i, e.getMessage());
                }
            }
            log.info("Chunk upload aborted: uploadId={}, user={}", uploadId, userId);
        } catch (Exception e) {
            log.error("Error during abort cleanup: uploadId={}", uploadId, e);
        }

        cleanupRedis(uploadId, session.get("userId"), session.get("fileMd5"));
    }

    // ==================== 合并流程 ====================

    void performMerge(String uploadId) {
        String sessionKey = UploadRedisConstants.sessionKey(uploadId);
        Map<String, String> session = toStringMap(redisTemplate.opsForHash().entries(sessionKey));

        if (session.isEmpty()) {
            log.warn("Merge skipped: session already cleaned, uploadId={}", uploadId);
            return;
        }

        // 团队状态校验：团队已解散则拒绝合并
        String teamIdStr = session.get("teamId");
        Long teamId = null;
        if (teamIdStr != null) {
            teamId = Long.parseLong(teamIdStr);
            if (!teamStatusService.isTeamActive(teamId)) {
                log.warn("Merge rejected: team dissolved, teamId={}, uploadId={}", teamId, uploadId);
                String bucket = session.get("bucket");
                cleanupTempChunks(bucket, session.get("objectName"), Integer.parseInt(session.get("totalChunks")));
                cleanupRedis(uploadId, session.get("userId"), session.get("fileMd5"));
                throw new BusinessException(ErrorCode.TEAM_NOT_FOUND, "团队已解散，上传已取消");
            }
        }

        String bucket = session.get("bucket");
        String basePath = session.get("objectName");
        int totalChunks = Integer.parseInt(session.get("totalChunks"));

        // 1. 构建 Source 列表用于 composeObject
        List<SourceObject> sources = new ArrayList<>();
        for (int i = 0; i < totalChunks; i++) {
            String chunkObjectKey = basePath + "/part-" + i;
            sources.add(SourceObject.builder()
                    .bucket(bucket)
                    .object(chunkObjectKey)
                    .build());
        }

        // 2. 合并目标路径：documents/{userId}/{shortId}_{原始文件名}
        String originalName = session.get("fileName");
        String targetObjectKey = "documents/" + session.get("userId") + "/" + generateShortId(8) + "_" + sanitizeFilename(originalName);

        // 3. composeObject 合并（携带 Content-Type）
        String mimeType = session.get("mimeType");
        composeObjects(bucket, targetObjectKey, sources, mimeType);

        // 4. 流式读取合并后文件，计算实际 MD5
        String actualMd5 = computeFileMd5FromMinio(bucket, targetObjectKey);
        String declaredMd5 = session.get("fileMd5");

        if (!actualMd5.equalsIgnoreCase(declaredMd5)) {
            log.warn("File MD5 mismatch: uploadId={}, expected={}, actual={}", uploadId, declaredMd5, actualMd5);
            deleteFromMinio(bucket, targetObjectKey);
            cleanupTempChunks(bucket, basePath, totalChunks);
            redisTemplate.opsForHash().delete(UploadRedisConstants.partsKey(uploadId), UploadRedisConstants.MERGING_FIELD);
            throw new BusinessException(ErrorCode.UPLOAD_FILE_MD5_MISMATCH, "文件校验失败");
        }

        // 5. 持久化 rag_document
        Long userId = Long.parseLong(session.get("userId"));
        Long docId = persistDocument(session, targetObjectKey, actualMd5, userId, teamId);
        log.info("Chunk upload merged: uploadId={}, docId={}, md5={}", uploadId, docId, actualMd5);

        // 6. 清理临时分片
        cleanupTempChunks(bucket, basePath, totalChunks);

        // 7. 清理 Redis
        cleanupRedis(uploadId, session.get("userId"), declaredMd5);

        // 8. 触发 ETL
        etlDispatchService.dispatchAsync(
                docId, bucket, targetObjectKey, session.get("fileName"),
                session.get("mimeType"), Long.parseLong(session.get("fileSize")), userId,
                teamId
        );

        // 9. 发布 DocumentCreatedEvent（用于增量更新版本链接）
        String replaceDocIdStr = session.get("replaceDocumentId");
        Long replaceDocumentId = replaceDocIdStr != null ? Long.parseLong(replaceDocIdStr) : null;
        eventPublisher.publishEvent(new DocumentCreatedEvent(docId, replaceDocumentId, userId, teamId));
    }

    // ==================== 私有方法 ====================

    private void validateMimeType(String mimeType) {
        Set<String> allowed = Set.of(documentProperties.getAllowedMimeTypes().split(","));
        if (!allowed.contains(mimeType)) {
            throw new BusinessException(ErrorCode.UPLOAD_MIME_UNSUPPORTED, "不支持的文件类型: " + mimeType);
        }
    }

    private void validateFileSize(Long fileSize) {
        long maxBytes = DataSize.parse(documentProperties.getMaxFileSize()).toBytes();
        if (fileSize > maxBytes) {
            throw new BusinessException(ErrorCode.UPLOAD_FILE_TOO_LARGE,
                    String.format("文件大小 %d MB 超过上限 %s",
                            fileSize / (1024 * 1024), documentProperties.getMaxFileSize()));
        }
    }

    /** 原子化 INCR + 首次 EXPIRE，修复两步 TTL 窗口问题 */
    private static final DefaultRedisScript<Long> INCR_WITH_EXPIRE_SCRIPT = new DefaultRedisScript<>(
            "local count = redis.call('INCR', KEYS[1]) " +
            "if count == 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end " +
            "return count", Long.class);

    private void checkRateLimit(Long userId) {
        String rateKey = UploadRedisConstants.rateKey(userId);
        Long count = redisTemplate.execute(INCR_WITH_EXPIRE_SCRIPT,
                List.of(rateKey),
                String.valueOf(UploadRedisConstants.RATE_WINDOW.getSeconds()));
        if (count != null && count > UploadRedisConstants.RATE_LIMIT) {
            throw new BusinessException(ErrorCode.RATE_LIMITED, "上传初始化请求过于频繁");
        }
    }

    private RagDocument findExistingForQuickUpload(String fileMd5, Long userId) {
        return ragDocumentMapper.selectOne(
                new LambdaQueryWrapper<RagDocument>()
                        .eq(RagDocument::getFileMd5, fileMd5)
                        .eq(RagDocument::getUserId, userId)
                        .in(RagDocument::getStatus, EtlStatus.COMPLETED, EtlStatus.PROCESSING)
                        .eq(RagDocument::getDeleted, 0)
                        .last("LIMIT 1")
        );
    }

    private ChunkUploadResult tryResume(String uploadId, ChunkUploadInitRequest request, Long userId) {
        String sessionKey = UploadRedisConstants.sessionKey(uploadId);
        Map<Object, Object> rawSession = redisTemplate.opsForHash().entries(sessionKey);
        if (rawSession.isEmpty()) {
            redisTemplate.delete(UploadRedisConstants.fileKey(userId, request.fileMd5()));
            return null;
        }

        Map<String, String> session = toStringMap(rawSession);
        validateOwner(session, userId);

        int chunkSize = Integer.parseInt(session.get("chunkSize"));
        int totalChunks = Integer.parseInt(session.get("totalChunks"));

        Set<Object> keys = redisTemplate.opsForHash().keys(UploadRedisConstants.partsKey(uploadId));
        List<Integer> uploadedChunks = keys.stream()
                .map(Object::toString)
                .filter(k -> !UploadRedisConstants.MERGING_FIELD.equals(k))
                .map(Integer::parseInt)
                .sorted()
                .collect(Collectors.toList());

        log.info("Chunk upload resumed: uploadId={}, uploaded={}/{}", uploadId, uploadedChunks.size(), totalChunks);
        return ChunkUploadResult.resumeSession(uploadId, chunkSize, totalChunks, uploadedChunks);
    }

    private ChunkUploadResult createNewSession(ChunkUploadInitRequest request, Long userId) {
        int chunkSize = chunkSizeStrategy.calculateChunkSize(request.fileSize());
        int totalChunks = request.fileSize() <= chunkSize ? 1 : (int) ((request.fileSize() + chunkSize - 1) / chunkSize);

        if (request.fileSize() <= chunkSize) {
            chunkSize = (int) (long) request.fileSize();
        }

        String bucket = bucketResolver.resolve(request.teamId());
        fileStorageService.ensureBucketExists(bucket);
        String objectBasePath = "chunks/" + userId + "/" + UUID.randomUUID();
        String uploadId = UUID.randomUUID().toString();

        // Redis 写入 session
        String sessionKey = UploadRedisConstants.sessionKey(uploadId);
        Map<String, String> sessionFields = new HashMap<>(Map.ofEntries(
                Map.entry("fileMd5", request.fileMd5()),
                Map.entry("fileName", request.fileName()),
                Map.entry("fileSize", String.valueOf(request.fileSize())),
                Map.entry("mimeType", request.mimeType()),
                Map.entry("chunkSize", String.valueOf(chunkSize)),
                Map.entry("totalChunks", String.valueOf(totalChunks)),
                Map.entry("userId", String.valueOf(userId)),
                Map.entry("bucket", bucket),
                Map.entry("objectName", objectBasePath),
                Map.entry("createdAt", String.valueOf(System.currentTimeMillis()))
        ));
        if (request.teamId() != null) {
            sessionFields.put("teamId", String.valueOf(request.teamId()));
        }
        if (request.replaceDocumentId() != null) {
            sessionFields.put("replaceDocumentId", String.valueOf(request.replaceDocumentId()));
        }
        redisTemplate.opsForHash().putAll(sessionKey, sessionFields);
        redisTemplate.expire(sessionKey, UploadRedisConstants.SESSION_TTL);

        // parts key TTL
        redisTemplate.expire(UploadRedisConstants.partsKey(uploadId), UploadRedisConstants.SESSION_TTL);

        // 反向索引
        redisTemplate.opsForValue().set(
                UploadRedisConstants.fileKey(userId, request.fileMd5()),
                uploadId, UploadRedisConstants.SESSION_TTL);

        log.info("Chunk upload init: uploadId={}, file={}, size={}, chunks={}, user={}",
                uploadId, request.fileName(), request.fileSize(), totalChunks, userId);

        return ChunkUploadResult.newSession(uploadId, chunkSize, totalChunks);
    }

    private Map<String, String> validateSession(String uploadId, Long userId) {
        // 格式校验：防止路径遍历
        if (uploadId == null || !uploadId.matches("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "无效的上传会话ID");
        }
        String sessionKey = UploadRedisConstants.sessionKey(uploadId);
        Map<Object, Object> rawSession = redisTemplate.opsForHash().entries(sessionKey);
        if (rawSession.isEmpty()) {
            throw new BusinessException(ErrorCode.UPLOAD_SESSION_NOT_FOUND);
        }
        Map<String, String> session = toStringMap(rawSession);
        validateOwner(session, userId);
        return session;
    }

    private void validateOwner(Map<String, String> session, Long userId) {
        Long owner = Long.parseLong(session.get("userId"));
        if (!owner.equals(userId)) {
            log.warn("Upload owner mismatch: expected={}, actual={}", owner, userId);
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
    }

    // ==================== MinIO 操作 ====================

    private void putObjectToMinio(String bucket, String objectKey, byte[] data, String contentType) {
        try {
            minioClient.putObject(
                    PutObjectArgs.builder()
                            .bucket(bucket)
                            .object(objectKey)
                            .stream(new ByteArrayInputStream(data), (long) data.length, -1L)
                            .contentType(contentType)
                            .build()
            );
            log.debug("Uploaded chunk to MinIO: {}/{}", bucket, objectKey);
        } catch (Exception e) {
            log.error("MinIO putObject error: bucket={}, object={}", bucket, objectKey, e);
            throw new BusinessException(ErrorCode.UPLOAD_FAILED, "存储服务异常");
        }
    }

    private void composeObjects(String bucket, String targetObjectKey, List<SourceObject> sources, String contentType) {
        try {
            minioClient.composeObject(
                    ComposeObjectArgs.builder()
                            .bucket(bucket)
                            .object(targetObjectKey)
                            .sources(sources)
                            .headers(Map.of("Content-Type", contentType))
                            .build()
            );
            log.info("Composed object: {}/{} from {} parts, contentType={}", bucket, targetObjectKey, sources.size(), contentType);
        } catch (Exception e) {
            log.error("MinIO composeObject error: bucket={}, target={}", bucket, targetObjectKey, e);
            throw new BusinessException(ErrorCode.UPLOAD_FAILED, "合并分片失败");
        }
    }

    private static final char[] NANOID_CHARS =
            "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ".toCharArray();
    private static final java.util.Random RANDOM = new java.security.SecureRandom();

    private String generateShortId(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(NANOID_CHARS[RANDOM.nextInt(NANOID_CHARS.length)]);
        }
        return sb.toString();
    }

    private String sanitizeFilename(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return "unnamed";
        }
        return fileName.replace("/", "_").replace("\\", "_").replace("\0", "_");
    }

    private String computeFileMd5FromMinio(String bucket, String objectName) {
        try (InputStream is = minioClient.getObject(
                GetObjectArgs.builder().bucket(bucket).object(objectName).build())) {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = is.read(buffer)) != -1) {
                md.update(buffer, 0, read);
            }
            return hexFormat(md.digest());
        } catch (Exception e) {
            log.error("Failed to compute file MD5 from MinIO: bucket={}, object={}", bucket, objectName, e);
            throw new BusinessException(ErrorCode.UPLOAD_FAILED, "文件校验失败");
        }
    }

    private void deleteFromMinio(String bucket, String objectName) {
        try {
            minioClient.removeObject(
                    RemoveObjectArgs.builder().bucket(bucket).object(objectName).build());
        } catch (Exception e) {
            log.error("Failed to delete from MinIO: bucket={}, object={}", bucket, objectName, e);
        }
    }

    private void cleanupTempChunks(String bucket, String basePath, int totalChunks) {
        for (int i = 0; i < totalChunks; i++) {
            try {
                minioClient.removeObject(RemoveObjectArgs.builder()
                        .bucket(bucket)
                        .object(basePath + "/part-" + i)
                        .build());
            } catch (Exception e) {
                log.warn("Failed to cleanup chunk {}: {}", i, e.getMessage());
            }
        }
    }

    // ==================== DB 持久化 ====================

    private Long persistDocument(Map<String, String> session, String targetObjectKey, String actualMd5, Long userId, @Nullable Long teamId) {
        RagDocument doc = new RagDocument();
        doc.setFileName(session.get("fileName"));
        doc.setFileSize(Long.parseLong(session.get("fileSize")));
        doc.setMimeType(session.get("mimeType"));
        doc.setStorageKey(targetObjectKey);
        doc.setBucket(session.get("bucket"));
        doc.setUserId(userId);
        doc.setTeamId(teamId);
        doc.setFileMd5(actualMd5);
        doc.setStatus(EtlStatus.PROCESSING);
        doc.setDeleted(0);
        doc.setCreateTime(OffsetDateTime.now());
        doc.setUpdateTime(OffsetDateTime.now());
        ragDocumentMapper.insert(doc);
        return doc.getId();
    }

    // ==================== Redis 清理 ====================

    private void cleanupRedis(String uploadId, String userId, String fileMd5) {
        redisTemplate.delete(UploadRedisConstants.sessionKey(uploadId));
        redisTemplate.delete(UploadRedisConstants.partsKey(uploadId));
        if (userId != null && fileMd5 != null) {
            redisTemplate.delete(UploadRedisConstants.fileKey(Long.parseLong(userId), fileMd5));
        }
    }

    // ==================== 工具方法 ====================

    private static String md5Hex(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            return hexFormat(md.digest(data));
        } catch (Exception e) {
            throw new RuntimeException("MD5 computation failed", e);
        }
    }

    private static final char[] HEX_CHARS = "0123456789abcdef".toCharArray();

    private static String hexFormat(byte[] bytes) {
        char[] chars = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            chars[i * 2] = HEX_CHARS[(bytes[i] >> 4) & 0x0f];
            chars[i * 2 + 1] = HEX_CHARS[bytes[i] & 0x0f];
        }
        return new String(chars);
    }

    private static Map<String, String> toStringMap(Map<Object, Object> raw) {
        Map<String, String> result = new HashMap<>(raw.size());
        raw.forEach((k, v) -> result.put(k.toString(), v != null ? v.toString() : ""));
        return Collections.unmodifiableMap(result);
    }
}
