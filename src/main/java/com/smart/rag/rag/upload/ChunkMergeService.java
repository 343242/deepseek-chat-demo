package com.smart.rag.rag.upload;

import com.smart.rag.infrastructure.exception.ClientException;
import com.smart.rag.infrastructure.exception.ServiceException;
import com.smart.rag.infrastructure.exception.errorcode.ClientErrorCode;
import com.smart.rag.infrastructure.exception.errorcode.ServiceErrorCode;
import com.smart.rag.rag.entity.RagDocument;
import com.smart.rag.rag.etl.EtlStatus;
import com.smart.rag.rag.event.DocumentCreatedEvent;
import com.smart.rag.rag.mapper.RagDocumentMapper;
import com.smart.rag.rag.service.DocumentDedupService;
import com.smart.rag.rag.service.EtlDispatchService;
import com.smart.rag.rag.service.TeamAccessGate;
import com.smart.rag.rag.service.impl.DocumentValidator;
import io.minio.SourceObject;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 分片合并服务。
 * <p>
 * 拥有 {@code performMerge} 流程：composeObject 合并 → 校验和/魔数校验 →
 * 落库 + 去重登记 + 清理 + ETL 派发 + 事件发布。
 * <p>
 * R1-H1: {@link #performMerge(String)} 返回新持久化的 docId，
 * 会话已被清理（幂等重复触发）时返回 {@code null}。
 */
public class ChunkMergeService {

    private static final Logger log = LoggerFactory.getLogger(ChunkMergeService.class);

    private final ChunkSessionStore sessionStore;
    private final ChunkMinioGateway minioGateway;
    private final TeamAccessGate teamAccessGate;
    private final DocumentValidator documentValidator;
    private final RagDocumentMapper ragDocumentMapper;
    private final EtlDispatchService etlDispatchService;
    private final ApplicationEventPublisher eventPublisher;
    private final @Nullable DocumentDedupService documentDedupService;

    public ChunkMergeService(ChunkSessionStore sessionStore,
                             ChunkMinioGateway minioGateway,
                             TeamAccessGate teamAccessGate,
                             DocumentValidator documentValidator,
                             RagDocumentMapper ragDocumentMapper,
                             EtlDispatchService etlDispatchService,
                             ApplicationEventPublisher eventPublisher,
                             @Nullable DocumentDedupService documentDedupService) {
        this.sessionStore = sessionStore;
        this.minioGateway = minioGateway;
        this.teamAccessGate = teamAccessGate;
        this.documentValidator = documentValidator;
        this.ragDocumentMapper = ragDocumentMapper;
        this.etlDispatchService = etlDispatchService;
        this.eventPublisher = eventPublisher;
        this.documentDedupService = documentDedupService;
    }

    /**
     * 执行分片合并流程。
     *
     * @return 新文档的 docId；会话已不存在则返回 null
     */
    public @Nullable Long performMerge(String uploadId) {
        Map<String, String> session = sessionStore.load(uploadId);
        if (session.isEmpty()) {
            log.warn("Merge skipped: session already cleaned, uploadId={}", uploadId);
            return null;
        }

        Long teamId = validateTeamActive(uploadId, session);

        String bucket = session.get(ChunkSessionStore.FIELD_BUCKET);
        String basePath = session.get(ChunkSessionStore.FIELD_OBJECT_NAME);
        int totalChunks = ChunkSessionStore.parseSessionInt(session, ChunkSessionStore.FIELD_TOTAL_CHUNKS);
        String declaredChecksum = session.get(ChunkSessionStore.FIELD_FILE_CHECKSUM);
        String declaredMimeType = session.get(ChunkSessionStore.FIELD_MIME_TYPE);
        String originalName = session.get(ChunkSessionStore.FIELD_FILE_NAME);

        List<SourceObject> sources = buildSources(bucket, basePath, totalChunks);
        String targetObjectKey = StorageKeys.documentObjectKey(
                ChunkSessionStore.parseSessionLong(session, ChunkSessionStore.FIELD_USER_ID), originalName);
        minioGateway.composeObjects(bucket, targetObjectKey, sources, declaredMimeType);

        String detectedMimeType = validateMergedObject(uploadId, session, bucket, basePath,
                targetObjectKey, declaredChecksum, declaredMimeType, originalName);

        return finalizeMerge(uploadId, session, bucket, basePath, totalChunks,
                targetObjectKey, declaredChecksum, declaredMimeType, detectedMimeType, teamId);
    }

    /**
     * 团队状态校验：团队已解散则清理并拒绝合并。
     *
     * @return 会话中的 teamId（无则 null）
     */
    private @Nullable Long validateTeamActive(String uploadId, Map<String, String> session) {
        Long teamId = ChunkSessionStore.parseNullableLong(
                session.get(ChunkSessionStore.FIELD_TEAM_ID), ChunkSessionStore.FIELD_TEAM_ID);
        if (teamId != null && !teamAccessGate.isTeamActive(teamId)) {
            log.warn("Merge rejected: team dissolved, teamId={}, uploadId={}", teamId, uploadId);
            String bucket = session.get(ChunkSessionStore.FIELD_BUCKET);
            int totalChunks = ChunkSessionStore.parseSessionInt(session, ChunkSessionStore.FIELD_TOTAL_CHUNKS);
            minioGateway.cleanupTempChunks(bucket, session.get(ChunkSessionStore.FIELD_OBJECT_NAME), totalChunks);
            sessionStore.cleanup(uploadId, session.get(ChunkSessionStore.FIELD_USER_ID),
                    session.get(ChunkSessionStore.FIELD_FILE_CHECKSUM));
            throw new ServiceException(ServiceErrorCode.TEAM_NOT_FOUND, "团队已解散，上传已取消");
        }
        return teamId;
    }

    /**
     * 构建 composeObject 的 Source 列表。
     */
    private List<SourceObject> buildSources(String bucket, String basePath, int totalChunks) {
        List<SourceObject> sources = new ArrayList<>(totalChunks);
        for (int i = 0; i < totalChunks; i++) {
            sources.add(SourceObject.builder()
                    .bucket(bucket)
                    .object(UploadObjectKeys.chunkObjectKey(basePath, i))
                    .build());
        }
        return sources;
    }

    /**
     * 校验合并后对象：SHA-256 校验和 + 服务端魔数 MIME 检测（R2-H1）。
     * <p>
     * 校验失败时删除目标对象与临时分片，清除 __merging 标记（允许 complete 重试），
     * 并抛出对应业务异常。
     *
     * @return 服务端魔数探测到的真实 MIME（探测失败为 null，由声明类型回退）
     */
    private @Nullable String validateMergedObject(String uploadId, Map<String, String> session,
                                      String bucket, String basePath, String targetObjectKey,
                                      String declaredChecksum, String declaredMimeType, String originalName) {
        String actualChecksum = minioGateway.computeFileChecksum(bucket, targetObjectKey);
        if (!actualChecksum.equalsIgnoreCase(declaredChecksum)) {
            log.warn("File checksum mismatch: uploadId={}, expected={}, actual={}", uploadId, declaredChecksum, actualChecksum);
            discardMergedAndChunks(uploadId, bucket, basePath, targetObjectKey,
                    ChunkSessionStore.parseSessionInt(session, ChunkSessionStore.FIELD_TOTAL_CHUNKS));
            throw new ClientException(ClientErrorCode.UPLOAD_FILE_CHECKSUM_MISMATCH, "文件校验失败");
        }

        // R2-H1: 对合并后的对象做服务端魔数校验，用检测到的真实 MIME 路由解析器与落库，
        // 而非 session 中客户端声明的 MIME（防止声明与实际内容不符的 confused-deputy）。
        String detectedMimeType = minioGateway.detectObjectMimeType(bucket, targetObjectKey, originalName);
        if (!documentValidator.isDetectedMimeTypeAcceptable(detectedMimeType, declaredMimeType)) {
            log.warn("MIME bypass rejected: uploadId={}, declared={}, detected={}",
                    uploadId, declaredMimeType, detectedMimeType);
            discardMergedAndChunks(uploadId, bucket, basePath, targetObjectKey,
                    ChunkSessionStore.parseSessionInt(session, ChunkSessionStore.FIELD_TOTAL_CHUNKS));
            throw new ClientException(ClientErrorCode.UPLOAD_MIME_UNSUPPORTED,
                    String.format("文件实际类型(%s)与声明类型(%s)不匹配", detectedMimeType, declaredMimeType));
        }
        return detectedMimeType;
    }

    /** 校验失败后的资源回收：删除合并对象 + 临时分片，清除 __merging 标记 */
    private void discardMergedAndChunks(String uploadId, String bucket, String basePath,
                                        String targetObjectKey, int totalChunks) {
        minioGateway.deleteObjectBestEffort(bucket, targetObjectKey);
        minioGateway.cleanupTempChunks(bucket, basePath, totalChunks);
        sessionStore.clearMergingFlag(uploadId);
    }

    /**
     * 合并成功后的收尾：落库 + BloomFilter 登记 + 清理 + ETL 派发 + 事件发布。
     *
     * @return 新文档 docId
     */
    private Long finalizeMerge(String uploadId, Map<String, String> session,
                               String bucket, String basePath, int totalChunks,
                               String targetObjectKey, String actualChecksum,
                               String declaredMimeType, @Nullable String detectedMimeType, @Nullable Long teamId) {
        String effectiveMimeType = resolveEffectiveMimeType(detectedMimeType, declaredMimeType);

        Long userId = ChunkSessionStore.parseSessionLong(session, ChunkSessionStore.FIELD_USER_ID);
        Long docId = persistDocument(session, targetObjectKey, actualChecksum, userId, teamId, effectiveMimeType);
        log.info("Chunk upload merged: uploadId={}, docId={}, checksum={}, mime={} (declared={})",
                uploadId, docId, actualChecksum, effectiveMimeType, declaredMimeType);

        // 将文件校验和加入 BloomFilter 去重
        if (documentDedupService != null && actualChecksum != null) {
            documentDedupService.add(actualChecksum);
        }

        // 清理临时分片与 Redis
        minioGateway.cleanupTempChunks(bucket, basePath, totalChunks);
        sessionStore.cleanup(uploadId, session.get(ChunkSessionStore.FIELD_USER_ID),
                session.get(ChunkSessionStore.FIELD_FILE_CHECKSUM));

        // 触发 ETL（使用检测到的真实 MIME 路由解析器）
        etlDispatchService.dispatchAsync(
                docId, bucket, targetObjectKey, session.get(ChunkSessionStore.FIELD_FILE_NAME),
                effectiveMimeType, ChunkSessionStore.parseSessionLong(session, ChunkSessionStore.FIELD_FILE_SIZE),
                userId, teamId
        );

        // 发布 DocumentCreatedEvent（用于增量更新版本链接）
        Long replaceDocumentId = ChunkSessionStore.parseNullableLong(
                session.get(ChunkSessionStore.FIELD_REPLACE_DOCUMENT_ID), ChunkSessionStore.FIELD_REPLACE_DOCUMENT_ID);
        eventPublisher.publishEvent(new DocumentCreatedEvent(docId, replaceDocumentId, userId, teamId));

        return docId;
    }

    /**
     * R2-H1: 解析最终生效的 MIME。
     * <p>
     * 优先使用检测到的具体类型（PDF/text/office 子类型）；
     * 若检测仅得出容器类型 application/zip 而声明为 OOXML 子类型，则信任声明类型
     * （魔数无法区分 docx/pptx/xlsx，子类型由扩展名路由决定）；
     * 检测为 null 时回退声明类型（白名单校验在 isDetectedMimeTypeAcceptable 中已拦截）。
     */
    private String resolveEffectiveMimeType(@Nullable String detectedMimeType, String declaredMimeType) {
        if (detectedMimeType != null && !"application/zip".equals(detectedMimeType)) {
            return detectedMimeType;
        }
        return declaredMimeType;
    }

    private Long persistDocument(Map<String, String> session, String targetObjectKey, String actualChecksum,
                                 Long userId, @Nullable Long teamId, String effectiveMimeType) {
        RagDocument doc = new RagDocument();
        doc.setFileName(session.get(ChunkSessionStore.FIELD_FILE_NAME));
        doc.setFileSize(ChunkSessionStore.parseSessionLong(session, ChunkSessionStore.FIELD_FILE_SIZE));
        doc.setMimeType(effectiveMimeType);
        doc.setStorageKey(targetObjectKey);
        doc.setBucket(session.get(ChunkSessionStore.FIELD_BUCKET));
        doc.setUserId(userId);
        doc.setTeamId(teamId);
        doc.setFileChecksum(actualChecksum);
        doc.setStatus(EtlStatus.PROCESSING);
        doc.setDeleted(0);
        OffsetDateTime now = OffsetDateTime.now();
        doc.setCreateTime(now);
        doc.setUpdateTime(now);
        ragDocumentMapper.insert(doc);
        return doc.getId();
    }
}
