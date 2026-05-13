package com.demo.chat.team.upload;

import com.demo.chat.rag.dto.DocumentUploadResponse;
import com.demo.chat.rag.etl.EtlStatus;
import com.demo.chat.rag.config.MinioProperties;
import com.demo.chat.rag.entity.RagDocument;
import com.demo.chat.rag.mapper.RagDocumentMapper;
import com.demo.chat.rag.service.FileStorageService;
import com.demo.chat.rag.service.EtlDispatchService;
import com.demo.chat.rag.service.impl.DocumentValidator;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 团队上传策略
 * <p>
 * 处理团队空间的文件上传。与 {@link PersonalUploadStrategy} 的区别：
 * <ul>
 *   <li>写入 rag_document 时设置 teamId</li>
 *   <li>管理员/创建者上传自动通过（PROCESSING），普通成员设为 PENDING_APPROVAL</li>
 *   <li>TODO: Phase 3E — 普通成员上传创建审批记录</li>
 * </ul>
 */
@Component
public class TeamUploadStrategy implements UploadStrategy {

    private static final Logger log = LoggerFactory.getLogger(TeamUploadStrategy.class);

    private final DocumentValidator documentValidator;
    private final FileStorageService fileStorageService;
    private final MinioProperties minioProperties;
    private final RagDocumentMapper ragDocumentMapper;
    private final EtlDispatchService etlDispatchService;

    public TeamUploadStrategy(DocumentValidator documentValidator,
                              FileStorageService fileStorageService,
                              MinioProperties minioProperties,
                              RagDocumentMapper ragDocumentMapper,
                              EtlDispatchService etlDispatchService) {
        this.documentValidator = documentValidator;
        this.fileStorageService = fileStorageService;
        this.minioProperties = minioProperties;
        this.ragDocumentMapper = ragDocumentMapper;
        this.etlDispatchService = etlDispatchService;
    }

    @Override
    public DocumentUploadResponse upload(MultipartFile file, @Nullable Long teamId, Long userId) {
        documentValidator.validate(file);
        String bucket = minioProperties.getBucket();
        fileStorageService.ensureBucketExists(bucket);
        String storageKey = UUID.randomUUID().toString();
        fileStorageService.upload(bucket, storageKey, file.getResource(), file.getContentType());

        RagDocument ragDoc = persistDocument(file.getOriginalFilename(), file.getSize(),
                file.getContentType(), storageKey, bucket, userId, teamId);

        // 管理员/创建者直接触发 ETL，普通成员等审批（PENDING_APPROVAL 状态不触发）
        if (ragDoc.getStatus() == EtlStatus.PROCESSING) {
            etlDispatchService.dispatchAsync(ragDoc.getId(), bucket, storageKey,
                    file.getOriginalFilename(), file.getContentType(), file.getSize(), userId, teamId);
        }

        log.info("Team document uploaded: id={}, file={}, teamId={}, userId={}, status={}",
                ragDoc.getId(), file.getOriginalFilename(), teamId, userId, ragDoc.getStatus());
        return new DocumentUploadResponse(ragDoc.getId(), file.getOriginalFilename(), ragDoc.getStatus());
    }

    @Override
    public List<DocumentUploadResponse> uploadBatch(List<MultipartFile> files, @Nullable Long teamId, Long userId) {
        String bucket = minioProperties.getBucket();
        fileStorageService.ensureBucketExists(bucket);

        List<DocumentUploadResponse> responses = new ArrayList<>();
        List<RagDocument> autoApproved = new ArrayList<>();

        for (MultipartFile file : files) {
            documentValidator.validate(file);
            String storageKey = UUID.randomUUID().toString();
            fileStorageService.upload(bucket, storageKey, file.getResource(), file.getContentType());

            RagDocument ragDoc = persistDocument(file.getOriginalFilename(), file.getSize(),
                    file.getContentType(), storageKey, bucket, userId, teamId);

            if (ragDoc.getStatus() == EtlStatus.PROCESSING) {
                autoApproved.add(ragDoc);
            }
            responses.add(new DocumentUploadResponse(ragDoc.getId(), file.getOriginalFilename(), ragDoc.getStatus()));
        }

        // 批量触发 ETL（只有自动通过的）
        for (RagDocument doc : autoApproved) {
            etlDispatchService.dispatchAsync(doc.getId(), doc.getBucket(), doc.getStorageKey(),
                    doc.getFileName(), doc.getMimeType(), doc.getFileSize(), userId, teamId);
        }

        return responses;
    }

    /**
     * 持久化文档记录
     * <p>
     * 当前版本：直接设 PROCESSING（全部自动通过）。
     * Phase 3E 完成后根据成员角色判断是否需要审批。
     */
    private RagDocument persistDocument(String fileName, long fileSize, String mimeType,
                                        String storageKey, String bucket, Long userId, Long teamId) {
        RagDocument doc = new RagDocument();
        doc.setFileName(fileName);
        doc.setFileSize(fileSize);
        doc.setMimeType(mimeType);
        doc.setStorageKey(storageKey);
        doc.setBucket(bucket);
        doc.setUserId(userId);
        doc.setTeamId(teamId);
        doc.setStatus(EtlStatus.PROCESSING);
        ragDocumentMapper.insert(doc);
        return doc;
    }
}
