package com.smart.rag.team.upload;

import com.smart.rag.common.util.ChecksumUtils;
import com.smart.rag.infrastructure.exception.ClientException;
import com.smart.rag.infrastructure.exception.errorcode.ClientErrorCode;
import com.smart.rag.rag.upload.UploadStrategy;
import com.smart.rag.rag.dto.DocumentUploadResponse;
import com.smart.rag.rag.etl.EtlStatus;
import com.smart.rag.rag.upload.BucketResolver;
import com.smart.rag.rag.upload.UploadDocumentPersistence;
import com.smart.rag.rag.entity.RagDocument;
import com.smart.rag.rag.mapper.RagDocumentMapper;
import com.smart.rag.rag.service.FileStorageService;
import com.smart.rag.rag.service.impl.DocumentValidator;
import com.smart.rag.rag.service.impl.ValidatedDocumentFile;
import com.smart.rag.team.entity.TeamMember;
import com.smart.rag.team.entity.TeamUploadApproval;
import com.smart.rag.team.enums.ApprovalStatus;
import com.smart.rag.team.enums.TeamMemberRole;
import com.smart.rag.team.mapper.TeamMemberMapper;
import com.smart.rag.team.mapper.TeamUploadApprovalMapper;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 团队上传策略
 * <p>
 * 处理团队空间的文件上传：
 * <ul>
 *   <li>写入 rag_document 时设置 teamId</li>
 *   <li>管理员/创建者上传自动通过（PROCESSING），普通成员需审批（PENDING_APPROVAL）</li>
 *   <li>普通成员上传时创建审批记录</li>
 *   <li>校验成员上传额度（UPLOAD_QUOTA_EXCEEDED）</li>
 *   <li>落库/事件/ETL 经 {@link UploadDocumentPersistence} 共享组件（与直传 commit 同源）</li>
 * </ul>
 */
@Component
public class TeamUploadStrategy implements UploadStrategy {

    private static final Logger log = LoggerFactory.getLogger(TeamUploadStrategy.class);

    private final DocumentValidator documentValidator;
    private final FileStorageService fileStorageService;
    private final BucketResolver bucketResolver;
    private final RagDocumentMapper ragDocumentMapper;
    private final TeamMemberMapper teamMemberMapper;
    private final TeamUploadApprovalMapper approvalMapper;
    private final UploadDocumentPersistence persistence;

    public TeamUploadStrategy(DocumentValidator documentValidator,
                              FileStorageService fileStorageService,
                              BucketResolver bucketResolver,
                              RagDocumentMapper ragDocumentMapper,
                              TeamMemberMapper teamMemberMapper,
                              TeamUploadApprovalMapper approvalMapper,
                              UploadDocumentPersistence persistence) {
        this.documentValidator = documentValidator;
        this.fileStorageService = fileStorageService;
        this.bucketResolver = bucketResolver;
        this.ragDocumentMapper = ragDocumentMapper;
        this.teamMemberMapper = teamMemberMapper;
        this.approvalMapper = approvalMapper;
        this.persistence = persistence;
    }

    @Override
    public boolean supports(@Nullable Long teamId) {
        return teamId != null;
    }

    @Override
    public DocumentUploadResponse upload(MultipartFile file, @Nullable Long teamId, @Nullable Long replaceDocumentId, Long userId) {
        ValidatedDocumentFile validated = documentValidator.validate(file);
        String mimeType = validated.canonicalMimeType();

        // 校验成员上传额度
        verifyUploadQuota(teamId, userId, validated.fileSize());

        String bucket = bucketResolver.resolve(teamId);
        fileStorageService.ensureBucketExists(bucket);
        String storageKey = UUID.randomUUID().toString();
        fileStorageService.upload(bucket, storageKey, file.getResource(), mimeType);

        boolean autoApproved = isAutoApproved(teamId, userId);
        String fileChecksum = computeChecksum(file);
        RagDocument ragDoc = persistence.insert(new UploadDocumentPersistence.Insert(
                validated.fileName(), validated.fileSize(), mimeType, storageKey, bucket, userId, teamId,
                fileChecksum, autoApproved ? EtlStatus.PROCESSING : EtlStatus.PENDING_APPROVAL));
        persistence.publishCreated(ragDoc.getId(), replaceDocumentId, userId, teamId);

        if (!autoApproved) {
            createApprovalRecord(teamId, ragDoc.getId(), userId);
        }

        // 管理员/创建者直接触发 ETL，普通成员等审批
        if (ragDoc.getStatus() == EtlStatus.PROCESSING) {
            persistence.dispatchEtl(ragDoc.getId(), bucket, storageKey,
                    validated.fileName(), mimeType, validated.fileSize(), userId, teamId);
        }

        log.info("Team document uploaded: id={}, file={}, teamId={}, userId={}, status={}",
                ragDoc.getId(), validated.fileName(), teamId, userId, ragDoc.getStatus());
        return new DocumentUploadResponse(ragDoc.getId(), validated.fileName(), ragDoc.getStatus());
    }

    @Override
    public List<DocumentUploadResponse> uploadBatch(List<MultipartFile> files, @Nullable Long teamId, @Nullable Long replaceDocumentId, Long userId) {
        String bucket = bucketResolver.resolve(teamId);
        fileStorageService.ensureBucketExists(bucket);

        boolean autoApproved = isAutoApproved(teamId, userId);

        // 批量校验额度
        long totalSize = files.stream().mapToLong(MultipartFile::getSize).sum();
        verifyUploadQuota(teamId, userId, totalSize);

        List<DocumentUploadResponse> responses = new ArrayList<>();
        List<RagDocument> autoApprovedDocs = new ArrayList<>();

        for (MultipartFile file : files) {
            ValidatedDocumentFile validated = documentValidator.validate(file);
            String mimeType = validated.canonicalMimeType();
            String storageKey = UUID.randomUUID().toString();
            fileStorageService.upload(bucket, storageKey, file.getResource(), mimeType);

            RagDocument ragDoc = persistence.insert(new UploadDocumentPersistence.Insert(
                    validated.fileName(), validated.fileSize(), mimeType, storageKey, bucket, userId, teamId,
                    computeChecksum(file), autoApproved ? EtlStatus.PROCESSING : EtlStatus.PENDING_APPROVAL));
            persistence.publishCreated(ragDoc.getId(), null, userId, teamId);

            if (!autoApproved) {
                createApprovalRecord(teamId, ragDoc.getId(), userId);
            }

            if (ragDoc.getStatus() == EtlStatus.PROCESSING) {
                autoApprovedDocs.add(ragDoc);
            }
            responses.add(new DocumentUploadResponse(ragDoc.getId(), file.getOriginalFilename(), ragDoc.getStatus()));
        }

        for (RagDocument doc : autoApprovedDocs) {
            persistence.dispatchEtl(doc.getId(), doc.getBucket(), doc.getStorageKey(),
                    doc.getFileName(), doc.getMimeType(), doc.getFileSize(), userId, teamId);
        }

        return responses;
    }

    // === 私有方法 ===

    /**
     * 判断是否自动通过（CREATOR / ADMIN）
     */
    private boolean isAutoApproved(Long teamId, Long userId) {
        TeamMember member = teamMemberMapper.selectByTeamAndUser(teamId, userId);
        return member != null && (member.getRole() == TeamMemberRole.CREATOR
                || member.getRole() == TeamMemberRole.ADMIN);
    }

    /**
     * 校验成员上传额度
     *
     * @param teamId    团队 ID
     * @param userId    用户 ID
     * @param extraSize 本次上传的额外大小
     */
    private void verifyUploadQuota(Long teamId, Long userId, long extraSize) {
        TeamMember member = teamMemberMapper.selectByTeamAndUser(teamId, userId);
        if (member == null) {
            return; // 非成员由 TeamMembershipVerifier 拦截，这里兜底
        }

        long limitBytes = member.getUploadLimitMb() * 1024 * 1024;
        long usedBytes = getUsedBytes(teamId, userId);

        if (usedBytes + extraSize > limitBytes) {
            log.warn("Upload quota exceeded: teamId={}, userId={}, used={}MB, limit={}MB, requested={}MB",
                    teamId, userId, usedBytes / 1024 / 1024, member.getUploadLimitMb(), extraSize / 1024 / 1024);
            throw new ClientException(ClientErrorCode.UPLOAD_QUOTA_EXCEEDED);
        }
    }

    /**
     * 查询成员已上传文档总大小（排除 REJECTED）
     */
    private long getUsedBytes(Long teamId, Long userId) {
        Long totalBytes = ragDocumentMapper.selectFileSizeSum(teamId, userId,
                EtlStatus.REJECTED.getCode());
        return totalBytes != null ? totalBytes : 0L;
    }

    /**
     * 创建审批记录
     */
    private void createApprovalRecord(Long teamId, Long documentId, Long uploaderId) {
        TeamUploadApproval approval = new TeamUploadApproval();
        approval.setTeamId(teamId);
        approval.setDocumentId(documentId);
        approval.setUploaderId(uploaderId);
        approval.setStatus(ApprovalStatus.PENDING);
        approval.setCreatedAt(OffsetDateTime.now());
        approvalMapper.insert(approval);
        log.info("Approval created: teamId={}, documentId={}, uploaderId={}", teamId, documentId, uploaderId);
    }

    /**
     * 计算 MultipartFile 的校验和（SHA-256，64 位 hex）
     * <p>
     * R1-L2: 失败时返回空串（保持现有行为，W5 将改为失败上传）。
     */
    private String computeChecksum(MultipartFile file) {
        try (InputStream is = file.getInputStream()) {
            return ChecksumUtils.sha256Hex(is);
        } catch (Exception e) {
            log.warn("Failed to compute file checksum: {}", e.getMessage());
            return "";
        }
    }
}
