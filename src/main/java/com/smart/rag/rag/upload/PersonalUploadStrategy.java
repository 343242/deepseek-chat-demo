package com.smart.rag.rag.upload;

import com.smart.rag.infrastructure.exception.errorcode.ClientErrorCode;
import com.smart.rag.common.upload.UploadStrategy;
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
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.security.MessageDigest;
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
            String mimeType = file.getContentType();
            String originalFilename = file.getOriginalFilename();
            String storageKey = buildStorageKey(userId, originalFilename);

            fileStorageService.upload(bucket, storageKey, file.getResource(), mimeType);

            String fileMd5 = computeMd5(file);
            RagDocument ragDoc = persistDocument(originalFilename, file.getSize(), mimeType, storageKey, bucket, userId, fileMd5);
            if (documentDedupService != null && ragDoc.getFileMd5() != null) {
                documentDedupService.add(ragDoc.getFileMd5());
            }
            eventPublisher.publishEvent(new DocumentCreatedEvent(ragDoc.getId(), null, userId, ragDoc.getTeamId()));
            log.debug("Document uploaded (batch): id={}, file={}, size={}, userId={}", ragDoc.getId(), originalFilename, file.getSize(), userId);

            candidates.add(new EtlCandidate(ragDoc.getId(), bucket, storageKey, originalFilename, mimeType, file.getSize(), userId, ragDoc.getTeamId()));
            responses.add(new DocumentUploadResponse(ragDoc.getId(), originalFilename, EtlStatus.PROCESSING));
        }

        etlDispatchService.dispatch(candidates);

        log.info("Batch upload completed: count={}, userId={}", responses.size(), userId);
        return responses;
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
     */
    private String computeMd5(MultipartFile file) {
        try (InputStream is = file.getInputStream()) {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = is.read(buffer)) != -1) {
                md.update(buffer, 0, read);
            }
            byte[] digest = md.digest();
            StringBuilder sb = new StringBuilder(32);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            log.warn("Failed to compute file MD5: {}", e.getMessage());
            return null;
        }
    }
}
