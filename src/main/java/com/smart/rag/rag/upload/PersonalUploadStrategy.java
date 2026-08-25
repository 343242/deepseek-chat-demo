package com.smart.rag.rag.upload;

import com.smart.rag.common.util.ChecksumUtils;
import com.smart.rag.infrastructure.exception.errorcode.ClientErrorCode;
import com.smart.rag.infrastructure.exception.ClientException;
import com.smart.rag.rag.dto.DocumentUploadResponse;
import com.smart.rag.rag.entity.RagDocument;
import com.smart.rag.rag.etl.EtlCandidate;
import com.smart.rag.rag.etl.EtlStatus;
import com.smart.rag.rag.service.FileStorageService;
import com.smart.rag.rag.service.impl.DocumentValidator;
import com.smart.rag.rag.service.impl.ValidatedDocumentFile;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 个人文档上传策略 — 封装现有上传逻辑
 * <ul>
 *   <li>文件校验（{@link DocumentValidator}）</li>
 *   <li>MinIO 存储（{@link FileStorageService}）</li>
 *   <li>落库/事件/dedup/ETL 经 {@link UploadDocumentPersistence} 共享组件（与直传 commit 同源）</li>
 * </ul>
 * <p>
 * teamId 参数传入但忽略——个人上传不关心团队。
 */
@Component
public class PersonalUploadStrategy implements UploadStrategy {

    private static final Logger log = LoggerFactory.getLogger(PersonalUploadStrategy.class);

    private final FileStorageService fileStorageService;
    private final BucketResolver bucketResolver;
    private final DocumentValidator documentValidator;
    private final UploadDocumentPersistence persistence;

    public PersonalUploadStrategy(FileStorageService fileStorageService,
                                   BucketResolver bucketResolver,
                                   DocumentValidator documentValidator,
                                   UploadDocumentPersistence persistence) {
        this.fileStorageService = fileStorageService;
        this.bucketResolver = bucketResolver;
        this.documentValidator = documentValidator;
        this.persistence = persistence;
    }

    @Override
    public boolean supports(@Nullable Long teamId) {
        return teamId == null;
    }

    @Override
    public DocumentUploadResponse upload(MultipartFile file, @Nullable Long teamId, @Nullable Long replaceDocumentId, Long userId) {
        ValidatedDocumentFile validated = documentValidator.validate(file);
        String mimeType = validated.canonicalMimeType();
        String originalFilename = validated.fileName();
        String bucket = bucketResolver.resolve(null);

        fileStorageService.ensureBucketExists(bucket);
        String storageKey = buildStorageKey(userId, originalFilename);
        fileStorageService.upload(bucket, storageKey, file.getResource(), mimeType);

        String fileChecksum = computeChecksum(file);
        var ragDoc = persistence.insert(new UploadDocumentPersistence.Insert(
                originalFilename, validated.fileSize(), mimeType, storageKey, bucket, userId, null,
                fileChecksum, EtlStatus.UPLOADED));
        persistence.registerDedup(ragDoc.getFileChecksum());
        persistence.publishCreated(ragDoc.getId(), replaceDocumentId, userId, ragDoc.getTeamId());
        log.info("Document uploaded: id={}, file={}, size={}, userId={}", ragDoc.getId(), originalFilename, validated.fileSize(), userId);

        persistence.dispatchEtl(ragDoc.getId(), bucket, storageKey, originalFilename, mimeType, validated.fileSize(), userId, ragDoc.getTeamId());

        return new DocumentUploadResponse(ragDoc.getId(), originalFilename, EtlStatus.PROCESSING);
    }

    @Override
    public List<DocumentUploadResponse> uploadBatch(List<MultipartFile> files, @Nullable Long teamId, @Nullable Long replaceDocumentId, Long userId) {
        if (files == null || files.isEmpty()) {
            throw new ClientException(ClientErrorCode.UPLOAD_LIST_EMPTY);
        }

        String bucket = bucketResolver.resolve(null);
        fileStorageService.ensureBucketExists(bucket);

        // 预校验全部文件（任一失败在任何上传发生前拒绝），结果复用到上传循环，避免二次全量校验
        List<ValidatedDocumentFile> validatedFiles = new ArrayList<>(files.size());
        for (MultipartFile file : files) {
            validatedFiles.add(documentValidator.validate(file));
        }

        List<EtlCandidate> candidates = new ArrayList<>();
        List<DocumentUploadResponse> responses = new ArrayList<>();

        for (int i = 0; i < files.size(); i++) {
            MultipartFile file = files.get(i);
            ValidatedDocumentFile validated = validatedFiles.get(i);
            String mimeType = validated.canonicalMimeType();
            String originalFilename = validated.fileName();
            String storageKey = buildStorageKey(userId, originalFilename);
            boolean uploaded = false;
            RagDocument ragDoc = null;
            try {
                fileStorageService.upload(bucket, storageKey, file.getResource(), mimeType);
                uploaded = true;

                String fileChecksum = computeChecksum(file);
                ragDoc = persistence.insert(new UploadDocumentPersistence.Insert(
                        originalFilename, validated.fileSize(), mimeType, storageKey, bucket, userId, null,
                        fileChecksum, EtlStatus.UPLOADED));
                // persist 成功后立即登记 dispatch 候选 —— 即使后续 dedup/event 抛异常，
                // 该文档仍会被 dispatch，避免落入 UPLOADED 死状态。
                candidates.add(new EtlCandidate(ragDoc.getId(), bucket, storageKey, originalFilename, mimeType, validated.fileSize(), userId, ragDoc.getTeamId()));
                persistence.registerDedup(ragDoc.getFileChecksum());
                persistence.publishCreated(ragDoc.getId(), null, userId, ragDoc.getTeamId());
                log.debug("Document uploaded (batch): id={}, file={}, size={}, userId={}", ragDoc.getId(), originalFilename, file.getSize(), userId);
                responses.add(new DocumentUploadResponse(ragDoc.getId(), originalFilename, EtlStatus.PROCESSING));
            } catch (RuntimeException e) {
                // R1-H3: 单文件失败不中断整批。业务异常（AbstractException 子类）按原语义继续批处理，
                // 其余运行时异常同样降级为单文件失败；受检异常不在此吞掉。
                // 仅在"已上传但未成功 persist"时回滚 MinIO 对象，避免无 DB 记录的孤儿对象；
                // 已 persist 的（ragDoc != null）保留并照常 dispatch。
                log.error("Batch upload failed for file (continuing batch): file={}, userId={}", originalFilename, userId, e);
                if (uploaded && ragDoc == null) {
                    rollbackMinioObject(bucket, storageKey, originalFilename);
                }
                responses.add(new DocumentUploadResponse(ragDoc != null ? ragDoc.getId() : null, originalFilename, EtlStatus.FAILED));
            }
        }

        // 所有成功 persist 的候选统一投递（即使批次中后续文件失败也已覆盖，避免 UPLOADED 死状态）。
        // 逐文件 dispatchAsync：经 outbox → Redis Stream 异步消费，HTTP 请求落库后立即返回，
        // 不再阻塞 ETL；单文档失败进重试/DLQ，与整批其余文档错误隔离（对齐 TeamUploadStrategy）。
        for (EtlCandidate c : candidates) {
            persistence.dispatchEtl(c.documentId(), c.bucket(), c.objectKey(),
                    c.fileName(), c.mimeType(), c.fileSize(), c.userId(), c.teamId());
        }

        log.info("Batch upload completed: succeeded={}, total={}, userId={}", candidates.size(), responses.size(), userId);
        return responses;
    }

    /**
     * 尽力删除已上传但未能 persist 的 MinIO 对象（R1-H3 回滚）。
     * 删除失败仅记录日志，不影响主流程。
     */
    private void rollbackMinioObject(String bucket, String storageKey, String originalFilename) {
        try {
            fileStorageService.delete(bucket, storageKey);
            log.debug("Rolled back MinIO object after batch failure: file={}", originalFilename);
        } catch (Exception ex) {
            log.warn("Failed to roll back MinIO object after batch failure: bucket={}, key={}, file={}",
                    bucket, storageKey, originalFilename);
        }
    }

    // === 私有方法 ===

    /**
     * 构建存储路径：documents/{userId}/{shortId}_{originalFilename}
     * shortId 为 8 位随机字母数字，用于避免同名文件冲突。
     *
     * @see StorageKeys#documentObjectKey(Long, String)
     */
    private String buildStorageKey(Long userId, String originalFilename) {
        return StorageKeys.documentObjectKey(userId, originalFilename);
    }

    /**
     * 计算 MultipartFile 的校验和（SHA-256，64 位 hex）
     * <p>
     * R1-L2: 失败时返回 null（保持现有行为，秒传去重对该文件降级为不可用），
     * 但以 ERROR 级别记录，避免静默降级。
     */
    private String computeChecksum(MultipartFile file) {
        try (InputStream is = file.getInputStream()) {
            return ChecksumUtils.sha256Hex(is);
        } catch (Exception e) {
            log.error("Failed to compute file checksum (file loses quick-upload dedup): {}", e.getMessage());
            return null;
        }
    }
}
