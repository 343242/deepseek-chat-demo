package com.smart.rag.rag.upload;

import com.smart.rag.common.util.ChecksumUtils;
import com.smart.rag.infrastructure.exception.errorcode.ClientErrorCode;
import com.smart.rag.infrastructure.exception.errorcode.ServiceErrorCode;
import com.smart.rag.infrastructure.exception.AbstractException;
import com.smart.rag.infrastructure.exception.ClientException;
import com.smart.rag.infrastructure.exception.ServiceException;
import com.smart.rag.rag.config.DocumentProperties;
import com.smart.rag.rag.entity.RagDocument;
import com.smart.rag.rag.etl.EtlStatus;
import com.smart.rag.rag.mapper.RagDocumentMapper;
import com.smart.rag.rag.service.FileStorageService;
import com.smart.rag.rag.service.EtlDispatchService;
import com.smart.rag.rag.service.DocumentDedupService;
import com.smart.rag.rag.service.DocumentMimePolicy;
import com.smart.rag.rag.service.TeamAccessGate;
import com.smart.rag.rag.service.impl.DocumentValidator;
import com.smart.rag.infrastructure.web.util.SecurityUtils;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.minio.MinioClient;
import io.minio.messages.DeleteRequest;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.unit.DataSize;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import java.util.concurrent.Executor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 分片上传服务实现（薄门面）。
 * <p>
 * 编排 Redis（会话状态 + Lua 原子操作）+ MinIO（putObject 分片 + composeObject 合并）+ DB（文档元数据），
 * 具体职责委托给三个协作对象：
 * <ul>
 *   <li>{@link ChunkSessionStore} — Redis 会话 Hash 读写与字段名常量</li>
 *   <li>{@link ChunkMinioGateway} — MinIO 对象读写与批量清理</li>
 *   <li>{@link ChunkMergeService} — 分片合并流程</li>
 * </ul>
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

    /** uploadId 格式（UUID v4 形态的 hex + 连字符），用于路径遍历防护 */
    private static final Pattern UPLOAD_ID_PATTERN =
            Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

    private final StringRedisTemplate redisTemplate;
    private final BucketResolver bucketResolver;
    private final ChunkSizeStrategy chunkSizeStrategy;
    private final DocumentProperties documentProperties;
    private final DocumentValidator documentValidator;
    private final FileStorageService fileStorageService;
    private final RagDocumentMapper ragDocumentMapper;
    private final Executor mergeExecutor;
    private final @Nullable DocumentDedupService documentDedupService;

    private final ChunkSessionStore sessionStore;
    private final ChunkMinioGateway minioGateway;
    private final ChunkMergeService mergeService;
    private final DocumentMimePolicy documentMimePolicy;
    private final DefaultRedisScript<List> atomicChunkUploadScript;

    public ChunkUploadServiceImpl(
            StringRedisTemplate redisTemplate,
            MinioClient minioClient,
            BucketResolver bucketResolver,
            ChunkSizeStrategy chunkSizeStrategy,
            DocumentProperties documentProperties,
            DocumentValidator documentValidator,
            DocumentMimePolicy documentMimePolicy,
            FileStorageService fileStorageService,
            RagDocumentMapper ragDocumentMapper,
            EtlDispatchService etlDispatchService,
            TeamAccessGate teamAccessGate,
            Executor mergeExecutor,
            ApplicationEventPublisher eventPublisher,
            @Nullable DocumentDedupService documentDedupService
    ) {
        this.redisTemplate = redisTemplate;
        this.bucketResolver = bucketResolver;
        this.chunkSizeStrategy = chunkSizeStrategy;
        this.documentProperties = documentProperties;
        this.documentValidator = documentValidator;
        this.documentMimePolicy = documentMimePolicy;
        this.fileStorageService = fileStorageService;
        this.ragDocumentMapper = ragDocumentMapper;
        this.mergeExecutor = mergeExecutor;
        this.documentDedupService = documentDedupService;

        this.sessionStore = new ChunkSessionStore(redisTemplate);
        this.minioGateway = new ChunkMinioGateway(minioClient, documentValidator);
        this.mergeService = new ChunkMergeService(sessionStore, minioGateway, teamAccessGate,
                documentValidator, ragDocumentMapper, etlDispatchService, eventPublisher, documentDedupService);

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

        // 秒传：BloomFilter 预筛 + DB 确认
        if (tryQuickUpload(request, userId) instanceof ChunkUploadResult result) {
            return result;
        }

        // 续传检查
        String existingUploadId = sessionStore.findResumableUploadId(userId, request.fileChecksum());
        if (existingUploadId != null) {
            ChunkUploadResult resumed = tryResume(existingUploadId, request, userId);
            if (resumed != null) {
                return resumed;
            }
        }

        return createNewSession(request, userId);
    }

    /** 秒传检查：命中返回 ChunkUploadResult，未命中返回 null */
    private @Nullable ChunkUploadResult tryQuickUpload(ChunkUploadInitRequest request, Long userId) {
        if (documentDedupService != null && !documentDedupService.mayExist(request.fileChecksum())) {
            log.debug("BloomFilter miss for fileChecksum={}, skipping quick-upload check", request.fileChecksum());
            return null;
        }
        RagDocument existing = findExistingForQuickUpload(request.fileChecksum(), userId, request.teamId());
        if (existing != null) {
            log.info("Quick upload hit: fileChecksum={}, userId={}, teamId={}, docId={}",
                    request.fileChecksum(), userId, request.teamId(), existing.getId());
            return ChunkUploadResult.quickUploaded(existing.getId(), existing.getFileName());
        }
        return null;
    }

    // ==================== uploadChunk ====================

    @Override
    public ChunkUploadResponse uploadChunk(String uploadId, int chunkIndex, String chunkChecksum, byte[] chunkData) {
        Long userId = SecurityUtils.getCurrentUserId();
        Map<String, String> session = validateSession(uploadId, userId);

        int totalChunks = ChunkSessionStore.parseSessionInt(session, ChunkSessionStore.FIELD_TOTAL_CHUNKS);

        if (chunkIndex < 0 || chunkIndex >= totalChunks) {
            throw new ClientException(ClientErrorCode.BAD_REQUEST, "分片序号超出范围: " + chunkIndex);
        }
        if (chunkData.length > ChunkUploadInitRequest.MAX_CHUNK_SIZE) {
            throw new ClientException(ClientErrorCode.BAD_REQUEST, "单分片大小不能超过50MB");
        }

        if (skipDuplicateChunk(uploadId, chunkIndex)) {
            return ChunkUploadResponse.uploaded(uploadId, chunkIndex);
        }

        String actualChunkChecksum = verifyChunkChecksum(uploadId, chunkIndex, chunkChecksum, chunkData);

        // 上传分片到 MinIO 临时路径
        String bucket = session.get(ChunkSessionStore.FIELD_BUCKET);
        String chunkObjectKey = UploadObjectKeys.chunkObjectKey(
                session.get(ChunkSessionStore.FIELD_OBJECT_NAME), chunkIndex);
        minioGateway.putObject(bucket, chunkObjectKey, chunkData, session.get(ChunkSessionStore.FIELD_MIME_TYPE));

        if (recordPartAndCheckMergeTrigger(uploadId, chunkIndex, actualChunkChecksum, totalChunks)) {
            log.info("All chunks uploaded, triggering async merge: uploadId={}", uploadId);
            dispatchAsyncMerge(uploadId);
            return ChunkUploadResponse.merging(uploadId, chunkIndex);
        }

        return ChunkUploadResponse.uploaded(uploadId, chunkIndex);
    }

    /** 幂等检查：分片已上传则返回 true */
    private boolean skipDuplicateChunk(String uploadId, int chunkIndex) {
        if (sessionStore.hasPart(uploadId, chunkIndex)) {
            log.debug("Chunk already uploaded: uploadId={}, index={}", uploadId, chunkIndex);
            return true;
        }
        return false;
    }

    /** 分片校验和（SHA-256）校验，通过则返回实际校验和 */
    private String verifyChunkChecksum(String uploadId, int chunkIndex, String chunkChecksum, byte[] chunkData) {
        String actualChunkChecksum = ChecksumUtils.sha256Hex(chunkData);
        if (!actualChunkChecksum.equalsIgnoreCase(chunkChecksum)) {
            log.warn("Chunk checksum mismatch: uploadId={}, index={}, expected={}, actual={}",
                    uploadId, chunkIndex, chunkChecksum, actualChunkChecksum);
            throw new ClientException(ClientErrorCode.UPLOAD_CHUNK_CHECKSUM_MISMATCH);
        }
        return actualChunkChecksum;
    }

    /** Lua 原子记录分片；返回是否应触发合并 */
    private boolean recordPartAndCheckMergeTrigger(String uploadId, int chunkIndex, String checksum, int totalChunks) {
        List result = redisTemplate.execute(
                atomicChunkUploadScript,
                List.of(UploadRedisConstants.partsKey(uploadId)),
                String.valueOf(chunkIndex), checksum,
                String.valueOf(totalChunks), UploadRedisConstants.MERGING_FIELD
        );
        return result != null && !result.isEmpty() && ((Number) result.get(0)).longValue() == 1;
    }

    /**
     * 异步合并。CRITICAL: __merging 标记必须在 finally 中无条件清除，
     * 否则任一非业务异常（网络/存储故障）会把会话卡死到 TTL 过期，complete 无法重试。
     */
    private void dispatchAsyncMerge(String uploadId) {
        mergeExecutor.execute(() -> {
            try {
                mergeService.performMerge(uploadId); // docId ignored for async path
            } catch (AbstractException e) {
                // 业务异常（如团队解散）：清理 __merging 标记，前端可感知
                log.warn("Auto-merge rejected: uploadId={}, reason={}", uploadId, e.getMessage());
            } catch (Exception e) {
                log.error("Auto-merge failed: uploadId={}", uploadId, e);
            } finally {
                sessionStore.clearMergingFlag(uploadId);
            }
        });
    }

    // ==================== status ====================

    @Override
    public ChunkUploadStatusResponse status(String uploadId) {
        Long userId = SecurityUtils.getCurrentUserId();
        Map<String, String> session = validateSession(uploadId, userId);

        List<Integer> uploadedChunks = new ArrayList<>(new TreeSet<>(sessionStore.uploadedPartIndexes(uploadId)));
        int totalChunks = ChunkSessionStore.parseSessionInt(session, ChunkSessionStore.FIELD_TOTAL_CHUNKS);
        boolean completed = uploadedChunks.size() == totalChunks;

        return new ChunkUploadStatusResponse(
                uploadId, session.get(ChunkSessionStore.FIELD_FILE_NAME), totalChunks,
                uploadedChunks, completed, sessionStore.isMerging(uploadId), null
        );
    }

    // ==================== complete ====================

    @Override
    public Long complete(String uploadId, String fileChecksum) {
        return complete(uploadId, fileChecksum, null);
    }

    @Override
    public Long complete(String uploadId, String fileChecksum, @Nullable Long expectedTeamId) {
        Long userId = SecurityUtils.getCurrentUserId();

        Map<String, String> session = sessionStore.load(uploadId);
        if (session.isEmpty()) {
            // Session already cleaned — merge was done. 用路径 teamId 做幂等回查，保留团队维度
            RagDocument doc = findExistingForQuickUpload(fileChecksum, userId, expectedTeamId);
            if (doc != null) {
                log.info("Complete idempotent: uploadId={} already merged as docId={}", uploadId, doc.getId());
                return doc.getId();
            }
            throw new ServiceException(ServiceErrorCode.UPLOAD_SESSION_NOT_FOUND);
        }

        validateOwner(session, userId);
        // 团队端点：校验会话 teamId 与路径 teamId 一致，避免同用户跨团队会话混用
        validateTeamScope(session, expectedTeamId);

        // Extract teamId from session for team-scoped quick-upload lookup
        Long teamId = ChunkSessionStore.parseNullableLong(
                session.get(ChunkSessionStore.FIELD_TEAM_ID), ChunkSessionStore.FIELD_TEAM_ID);

        if (!fileChecksum.equalsIgnoreCase(session.get(ChunkSessionStore.FIELD_FILE_CHECKSUM))) {
            throw new ClientException(ClientErrorCode.UPLOAD_FILE_CHECKSUM_MISMATCH, "声明的文件校验和与会话不匹配");
        }

        if (deferWhileMerging(uploadId, fileChecksum, userId, teamId) instanceof Long docId) {
            return docId;
        }

        return resolveMergeResult(uploadId, fileChecksum, userId, teamId);
    }

    /** 防止 autoMerge 进行中重复触发：检查 __merging 标记；命中返回既有 docId 或抛出重试提示，否则返回 null */
    private @Nullable Long deferWhileMerging(String uploadId, String fileChecksum, Long userId, @Nullable Long teamId) {
        if (!sessionStore.isMerging(uploadId)) {
            return null;
        }
        // autoMerge 正在进行中，等待完成后再查结果
        log.info("Complete deferred: auto-merge in progress, uploadId={}", uploadId);
        RagDocument existing = findExistingForQuickUpload(fileChecksum, userId, teamId);
        if (existing != null) {
            return existing.getId();
        }
        throw new ClientException(ClientErrorCode.BAD_REQUEST, "文件合并正在进行中，请稍后重试");
    }

    /**
     * R1-H1: performMerge 直接返回新持久化的 docId。
     * 防御性兜底：docId 为 null（如会话已被并发清理）则回退查询，
     * 仍查不到时抛 ServiceException，绝不返回 null 包成 200。
     */
    private Long resolveMergeResult(String uploadId, String fileChecksum, Long userId, @Nullable Long teamId) {
        Long docId = mergeService.performMerge(uploadId);
        if (docId != null) {
            return docId;
        }
        RagDocument doc = findExistingForQuickUpload(fileChecksum, userId, teamId);
        if (doc == null) {
            log.error("Post-merge document lookup failed: uploadId={}, fileChecksum={}, userId={}", uploadId, fileChecksum, userId);
            throw new ServiceException(ServiceErrorCode.ETL_FAILED, "合并后文档未找到");
        }
        return doc.getId();
    }

    // ==================== abort ====================

    @Override
    public void abort(String uploadId) {
        Long userId = SecurityUtils.getCurrentUserId();
        Map<String, String> session = validateSession(uploadId, userId);

        deleteUploadedChunks(uploadId, session);
        log.info("Chunk upload aborted: uploadId={}, user={}", uploadId, userId);

        sessionStore.cleanup(uploadId, session.get(ChunkSessionStore.FIELD_USER_ID),
                session.get(ChunkSessionStore.FIELD_FILE_CHECKSUM));
    }

    /** 批量删除已上传的临时分片（仅删除 Redis parts 中确实存在的对象），尽力而为 */
    private void deleteUploadedChunks(String uploadId, Map<String, String> session) {
        String bucket = session.get(ChunkSessionStore.FIELD_BUCKET);
        String basePath = session.get(ChunkSessionStore.FIELD_OBJECT_NAME);
        int totalChunks = ChunkSessionStore.parseSessionInt(session, ChunkSessionStore.FIELD_TOTAL_CHUNKS);

        List<DeleteRequest.Object> toDelete = new ArrayList<>();
        for (int i = 0; i < totalChunks; i++) {
            if (sessionStore.hasPart(uploadId, i)) {
                toDelete.add(new DeleteRequest.Object(UploadObjectKeys.chunkObjectKey(basePath, i)));
            }
        }
        try {
            minioGateway.removeObjects(bucket, toDelete);
        } catch (Exception e) {
            log.error("Error during abort cleanup: uploadId={}", uploadId, e);
        }
    }

    // ==================== 合并流程（委托 ChunkMergeService） ====================

    /**
     * 执行分片合并流程。
     *
     * @return 新文档的 docId；会话已不存在则返回 null
     * @see ChunkMergeService#performMerge(String)
     */
    Long performMerge(String uploadId) {
        return mergeService.performMerge(uploadId);
    }

    // ==================== 私有方法 ====================

    private void validateMimeType(String mimeType) {
        // 声明值仅做白名单预筛（别名经 Policy 归一化）；最终落库的规范 MIME 由合并后服务端校验产出
        if (!documentMimePolicy.isAllowed(mimeType)) {
            throw new ClientException(ClientErrorCode.UPLOAD_MIME_UNSUPPORTED, "不支持的文件类型: " + mimeType);
        }
    }

    private void validateFileSize(Long fileSize) {
        long maxBytes = DataSize.parse(documentProperties.getMaxFileSize()).toBytes();
        if (fileSize > maxBytes) {
            throw new ClientException(ClientErrorCode.UPLOAD_FILE_TOO_LARGE,
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
            throw new ClientException(ClientErrorCode.RATE_LIMITED, "上传初始化请求过于频繁");
        }
    }

    /**
     * 秒传查找：按 fileChecksum 查找已入库文档。
     * <p>
     * teamId 隔离规则：
     * - teamId != null → 查 teamId + fileChecksum（团队空间）
     * - teamId == null → 查 userId + fileChecksum（个人空间）
     * 避免跨团队或个人/团队之间的秒传误命中。
     */
    private RagDocument findExistingForQuickUpload(String fileChecksum, Long userId, @Nullable Long teamId) {
        LambdaQueryWrapper<RagDocument> wrapper = new LambdaQueryWrapper<RagDocument>()
                .eq(RagDocument::getFileChecksum, fileChecksum)
                .in(RagDocument::getStatus, EtlStatus.COMPLETED, EtlStatus.PROCESSING)
                .eq(RagDocument::getDeleted, 0);
        if (teamId != null) {
            wrapper.eq(RagDocument::getTeamId, teamId);
        } else {
            wrapper.eq(RagDocument::getUserId, userId)
                   .isNull(RagDocument::getTeamId);
        }
        return ragDocumentMapper.selectOne(wrapper.last("LIMIT 1"));
    }

    private @Nullable ChunkUploadResult tryResume(String uploadId, ChunkUploadInitRequest request, Long userId) {
        Map<String, String> session = sessionStore.load(uploadId);
        if (session.isEmpty()) {
            sessionStore.deleteFileIndex(userId, request.fileChecksum());
            return null;
        }

        validateOwner(session, userId);

        int chunkSize = ChunkSessionStore.parseSessionInt(session, ChunkSessionStore.FIELD_CHUNK_SIZE);
        int totalChunks = ChunkSessionStore.parseSessionInt(session, ChunkSessionStore.FIELD_TOTAL_CHUNKS);

        List<Integer> uploadedChunks = new ArrayList<>(new TreeSet<>(sessionStore.uploadedPartIndexes(uploadId)));

        log.info("Chunk upload resumed: uploadId={}, uploaded={}/{}", uploadId, uploadedChunks.size(), totalChunks);
        return ChunkUploadResult.resumeSession(uploadId, chunkSize, totalChunks, uploadedChunks);
    }

    private ChunkUploadResult createNewSession(ChunkUploadInitRequest request, Long userId) {
        int chunkSize = chunkSizeStrategy.calculateChunkSize(request.fileSize());
        int totalChunks = request.fileSize() <= chunkSize ? 1 : (int) ((request.fileSize() + chunkSize - 1) / chunkSize);

        if (request.fileSize() <= chunkSize) {
            chunkSize = (int) (long) request.fileSize();
        }

        String bucket = resolveBucket(request.teamId());
        String objectBasePath = UploadObjectKeys.CHUNKS_PREFIX + userId + "/" + UUID.randomUUID();
        String uploadId = UUID.randomUUID().toString();

        sessionStore.save(uploadId, buildSessionFields(request, bucket, objectBasePath, chunkSize, totalChunks, userId));
        sessionStore.putFileIndex(userId, request.fileChecksum(), uploadId);

        log.info("Chunk upload init: uploadId={}, file={}, size={}, chunks={}, user={}",
                uploadId, request.fileName(), request.fileSize(), totalChunks, userId);

        return ChunkUploadResult.newSession(uploadId, chunkSize, totalChunks);
    }

    private String resolveBucket(@Nullable Long teamId) {
        String bucket = bucketResolver.resolve(teamId);
        fileStorageService.ensureBucketExists(bucket);
        return bucket;
    }

    /** 组装会话 Hash 字段 */
    private Map<String, String> buildSessionFields(ChunkUploadInitRequest request, String bucket,
                                                   String objectBasePath, int chunkSize, int totalChunks, Long userId) {
        Map<String, String> fields = new HashMap<>(Map.ofEntries(
                Map.entry(ChunkSessionStore.FIELD_FILE_CHECKSUM, request.fileChecksum()),
                Map.entry(ChunkSessionStore.FIELD_FILE_NAME, request.fileName()),
                Map.entry(ChunkSessionStore.FIELD_FILE_SIZE, String.valueOf(request.fileSize())),
                Map.entry(ChunkSessionStore.FIELD_MIME_TYPE, documentMimePolicy.normalizeAlias(request.mimeType())),
                Map.entry(ChunkSessionStore.FIELD_CHUNK_SIZE, String.valueOf(chunkSize)),
                Map.entry(ChunkSessionStore.FIELD_TOTAL_CHUNKS, String.valueOf(totalChunks)),
                Map.entry(ChunkSessionStore.FIELD_USER_ID, String.valueOf(userId)),
                Map.entry(ChunkSessionStore.FIELD_BUCKET, bucket),
                Map.entry(ChunkSessionStore.FIELD_OBJECT_NAME, objectBasePath),
                Map.entry(ChunkSessionStore.FIELD_CREATED_AT, String.valueOf(System.currentTimeMillis()))
        ));
        if (request.teamId() != null) {
            fields.put(ChunkSessionStore.FIELD_TEAM_ID, String.valueOf(request.teamId()));
        }
        if (request.replaceDocumentId() != null) {
            fields.put(ChunkSessionStore.FIELD_REPLACE_DOCUMENT_ID, String.valueOf(request.replaceDocumentId()));
        }
        return fields;
    }

    private Map<String, String> validateSession(String uploadId, Long userId) {
        // 格式校验：防止路径遍历
        if (uploadId == null || !UPLOAD_ID_PATTERN.matcher(uploadId).matches()) {
            throw new ClientException(ClientErrorCode.BAD_REQUEST, "无效的上传会话ID");
        }
        Map<String, String> session = sessionStore.load(uploadId);
        if (session.isEmpty()) {
            throw new ServiceException(ServiceErrorCode.UPLOAD_SESSION_NOT_FOUND);
        }
        validateOwner(session, userId);
        return session;
    }

    private void validateOwner(Map<String, String> session, Long userId) {
        Long owner = ChunkSessionStore.parseSessionLong(session, ChunkSessionStore.FIELD_USER_ID);
        if (!owner.equals(userId)) {
            log.warn("Upload owner mismatch: expected={}, actual={}", owner, userId);
            throw new ClientException(ClientErrorCode.FORBIDDEN);
        }
    }

    /**
     * 校验会话 teamId 与期望 teamId 一致（团队端点专用）。
     * <p>
     * teamId 是会话的强约束字段，而非仅由 controller 门禁保证：服务层主动发现并拒绝不一致。
     */
    private void validateTeamScope(Map<String, String> session, @Nullable Long expectedTeamId) {
        Long sessionTeamId = ChunkSessionStore.parseNullableLong(
                session.get(ChunkSessionStore.FIELD_TEAM_ID), ChunkSessionStore.FIELD_TEAM_ID);
        boolean mismatch;
        if (expectedTeamId == null) {
            // 个人端点访问了团队会话 → 拒绝
            mismatch = sessionTeamId != null;
        } else {
            mismatch = sessionTeamId == null || !expectedTeamId.equals(sessionTeamId);
        }
        if (mismatch) {
            log.warn("Upload team-scope mismatch: expected={}, session={}, uploadId={}",
                    expectedTeamId, sessionTeamId, session.get(ChunkSessionStore.FIELD_USER_ID));
            throw new ClientException(ClientErrorCode.FORBIDDEN, "上传会话与请求的团队不匹配");
        }
    }

    @Override
    public void validateTeamScope(String uploadId, Long expectedTeamId) {
        Long userId = SecurityUtils.getCurrentUserId();
        Map<String, String> session = validateSession(uploadId, userId);
        validateTeamScope(session, expectedTeamId);
    }

    // ==================== R1-M2: session 字段安全解析（委托 ChunkSessionStore，保留入口以便既有调用方） ====================

    /** @see ChunkSessionStore#parseSessionLong(Map, String) */
    static long parseSessionLong(Map<String, String> session, String key) {
        return ChunkSessionStore.parseSessionLong(session, key);
    }

    /** @see ChunkSessionStore#parseSessionInt(Map, String) */
    static int parseSessionInt(Map<String, String> session, String key) {
        return ChunkSessionStore.parseSessionInt(session, key);
    }

    /** @see ChunkSessionStore#parseNullableLong(String, String) */
    static Long parseNullableLong(String v, String label) {
        return ChunkSessionStore.parseNullableLong(v, label);
    }
}
