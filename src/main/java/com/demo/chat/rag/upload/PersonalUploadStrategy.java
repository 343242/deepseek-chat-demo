package com.demo.chat.rag.upload;

import com.demo.chat.common.errorcode.ErrorCode;
import com.demo.chat.common.upload.UploadStrategy;
import com.demo.chat.exception.BusinessException;
import com.demo.chat.rag.config.MinioProperties;
import com.demo.chat.rag.dto.DocumentUploadResponse;
import com.demo.chat.rag.etl.EtlCandidate;
import com.demo.chat.rag.etl.EtlStatus;
import com.demo.chat.rag.entity.RagDocument;
import com.demo.chat.rag.mapper.RagDocumentMapper;
import com.demo.chat.rag.service.EtlDispatchService;
import com.demo.chat.rag.service.FileStorageService;
import com.demo.chat.rag.service.impl.DocumentValidator;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

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
    private final MinioProperties minioProperties;
    private final DocumentValidator documentValidator;

    public PersonalUploadStrategy(FileStorageService fileStorageService,
                                   EtlDispatchService etlDispatchService,
                                   RagDocumentMapper ragDocumentMapper,
                                   MinioProperties minioProperties,
                                   DocumentValidator documentValidator) {
        this.fileStorageService = fileStorageService;
        this.etlDispatchService = etlDispatchService;
        this.ragDocumentMapper = ragDocumentMapper;
        this.minioProperties = minioProperties;
        this.documentValidator = documentValidator;
    }

    @Override
    public DocumentUploadResponse upload(MultipartFile file, @Nullable Long teamId, Long userId) {
        documentValidator.validate(file);

        String mimeType = file.getContentType();
        String originalFilename = file.getOriginalFilename();
        String bucket = minioProperties.getBucket();

        fileStorageService.ensureBucketExists(bucket);
        String storageKey = UUID.randomUUID().toString();
        fileStorageService.upload(bucket, storageKey, file.getResource(), mimeType);

        RagDocument ragDoc = persistDocument(originalFilename, file.getSize(), mimeType, storageKey, bucket, userId);
        log.info("Document uploaded: id={}, file={}, size={}, userId={}", ragDoc.getId(), originalFilename, file.getSize(), userId);

        etlDispatchService.dispatchAsync(ragDoc.getId(), bucket, storageKey, originalFilename, mimeType, file.getSize(), userId, ragDoc.getTeamId());

        return new DocumentUploadResponse(ragDoc.getId(), originalFilename, EtlStatus.PROCESSING);
    }

    @Override
    public List<DocumentUploadResponse> uploadBatch(List<MultipartFile> files, @Nullable Long teamId, Long userId) {
        if (files == null || files.isEmpty()) {
            throw new BusinessException(ErrorCode.UPLOAD_LIST_EMPTY);
        }

        String bucket = minioProperties.getBucket();
        fileStorageService.ensureBucketExists(bucket);

        for (MultipartFile file : files) {
            documentValidator.validate(file);
        }

        List<EtlCandidate> candidates = new ArrayList<>();
        List<DocumentUploadResponse> responses = new ArrayList<>();

        for (MultipartFile file : files) {
            String mimeType = file.getContentType();
            String originalFilename = file.getOriginalFilename();
            String storageKey = UUID.randomUUID().toString();

            fileStorageService.upload(bucket, storageKey, file.getResource(), mimeType);

            RagDocument ragDoc = persistDocument(originalFilename, file.getSize(), mimeType, storageKey, bucket, userId);
            log.debug("Document uploaded (batch): id={}, file={}, size={}, userId={}", ragDoc.getId(), originalFilename, file.getSize(), userId);

            candidates.add(new EtlCandidate(ragDoc.getId(), bucket, storageKey, originalFilename, mimeType, file.getSize(), userId, ragDoc.getTeamId()));
            responses.add(new DocumentUploadResponse(ragDoc.getId(), originalFilename, EtlStatus.PROCESSING));
        }

        etlDispatchService.dispatch(candidates);

        log.info("Batch upload completed: count={}, userId={}", responses.size(), userId);
        return responses;
    }

    // === 私有方法 ===

    /**
     * 持久化文档元数据到数据库
     */
    private RagDocument persistDocument(String fileName, long fileSize, String mimeType,
                                        String storageKey, String bucket, Long userId) {
        RagDocument ragDoc = new RagDocument();
        ragDoc.setFileName(fileName);
        ragDoc.setFileSize(fileSize);
        ragDoc.setMimeType(mimeType);
        ragDoc.setStorageKey(storageKey);
        ragDoc.setBucket(bucket);
        ragDoc.setUserId(userId);
        ragDoc.setStatus(EtlStatus.UPLOADED);
        ragDoc.setCreateTime(OffsetDateTime.now());
        ragDoc.setUpdateTime(OffsetDateTime.now());
        ragDocumentMapper.insert(ragDoc);
        return ragDoc;
    }
}
