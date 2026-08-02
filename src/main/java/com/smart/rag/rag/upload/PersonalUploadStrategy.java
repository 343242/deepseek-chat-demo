package com.smart.rag.rag.upload;

import com.smart.rag.infrastructure.exception.errorcode.ClientErrorCode;
import com.smart.rag.infrastructure.exception.ClientException;
import com.smart.rag.rag.event.DocumentCreatedEvent;
import com.smart.rag.rag.dto.DocumentUploadResponse;
import com.smart.rag.rag.etl.EtlCandidate;
import com.smart.rag.rag.etl.EtlStatus;
import com.smart.rag.rag.entity.RagDocument;
import com.smart.rag.rag.mapper.RagDocumentMapper;
import com.smart.rag.rag.service.EtlDispatchService;
import com.smart.rag.rag.service.FileStorageService;
import com.smart.rag.rag.service.DocumentDedupService;
import com.smart.rag.rag.service.impl.DocumentValidator;
import org.apache.commons.codec.digest.DigestUtils;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 个人文档上传策略 — 封装现有上传逻辑
 * <p>
 * 将原 {@code DocumentApplicationServiceImpl} 中 {@code upload()}/{@code uploadBatch()}
 * 的全部逻辑搬至此类，包括：
 * <ul>
 *   <li>文件校验（{@link DocumentValidator}）</li>
 *   <li>MinIO 存储（{@link FileStorageService}）</li>
 *   <li>数据库元数据写入（{@link RagDocumentMapper}）</li>
 *   <li>ETL 触发（{@link EtlDispatchService}）</li>
 * </ul>
 * <p>
 * teamId 参数传入但忽略——个人上传不关心团队。
 */
@Component
public class PersonalUploadStrategy implements UploadStrategy {

    private static final Logger log = LoggerFactory.getLogger(PersonalUploadStrategy.class);

    private final FileStorageService fileStorageService;
    private final EtlDispatchService etlDispatchService;
    private final RagDocumentMapper ragDocumentMapper;
    private final BucketResolver bucketResolver;
    private final DocumentValidator documentValidator;
    private final ApplicationEventPublisher eventPublisher;
    private final @Nullable DocumentDedupService documentDedupService;

    public PersonalUploadStrategy(FileStorageService fileStorageService,
                                   EtlDispatchService etlDispatchService,
                                   RagDocumentMapper ragDocumentMapper,
                                   BucketResolver bucketResolver,
                                   DocumentValidator documentValidator,
                                   ApplicationEventPublisher eventPublisher,
                                   @Nullable DocumentDedupService documentDedupService) {
        this.fileStorageService = fileStorageService;
        this.etlDispatchService = etlDispatchService;
        this.ragDocumentMapper = ragDocumentMapper;
        this.bucketResolver = bucketResolver;
        this.documentValidator = documentValidator;
        this.eventPublisher = eventPublisher;
        this.documentDedupService = documentDedupService;
    }

    @Override
    public boolean supports(@Nullable Long teamId) {
        return teamId == null;
    }

    @Override
    public DocumentUploadResponse upload(MultipartFile file, @Nullable Long teamId, @Nullable Long replaceDocumentId, Long userId) {
        documentValidator.validate(file);

        String mimeType = file.getContentType();
        String originalFilename = file.getOriginalFilename();
        String bucket = bucketResolver.resolve(null);

        fileStorageService.ensureBucketExists(bucket);
        String storageKey = buildStorageKey(userId, originalFilename);
        fileStorageService.upload(bucket, storageKey, file.getResource(), mimeType);

        String fileMd5 = computeMd5(file);
        RagDocument ragDoc = persistDocument(originalFilename, file.getSize(), mimeType, storageKey, bucket, userId, fileMd5);
        if (documentDedupService != null && ragDoc.getFileMd5() != null) {
            documentDedupService.add(ragDoc.getFileMd5());
        }
        eventPublisher.publishEvent(new DocumentCreatedEvent(ragDoc.getId(), replaceDocumentId, userId, ragDoc.getTeamId()));
        log.info("Document uploaded: id={}, file={}, size={}, userId={}", ragDoc.getId(), originalFilename, file.getSize(), userId);

        etlDispatchService.dispatchAsync(ragDoc.getId(), bucket, storageKey, originalFilename, mimeType, file.getSize(), userId, ragDoc.getTeamId());

        return new DocumentUploadResponse(ragDoc.getId(), originalFilename, EtlStatus.PROCESSING);
    }

    @Override
    public List<DocumentUploadResponse> uploadBatch(List<MultipartFile> files, @Nullable Long teamId, @Nullable Long replaceDocumentId, Long userId) {
        if (files == null || files.isEmpty()) {
            throw new ClientException(ClientErrorCode.UPLOAD_LIST_EMPTY);
        }

        String bucket = bucketResolver.resolve(null);
        fileStorageService.ensureBucketExists(bucket);

        for (MultipartFile file : files) {
            documentValidator.validate(file);
        }

        List<EtlCandidate> candidates = new ArrayList<>();
        List<DocumentUploadResponse> responses = new ArrayList<>();

        for (MultipartFile file : files) {
            String originalFilename = file.getOriginalFilename();
            String storageKey = buildStorageKey(userId, originalFilename);
            boolean uploaded = false;
            RagDocument ragDoc = null;
            try {
                String mimeType = file.getContentType();
                fileStorageService.upload(bucket, storageKey, file.getResource(), mimeType);
                uploaded = true;

                String fileMd5 = computeMd5(file);
                ragDoc = persistDocument(originalFilename, file.getSize(), mimeType, storageKey, bucket, userId, fileMd5);
                // persist 成功后立即登记 dispatch 候选 —— 即使后续 dedup/event 抛异常，
                // 该文档仍会被 dispatch，避免落入 UPLOADED 死状态。
                candidates.add(new EtlCandidate(ragDoc.getId(), bucket, storageKey, originalFilename, mimeType, file.getSize(), userId, ragDoc.getTeamId()));
                if (documentDedupService != null && ragDoc.getFileMd5() != null) {
                    documentDedupService.add(ragDoc.getFileMd5());
                }
                eventPublisher.publishEvent(new DocumentCreatedEvent(ragDoc.getId(), null, userId, ragDoc.getTeamId()));
                log.debug("Document uploaded (batch): id={}, file={}, size={}, userId={}", ragDoc.getId(), originalFilename, file.getSize(), userId);
                responses.add(new DocumentUploadResponse(ragDoc.getId(), originalFilename, EtlStatus.PROCESSING));
            } catch (Exception e) {
                // R1-H3: 单文件失败不中断整批。仅在"已上传但未成功 persist"时回滚 MinIO 对象，
                // 避免无 DB 记录的孤儿对象；已 persist 的（ragDoc != null）保留并照常 dispatch。
                log.error("Batch upload failed for file (continuing batch): file={}, userId={}", originalFilename, userId, e);
                if (uploaded && ragDoc == null) {
                    rollbackMinioObject(bucket, storageKey, originalFilename);
                }
                responses.add(new DocumentUploadResponse(ragDoc != null ? ragDoc.getId() : null, originalFilename, EtlStatus.FAILED));
            }
        }

        // 所有成功 persist 的候选统一 dispatch，即使批次中后续文件失败也已覆盖，避免 UPLOADED 死状态
        if (!candidates.isEmpty()) {
            etlDispatchService.dispatch(candidates);
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

    private static final char[] NANOID_CHARS =
            "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ".toCharArray();
    private static final java.util.Random RANDOM = new java.security.SecureRandom();

    /**
     * 构建存储路径：documents/{userId}/{shortId}_{originalFilename}
     * shortId 为 8 位随机字母数字，用于避免同名文件冲突。
     */
    private String buildStorageKey(Long userId, String originalFilename) {
        String shortId = generateShortId(8);
        String safeName = sanitizeFilename(originalFilename);
        return "documents/" + userId + "/" + shortId + "_" + safeName;
    }

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
        // 保留原始文件名（含扩展名），仅去除路径分隔符等危险字符
        return fileName.replace("/", "_").replace("\\", "_").replace("\0", "_");
    }

    /**
     * 持久化文档元数据到数据库
     */
    private RagDocument persistDocument(String fileName, long fileSize, String mimeType,
                                        String storageKey, String bucket, Long userId, String fileMd5) {
        RagDocument ragDoc = new RagDocument();
        ragDoc.setFileName(fileName);
        ragDoc.setFileSize(fileSize);
        ragDoc.setMimeType(mimeType);
        ragDoc.setStorageKey(storageKey);
        ragDoc.setBucket(bucket);
        ragDoc.setUserId(userId);
        ragDoc.setFileMd5(fileMd5);
        ragDoc.setStatus(EtlStatus.UPLOADED);
        ragDoc.setDeleted(0);
        ragDoc.setCreateTime(OffsetDateTime.now());
        ragDoc.setUpdateTime(OffsetDateTime.now());
        ragDocumentMapper.insert(ragDoc);
        return ragDoc;
    }

    /**
     * 计算 MultipartFile 的 MD5（hex 32 位）
     * <p>
     * R1-L2: 失败时返回 null（保持现有行为，秒传去重对该文件降级为不可用），
     * 但以 ERROR 级别记录，避免静默降级。U1 使用 commons-codec {@link DigestUtils#md5Hex(InputStream)}。
     */
    private String computeMd5(MultipartFile file) {
        try (InputStream is = file.getInputStream()) {
            return DigestUtils.md5Hex(is);
        } catch (Exception e) {
            log.error("Failed to compute file MD5 (file loses quick-upload dedup): {}", e.getMessage());
            return null;
        }
    }
}
