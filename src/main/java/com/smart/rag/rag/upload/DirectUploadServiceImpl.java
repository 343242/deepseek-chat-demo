package com.smart.rag.rag.upload;

import com.smart.rag.infrastructure.exception.AbstractException;
import com.smart.rag.infrastructure.exception.ClientException;
import com.smart.rag.infrastructure.exception.ServiceException;
import com.smart.rag.infrastructure.exception.errorcode.ClientErrorCode;
import com.smart.rag.infrastructure.exception.errorcode.ServiceErrorCode;
import com.smart.rag.infrastructure.web.util.SecurityUtils;
import com.smart.rag.rag.config.DocumentProperties;
import com.smart.rag.rag.dto.DocumentUploadResponse;
import com.smart.rag.rag.entity.RagDocument;
import com.smart.rag.rag.etl.EtlStatus;
import com.smart.rag.rag.service.DocumentDedupService;
import com.smart.rag.rag.service.DocumentMimePolicy;
import com.smart.rag.rag.service.FileStorageService;
import com.smart.rag.rag.service.TeamAccessGate;
import com.smart.rag.rag.service.impl.DocumentValidator;
import com.smart.rag.rag.service.impl.ValidatedDocumentFile;
import com.smart.rag.rag.upload.s3.S3MultipartGateway;
import com.smart.rag.rag.upload.s3.UploadGoneException;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.unit.DataSize;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.smart.rag.rag.upload.DirectUploadRedisConstants.FIELD_BUCKET;
import static com.smart.rag.rag.upload.DirectUploadRedisConstants.FIELD_CHUNK_SIZE;
import static com.smart.rag.rag.upload.DirectUploadRedisConstants.FIELD_CREATED_AT;
import static com.smart.rag.rag.upload.DirectUploadRedisConstants.FIELD_DOCUMENT_ID;
import static com.smart.rag.rag.upload.DirectUploadRedisConstants.FIELD_FILE_CHECKSUM;
import static com.smart.rag.rag.upload.DirectUploadRedisConstants.FIELD_FILE_NAME;
import static com.smart.rag.rag.upload.DirectUploadRedisConstants.FIELD_FILE_SIZE;
import static com.smart.rag.rag.upload.DirectUploadRedisConstants.FIELD_MIME_TYPE;
import static com.smart.rag.rag.upload.DirectUploadRedisConstants.FIELD_MODE;
import static com.smart.rag.rag.upload.DirectUploadRedisConstants.FIELD_OBJECT_KEY;
import static com.smart.rag.rag.upload.DirectUploadRedisConstants.FIELD_REPLACE_DOCUMENT_ID;
import static com.smart.rag.rag.upload.DirectUploadRedisConstants.FIELD_RESULT_STATUS;
import static com.smart.rag.rag.upload.DirectUploadRedisConstants.FIELD_STATUS;
import static com.smart.rag.rag.upload.DirectUploadRedisConstants.FIELD_TEAM_ID;
import static com.smart.rag.rag.upload.DirectUploadRedisConstants.FIELD_TOTAL_CHUNKS;
import static com.smart.rag.rag.upload.DirectUploadRedisConstants.FIELD_UPLOAD_ID;
import static com.smart.rag.rag.upload.DirectUploadRedisConstants.FIELD_USER_ID;
import static com.smart.rag.rag.upload.DirectUploadRedisConstants.STATUS_ACTIVE;

/**
 * Presigned URL 直传控制面服务实现（设计文档：docs/design/presigned-direct-upload.md）。
 * <p>
 * 数据面（PUT 字节流）浏览器直达 MinIO，本类只承载控制面：init 签发、part-urls 批签、
 * status 元数据、commit 事实校验与落库、abort 清理。所有「事实校验」（尺寸/MIME/校验和）
 * 后移至 commit（presigned URL 无法强制 Content-Length 等事实条件）。
 * <p>
 * commit 状态机（ACTIVE → COMMITTING → COMMITTED）与崩溃接管、TOCTOU 闭环
 * （single 模式 MD5 对拍 + if-match 条件 copy）见 {@link DirectUploadSessionStore} 与
 * {@link S3MultipartGateway#copyObjectIfMatch}。
 */
@Service
public class DirectUploadServiceImpl implements DirectUploadService {

    private static final Logger log = LoggerFactory.getLogger(DirectUploadServiceImpl.class);

    private final DirectUploadSessionStore sessionStore;
    private final S3MultipartGateway gateway;
    private final DirectUploadProperties properties;
    private final DocumentProperties documentProperties;
    private final DocumentMimePolicy documentMimePolicy;
    private final DocumentValidator documentValidator;
    private final BucketResolver bucketResolver;
    private final FileStorageService fileStorageService;
    private final @Nullable DocumentDedupService documentDedupService;
    private final TeamAccessGate teamAccessGate;
    private final UploadDocumentPersistence persistence;

    @org.springframework.beans.factory.annotation.Autowired
    public DirectUploadServiceImpl(StringRedisTemplate redisTemplate,
                                   S3MultipartGateway gateway,
                                   DirectUploadProperties properties,
                                   DocumentProperties documentProperties,
                                   DocumentMimePolicy documentMimePolicy,
                                   DocumentValidator documentValidator,
                                   BucketResolver bucketResolver,
                                   FileStorageService fileStorageService,
                                   @Nullable DocumentDedupService documentDedupService,
                                   TeamAccessGate teamAccessGate,
                                   UploadDocumentPersistence persistence) {
        this(new DirectUploadSessionStore(redisTemplate), gateway, properties, documentProperties,
                documentMimePolicy, documentValidator, bucketResolver, fileStorageService,
                documentDedupService, teamAccessGate, persistence);
    }

    /** 测试直注构造器（绕过 redisTemplate 手工组装 store）。 */
    DirectUploadServiceImpl(DirectUploadSessionStore sessionStore,
                            S3MultipartGateway gateway,
                            DirectUploadProperties properties,
                            DocumentProperties documentProperties,
                            DocumentMimePolicy documentMimePolicy,
                            DocumentValidator documentValidator,
                            BucketResolver bucketResolver,
                            FileStorageService fileStorageService,
                            @Nullable DocumentDedupService documentDedupService,
                            TeamAccessGate teamAccessGate,
                            UploadDocumentPersistence persistence) {
        this.sessionStore = sessionStore;
        this.gateway = gateway;
        this.properties = properties;
        this.documentProperties = documentProperties;
        this.documentMimePolicy = documentMimePolicy;
        this.documentValidator = documentValidator;
        this.bucketResolver = bucketResolver;
        this.fileStorageService = fileStorageService;
        this.documentDedupService = documentDedupService;
        this.teamAccessGate = teamAccessGate;
        this.persistence = persistence;
    }

    // ==================== init ====================

    @Override
    public DirectUploadInitResult init(DirectUploadInitRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();

        // 声明预检（白名单 + 大小上限）；事实校验后移至 commit
        if (!documentMimePolicy.isAllowed(request.mimeType())) {
            throw new ClientException(ClientErrorCode.UPLOAD_MIME_UNSUPPORTED);
        }
        long maxBytes = DataSize.parse(documentProperties.getMaxFileSize()).toBytes();
        if (request.fileSize() > maxBytes) {
            throw new ClientException(ClientErrorCode.UPLOAD_FILE_TOO_LARGE);
        }

        // direct init 独立限流桶（30 次/分：direct 化后每文件一次 init，旧 10/分桶必然 429）
        if (sessionStore.incrRateLimit(userId) > properties.getInitRateLimitPerMinute()) {
            throw new ClientException(ClientErrorCode.RATE_LIMITED, "直传初始化请求过于频繁");
        }

        Long teamId = request.teamId();
        if (teamId != null) {
            teamAccessGate.verifyAccess(teamId, userId);
            teamAccessGate.verifyUploadQuota(teamId, userId, request.fileSize());
        }

        DirectUploadInitResult instant = tryInstant(request, userId, teamId);
        if (instant != null) {
            return instant;
        }

        DirectUploadInitResult resumed = tryResume(request, userId, teamId);
        if (resumed != null) {
            return resumed;
        }

        return createNewSession(request, userId, teamId);
    }

    /** 秒传：BloomFilter 预筛 + DB 确认（teamId 隔离规则与代理路径一致）。 */
    private @Nullable DirectUploadInitResult tryInstant(DirectUploadInitRequest request, Long userId, @Nullable Long teamId) {
        if (documentDedupService != null && !documentDedupService.mayExist(request.fileChecksum())) {
            return null;
        }
        RagDocument existing = documentDedupService != null
                ? documentDedupService.confirmExisting(request.fileChecksum(), userId, teamId)
                : null;
        if (existing == null) {
            return null;
        }
        // 边界：秒传命中的既有文档 == replace 目标自身（同 checksum）时直接幂等返回，
        // 不触发 supersede（无事件发布，天然满足）
        log.info("Direct upload instant hit: fileChecksum={}, userId={}, teamId={}, docId={}",
                request.fileChecksum(), userId, teamId, existing.getId());
        return DirectUploadInitResult.instant(existing.getId(), existing.getFileName());
    }

    /** 续传：反向索引命中既有会话（秒传判定之后）。COMMITTED 会话按秒传语义返回。 */
    private @Nullable DirectUploadInitResult tryResume(DirectUploadInitRequest request, Long userId, @Nullable Long teamId) {
        String existingId = sessionStore.findResumableSessionId(userId, request.fileChecksum());
        if (existingId == null) {
            return null;
        }
        Map<String, String> session = sessionStore.load(existingId);
        if (session.isEmpty()
                || DirectUploadSessionStore.parseSessionLong(session, FIELD_USER_ID) != userId
                || !java.util.Objects.equals(DirectUploadSessionStore.sessionTeamId(session), teamId)) {
            // 陈旧反向索引（他人/异空间会话）：视为不可续传
            return null;
        }
        String status = session.get(FIELD_STATUS);
        if (DirectUploadRedisConstants.STATUS_COMMITTED.equals(status)) {
            return DirectUploadInitResult.instant(
                    DirectUploadSessionStore.parseSessionLong(session, FIELD_DOCUMENT_ID),
                    session.get(FIELD_FILE_NAME));
        }
        if (!STATUS_ACTIVE.equals(status) && !DirectUploadRedisConstants.STATUS_COMMITTING.equals(status)) {
            return null; // ABORTED 等：重新建会话
        }
        if (DirectUploadSessionStore.parseSessionLong(session, FIELD_FILE_SIZE) != request.fileSize()) {
            return null; // 声明与既有会话不符（同 checksum 不同尺寸的极小概率冲突）：重新建会话
        }
        String sessionId = existingId;
        if (DirectUploadInitResult.MODE_SINGLE.equals(session.get(FIELD_MODE))) {
            // presigned URL 过期重签（S3 presign 无一次性约束，重签即新能力）
            String bucket = session.get(FIELD_BUCKET);
            String objectKey = session.get(FIELD_OBJECT_KEY);
            String url = gateway.presignPutUrl(bucket, objectKey, properties.getPresignExpiry());
            return DirectUploadInitResult.single(sessionId, url,
                    System.currentTimeMillis() + properties.getPresignExpiry().toMillis(),
                    session.get(FIELD_MIME_TYPE));
        }
        return DirectUploadInitResult.multipart(sessionId, session.get(FIELD_UPLOAD_ID),
                DirectUploadSessionStore.parseSessionInt(session, FIELD_CHUNK_SIZE),
                DirectUploadSessionStore.parseSessionInt(session, FIELD_TOTAL_CHUNKS),
                System.currentTimeMillis() + properties.getPresignExpiry().toMillis());
    }

    private DirectUploadInitResult createNewSession(DirectUploadInitRequest request, Long userId, @Nullable Long teamId) {
        String sessionId = UUID.randomUUID().toString();
        String bucket = bucketResolver.resolve(teamId);
        fileStorageService.ensureBucketExists(bucket);
        String objectKey = UploadObjectKeys.pendingObjectKey(userId, sessionId, request.fileName());

        boolean single = request.fileSize() <= properties.getMultipartThresholdBytes();
        Map<String, String> fields = new HashMap<>();
        fields.put(FIELD_STATUS, STATUS_ACTIVE);
        fields.put(FIELD_FILE_CHECKSUM, request.fileChecksum());
        fields.put(FIELD_FILE_NAME, request.fileName());
        fields.put(FIELD_FILE_SIZE, String.valueOf(request.fileSize()));
        fields.put(FIELD_MIME_TYPE, request.mimeType());
        fields.put(FIELD_BUCKET, bucket);
        fields.put(FIELD_OBJECT_KEY, objectKey);
        fields.put(FIELD_USER_ID, String.valueOf(userId));
        fields.put(FIELD_TEAM_ID, teamId != null ? teamId.toString() : "");
        fields.put(FIELD_REPLACE_DOCUMENT_ID, request.replaceDocumentId() != null ? request.replaceDocumentId().toString() : "");
        fields.put(FIELD_CREATED_AT, String.valueOf(System.currentTimeMillis()));

        long expiresAt = System.currentTimeMillis() + properties.getPresignExpiry().toMillis();
        if (single) {
            String uploadUrl = gateway.presignPutUrl(bucket, objectKey, properties.getPresignExpiry());
            fields.put(FIELD_MODE, DirectUploadInitResult.MODE_SINGLE);
            fields.put(FIELD_CHUNK_SIZE, String.valueOf(request.fileSize()));
            fields.put(FIELD_TOTAL_CHUNKS, "1");
            sessionStore.save(sessionId, fields);
            sessionStore.putFileIndex(userId, request.fileChecksum(), sessionId);
            log.info("Direct upload session created (single): sessionId={}, userId={}, teamId={}, size={}",
                    sessionId, userId, teamId, request.fileSize());
            return DirectUploadInitResult.single(sessionId, uploadUrl, expiresAt, request.mimeType());
        }

        long chunkSize = properties.getChunkSizeBytes();
        int totalChunks = (int) ((request.fileSize() + chunkSize - 1) / chunkSize);
        String uploadId = gateway.createMultipartUpload(bucket, objectKey);
        sessionStore.registerMpu(bucket, objectKey, uploadId);
        fields.put(FIELD_MODE, DirectUploadInitResult.MODE_MULTIPART);
        fields.put(FIELD_UPLOAD_ID, uploadId);
        fields.put(FIELD_CHUNK_SIZE, String.valueOf(chunkSize));
        fields.put(FIELD_TOTAL_CHUNKS, String.valueOf(totalChunks));
        sessionStore.save(sessionId, fields);
        sessionStore.putFileIndex(userId, request.fileChecksum(), sessionId);
        log.info("Direct upload session created (multipart): sessionId={}, userId={}, teamId={}, uploadId={}, totalChunks={}",
                sessionId, userId, teamId, uploadId, totalChunks);
        return DirectUploadInitResult.multipart(sessionId, uploadId, (int) chunkSize, totalChunks, expiresAt);
    }

    // ==================== part-urls ====================

    @Override
    public DirectUploadPartUrlsResult partUrls(String sessionId, DirectUploadPartUrlsRequest request,
                                               @Nullable Long expectedTeamId) {
        Long userId = SecurityUtils.getCurrentUserId();
        Map<String, String> session = validateSession(sessionId, userId, expectedTeamId);
        requireMode(session, DirectUploadInitResult.MODE_MULTIPART);
        requireActive(session);

        int totalChunks = DirectUploadSessionStore.parseSessionInt(session, FIELD_TOTAL_CHUNKS);
        List<Integer> partNumbers = request.partNumbers();
        if (partNumbers.size() > properties.getMaxPartsPerBatch()) {
            throw new ClientException(ClientErrorCode.BAD_REQUEST,
                    "单批分片签发数不能超过" + properties.getMaxPartsPerBatch());
        }
        Set<Integer> distinct = new HashSet<>();
        for (Integer n : partNumbers) {
            if (n == null || n < 1 || n > totalChunks) {
                throw new ClientException(ClientErrorCode.BAD_REQUEST, "分片序号超出范围: " + n);
            }
            if (!distinct.add(n)) {
                throw new ClientException(ClientErrorCode.BAD_REQUEST, "分片序号重复: " + n);
            }
        }

        String bucket = session.get(FIELD_BUCKET);
        String objectKey = session.get(FIELD_OBJECT_KEY);
        String uploadId = session.get(FIELD_UPLOAD_ID);
        List<DirectUploadPartUrlsResult.PartUrl> urls = new ArrayList<>(partNumbers.size());
        for (Integer n : partNumbers) {
            urls.add(new DirectUploadPartUrlsResult.PartUrl(n,
                    gateway.presignPartUrl(bucket, objectKey, n, uploadId, properties.getPresignExpiry())));
        }
        return new DirectUploadPartUrlsResult(
                System.currentTimeMillis() + properties.getPresignExpiry().toMillis(), urls);
    }

    // ==================== status ====================

    @Override
    public DirectUploadStatusResponse status(String sessionId, @Nullable Long expectedTeamId) {
        Long userId = SecurityUtils.getCurrentUserId();
        Map<String, String> session = validateSession(sessionId, userId, expectedTeamId);

        String status = session.get(FIELD_STATUS);
        Long documentId = DirectUploadRedisConstants.STATUS_COMMITTED.equals(status)
                ? DirectUploadSessionStore.parseSessionLong(session, FIELD_DOCUMENT_ID)
                : null;
        String mode = session.get(FIELD_MODE);
        return new DirectUploadStatusResponse(
                sessionId, status, mode,
                session.get(FIELD_FILE_NAME),
                DirectUploadSessionStore.parseSessionLong(session, FIELD_FILE_SIZE),
                session.get(FIELD_MIME_TYPE),
                DirectUploadInitResult.MODE_MULTIPART.equals(mode) ? session.get(FIELD_UPLOAD_ID) : null,
                DirectUploadSessionStore.parseSessionInt(session, FIELD_CHUNK_SIZE),
                DirectUploadSessionStore.parseSessionInt(session, FIELD_TOTAL_CHUNKS),
                documentId);
    }

    // ==================== commit ====================

    @Override
    public DocumentUploadResponse commit(String sessionId, DirectUploadCommitRequest request,
                                         @Nullable Long expectedTeamId) {
        Long userId = SecurityUtils.getCurrentUserId();
        Map<String, String> session = validateSession(sessionId, userId, expectedTeamId);

        DirectUploadSessionStore.CommitAcquire acquire = sessionStore.acquireForCommit(
                sessionId, properties.getCommitLeaseTtl());
        switch (acquire) {
            case ALREADY_COMMITTED:
                // 确定性幂等回查（documentId + resultStatus 在翻转 COMMITTED 时原子写入）
                Long docId = DirectUploadSessionStore.parseSessionLong(session, FIELD_DOCUMENT_ID);
                String statusName = session.getOrDefault(FIELD_RESULT_STATUS, EtlStatus.PROCESSING.name());
                return new DocumentUploadResponse(docId, session.get(FIELD_FILE_NAME), EtlStatus.valueOf(statusName));
            case CONFLICT:
                // 对端 COMMITTING 且租约存活：绝不按已提交假成功，让前端短暂等待重试
                throw new ClientException(ClientErrorCode.BAD_REQUEST, "提交正在进行中，请稍后重试");
            case REJECTED:
                throw new ServiceException(ServiceErrorCode.UPLOAD_SESSION_NOT_FOUND, "上传会话不存在或已取消");
            case ACQUIRED:
            case TAKEOVER:
            default:
                return doCommit(sessionId, session, request, userId, acquire == DirectUploadSessionStore.CommitAcquire.TAKEOVER);
        }
    }

    private DocumentUploadResponse doCommit(String sessionId, Map<String, String> session,
                                            DirectUploadCommitRequest request, Long userId, boolean takeover) {
        String bucket = session.get(FIELD_BUCKET);
        String objectKey = session.get(FIELD_OBJECT_KEY);
        String uploadId = session.get(FIELD_UPLOAD_ID);
        String checksum = session.get(FIELD_FILE_CHECKSUM);
        boolean multipart = DirectUploadInitResult.MODE_MULTIPART.equals(session.get(FIELD_MODE));
        Long teamId = DirectUploadSessionStore.sessionTeamId(session);
        Long replaceId = DirectUploadSessionStore.sessionReplaceDocumentId(session);

        try {
            if (multipart) {
                List<S3MultipartGateway.CompletedPart> parts = validateParts(session, request);
                completeMultipart(sessionId, bucket, objectKey, uploadId, parts, takeover);
            } else if (request.parts() != null && !request.parts().isEmpty()) {
                throw new ServiceException(ServiceErrorCode.DIRECT_UPLOAD_MODE_INVALID, "single 会话不接受分片列表");
            }

            CommitOutcome outcome = verifyCopyAndPersist(session, userId, teamId, replaceId, checksum, !multipart);

            // 成功收尾：先原子写 documentId+resultStatus 并翻转 COMMITTED（幂等回查确定），
            // 再做 best-effort 清理（pending 删除失败由 cleaner 24h 兜底）
            sessionStore.markCommitted(sessionId, outcome.documentId(), outcome.resultStatus());
            sessionStore.deleteFileIndex(userId, checksum);
            if (multipart) {
                sessionStore.unregisterMpu(bucket, objectKey, uploadId);
            }
            gateway.removeObjectQuietly(bucket, objectKey);
            log.info("Direct upload committed: sessionId={}, docId={}, mode={}, userId={}, teamId={}",
                    sessionId, outcome.documentId(), session.get(FIELD_MODE), userId, teamId);
            return new DocumentUploadResponse(outcome.documentId(),
                    session.get(FIELD_FILE_NAME), EtlStatus.valueOf(outcome.resultStatus()));
        } catch (UploadGoneException e) {
            // 会话已死（cleaner abort / 并发 Complete / pending 被清）：彻底终结防续传死循环
            log.warn("Direct upload session gone during commit: sessionId={}, reason={}", sessionId, e.getMessage());
            sessionStore.markAborted(sessionId);
            sessionStore.cleanup(sessionId, userId, checksum);
            if (multipart) {
                sessionStore.unregisterMpu(bucket, objectKey, uploadId);
            }
            throw e;
        } catch (AbstractException e) {
            // 校验/复制/落库失败：清理已污染的 pending，回退 ACTIVE 允许重试
            log.warn("Direct upload commit failed: sessionId={}, code={}, reason={}",
                    sessionId, e.getErrorCode().getCode(), e.getMessage());
            gateway.removeObjectQuietly(bucket, objectKey);
            sessionStore.rollbackToActive(sessionId);
            throw e;
        }
    }

    /** commit 结果（documentId + 对外响应状态） */
    private record CommitOutcome(Long documentId, String resultStatus) {}

    /**
     * 校验前端回传分片声明：序号完整连续（1..totalChunks）+ 声明尺寸对拍
     * （非末片 == chunkSize；末片 == fileSize - chunkSize*(n-1)）。
     */
    private List<S3MultipartGateway.CompletedPart> validateParts(Map<String, String> session,
                                                                 DirectUploadCommitRequest request) {
        List<DirectUploadCommitRequest.PartDeclaration> parts = request.parts();
        if (parts == null || parts.isEmpty()) {
            throw new ServiceException(ServiceErrorCode.DIRECT_UPLOAD_PARTS_INCOMPLETE, "缺少分片列表");
        }
        int totalChunks = DirectUploadSessionStore.parseSessionInt(session, FIELD_TOTAL_CHUNKS);
        long chunkSize = DirectUploadSessionStore.parseSessionLong(session, FIELD_CHUNK_SIZE);
        long fileSize = DirectUploadSessionStore.parseSessionLong(session, FIELD_FILE_SIZE);
        long lastPartSize = fileSize - chunkSize * (totalChunks - 1);

        boolean[] seen = new boolean[totalChunks + 1];
        List<S3MultipartGateway.CompletedPart> completed = new ArrayList<>(totalChunks);
        for (DirectUploadCommitRequest.PartDeclaration p : parts) {
            int n = p.partNumber();
            if (n < 1 || n > totalChunks || seen[n]) {
                throw new ServiceException(ServiceErrorCode.DIRECT_UPLOAD_PARTS_INCOMPLETE,
                        "分片列表不连续或缺失: " + n);
            }
            seen[n] = true;
            long expected = n == totalChunks ? lastPartSize : chunkSize;
            if (p.size() != expected) {
                throw new ServiceException(ServiceErrorCode.DIRECT_UPLOAD_SIZE_MISMATCH,
                        "分片尺寸与声明不符: part=" + n);
            }
            completed.add(new S3MultipartGateway.CompletedPart(n, p.etag()));
        }
        for (int i = 1; i <= totalChunks; i++) {
            if (!seen[i]) {
                throw new ServiceException(ServiceErrorCode.DIRECT_UPLOAD_PARTS_INCOMPLETE, "分片列表不连续或缺失: " + i);
            }
        }
        return completed;
    }

    /**
     * Complete（含 H1 崩溃接管三分支）：
     * <ul>
     *   <li>常规：Complete（ETag 由 S3 逐片校验，伪造即 InvalidPart）；</li>
     *   <li>接管 + pending 已存在：Complete 已发生（uploadId 消亡是预期），不得重试 Complete；</li>
     *   <li>接管 + pending 缺失 + uploadId 存活：Complete 前崩溃 → 从 Complete 幂等重试；</li>
     *   <li>接管 + 双亡：真正 UPLOAD_GONE。</li>
     * </ul>
     */
    private void completeMultipart(String sessionId, String bucket, String objectKey, String uploadId,
                                   List<S3MultipartGateway.CompletedPart> parts, boolean takeover) {
        if (takeover) {
            try {
                gateway.statObject(bucket, objectKey);
                log.info("Commit takeover: pending exists, skipping Complete (uploadId expected dead): sessionId={}", sessionId);
                return; // (a) pending 存在：Complete 已发生
            } catch (UploadGoneException e) {
                // (b) pending 缺失：Complete 前崩溃 → 从 Complete 重试（parts 自带，安全幂等）
                log.info("Commit takeover: pending missing, retrying Complete: sessionId={}", sessionId);
            }
        }
        gateway.completeMultipartUpload(bucket, objectKey, uploadId, parts);
    }

    /**
     * commit 事实校验 + 条件 copy + 落库（安全校验后移的落点）。
     * <p>
     * TOCTOU 闭环：statObject 取 ETag+尺寸 → 单遍流式 SHA-256（对拍声明）+ MD5（single 模式
     * 对拍 ETag，防 presigned URL 有效期内重放覆盖）→ Tika 魔数探测 → if-match 条件 copy
     * （任一环节失配即拒绝，未校验内容永不进入 documents/）。
     */
    private CommitOutcome verifyCopyAndPersist(Map<String, String> session, Long userId,
                                               @Nullable Long teamId, @Nullable Long replaceId,
                                               String checksum, boolean singleMode) {
        String bucket = session.get(FIELD_BUCKET);
        String objectKey = session.get(FIELD_OBJECT_KEY);
        String fileName = session.get(FIELD_FILE_NAME);
        long declaredSize = DirectUploadSessionStore.parseSessionLong(session, FIELD_FILE_SIZE);

        S3MultipartGateway.PendingObjectStat stat = gateway.statObject(bucket, objectKey);
        if (stat.size() != declaredSize) {
            throw new ServiceException(ServiceErrorCode.DIRECT_UPLOAD_SIZE_MISMATCH,
                    "实际尺寸与声明不符: declared=" + declaredSize + ", actual=" + stat.size());
        }

        S3MultipartGateway.ObjectDigests digests = gateway.computeDigests(bucket, objectKey);
        if (!digests.sha256Hex().equalsIgnoreCase(checksum)) {
            log.warn("Direct upload checksum mismatch: object={}, declared={}, actual={}",
                    objectKey, checksum, digests.sha256Hex());
            throw new ServiceException(ServiceErrorCode.DIRECT_UPLOAD_CHECKSUM_MISMATCH);
        }
        // single 模式 MD5 对拍（单次 PUT 的对象 ETag == 内容 MD5）；multipart 对象 ETag 为
        // MD5(各分片 ETag 拼接)-N 形式，不做对拍（N1，误判会全量拒绝 >5MB 直传）
        if (singleMode && !digests.md5Hex().equalsIgnoreCase(stat.etag())) {
            log.warn("Direct upload single-mode MD5 mismatch (possible replay overwrite): object={}, statEtag={}, md5={}",
                    objectKey, stat.etag(), digests.md5Hex());
            throw new ServiceException(ServiceErrorCode.DIRECT_UPLOAD_CHECKSUM_MISMATCH, "对象在校验前被修改");
        }

        ValidatedDocumentFile validated;
        try (InputStream is = gateway.getObject(bucket, objectKey)) {
            validated = documentValidator.validate(is, fileName, stat.size());
        } catch (java.io.IOException e) {
            throw new ServiceException(ServiceErrorCode.INTERNAL_ERROR, "读取直传对象失败", e);
        }

        // commit 复核团队额度（防 init 后额度被并发占满）
        if (teamId != null) {
            teamAccessGate.verifyUploadQuota(teamId, userId, stat.size());
        }

        String canonicalMime = validated.canonicalMimeType();
        String finalKey = StorageKeys.documentObjectKey(userId, fileName);
        gateway.copyObjectIfMatch(bucket, objectKey, finalKey, stat.etag(), canonicalMime);

        try {
            if (teamId == null) {
                RagDocument doc = persistence.insert(new UploadDocumentPersistence.Insert(
                        fileName, stat.size(), canonicalMime, finalKey, bucket, userId, null,
                        checksum, EtlStatus.UPLOADED));
                persistence.registerDedup(checksum);
                persistence.publishCreated(doc.getId(), replaceId, userId, null);
                persistence.dispatchEtl(doc.getId(), bucket, finalKey, fileName, canonicalMime,
                        stat.size(), userId, null);
                return new CommitOutcome(doc.getId(), EtlStatus.PROCESSING.name());
            }
            boolean autoApproved = teamAccessGate.verifyAccess(teamId, userId).manager();
            RagDocument doc = persistence.insert(new UploadDocumentPersistence.Insert(
                    fileName, stat.size(), canonicalMime, finalKey, bucket, userId, teamId,
                    checksum, autoApproved ? EtlStatus.PROCESSING : EtlStatus.PENDING_APPROVAL));
            persistence.publishCreated(doc.getId(), replaceId, userId, teamId);
            if (!autoApproved) {
                teamAccessGate.createUploadApproval(teamId, doc.getId(), userId);
            } else {
                persistence.dispatchEtl(doc.getId(), bucket, finalKey, fileName, canonicalMime,
                        stat.size(), userId, teamId);
            }
            return new CommitOutcome(doc.getId(), doc.getStatus().name());
        } catch (RuntimeException e) {
            // copy 已成功但 persist 失败：回滚删除最终对象（对齐 PersonalUploadStrategy R1-H3 语义），
            // pending 由外层 catch 统一清理
            gateway.removeObjectQuietly(bucket, finalKey);
            throw e;
        }
    }

    // ==================== abort ====================

    @Override
    public void abort(String sessionId, @Nullable Long expectedTeamId) {
        Long userId = SecurityUtils.getCurrentUserId();
        Map<String, String> session = validateSession(sessionId, userId, expectedTeamId);

        String bucket = session.get(FIELD_BUCKET);
        String objectKey = session.get(FIELD_OBJECT_KEY);
        String checksum = session.get(FIELD_FILE_CHECKSUM);
        boolean multipart = DirectUploadInitResult.MODE_MULTIPART.equals(session.get(FIELD_MODE));
        String uploadId = session.get(FIELD_UPLOAD_ID);

        sessionStore.markAborted(sessionId);
        if (multipart && uploadId != null) {
            gateway.abortMultipartUploadQuietly(bucket, objectKey, uploadId);
            sessionStore.unregisterMpu(bucket, objectKey, uploadId);
        }
        // single 已 PUT / multipart 已 Complete 未 commit 的 pending 可见对象一并删除；
        // 与迟到 PUT 的竞态窗口由 cleaner（无会话 + 24h）兜底，不做额外防护
        gateway.removeObjectQuietly(bucket, objectKey);
        sessionStore.cleanup(sessionId, userId, checksum);
        log.info("Direct upload aborted: sessionId={}, userId={}", sessionId, userId);
    }

    // ==================== 公共校验 ====================

    /**
     * 会话端点入口统一校验：存在 → owner → 团队一致性（防会话劫持，
     * 对齐 ChunkUploadServiceImpl.validateOwner / validateTeamScope 惯例）。
     */
    private Map<String, String> validateSession(String sessionId, Long userId, @Nullable Long expectedTeamId) {
        Map<String, String> session = sessionStore.load(sessionId);
        if (session.isEmpty()) {
            throw new ServiceException(ServiceErrorCode.UPLOAD_SESSION_NOT_FOUND);
        }
        if (DirectUploadSessionStore.parseSessionLong(session, FIELD_USER_ID) != userId) {
            throw new ClientException(ClientErrorCode.FORBIDDEN);
        }
        Long teamId = DirectUploadSessionStore.sessionTeamId(session);
        if (expectedTeamId == null ? teamId != null : !expectedTeamId.equals(teamId)) {
            throw new ClientException(ClientErrorCode.FORBIDDEN, "上传会话与请求的团队不匹配");
        }
        return session;
    }

    private void requireMode(Map<String, String> session, String expectedMode) {
        if (!expectedMode.equals(session.get(FIELD_MODE))) {
            throw new ServiceException(ServiceErrorCode.DIRECT_UPLOAD_MODE_INVALID, "会话模式与请求不匹配");
        }
    }

    private void requireActive(Map<String, String> session) {
        if (!STATUS_ACTIVE.equals(session.get(FIELD_STATUS))) {
            throw new ClientException(ClientErrorCode.BAD_REQUEST, "会话状态不允许该操作");
        }
    }
}
