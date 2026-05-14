package com.demo.chat.team.upload;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.demo.chat.common.errorcode.ErrorCode;
import com.demo.chat.common.upload.UploadStrategy;
import com.demo.chat.exception.BusinessException;
import com.demo.chat.rag.dto.DocumentUploadResponse;
import com.demo.chat.rag.etl.EtlStatus;
import com.demo.chat.rag.upload.BucketResolver;
import com.demo.chat.rag.entity.RagDocument;
import com.demo.chat.rag.mapper.RagDocumentMapper;
import com.demo.chat.rag.service.FileStorageService;
import com.demo.chat.rag.service.EtlDispatchService;
import com.demo.chat.rag.service.impl.DocumentValidator;
import com.demo.chat.team.entity.TeamMember;
import com.demo.chat.team.entity.TeamUploadApproval;
import com.demo.chat.team.enums.ApprovalStatus;
import com.demo.chat.team.enums.TeamMemberRole;
import com.demo.chat.team.mapper.TeamMemberMapper;
import com.demo.chat.team.mapper.TeamUploadApprovalMapper;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.security.MessageDigest;
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
 * </ul>
 */
@Component
public class TeamUploadStrategy implements UploadStrategy {

    private static final Logger log = LoggerFactory.getLogger(TeamUploadStrategy.class);

    private final DocumentValidator documentValidator;
    private final FileStorageService fileStorageService;
    private final BucketResolver bucketResolver;
    private final RagDocumentMapper ragDocumentMapper;
    private final EtlDispatchService etlDispatchService;
    private final TeamMemberMapper teamMemberMapper;
    private final TeamUploadApprovalMapper approvalMapper;

    public TeamUploadStrategy(DocumentValidator documentValidator,
                              FileStorageService fileStorageService,
                              BucketResolver bucketResolver,
                              RagDocumentMapper ragDocumentMapper,
                              EtlDispatchService etlDispatchService,
                              TeamMemberMapper teamMemberMapper,
                              TeamUploadApprovalMapper approvalMapper) {
        this.documentValidator = documentValidator;
        this.fileStorageService = fileStorageService;
        this.bucketResolver = bucketResolver;
        this.ragDocumentMapper = ragDocumentMapper;
        this.etlDispatchService = etlDispatchService;
        this.teamMemberMapper = teamMemberMapper;
        this.approvalMapper = approvalMapper;
    }

    @Override
    public DocumentUploadResponse upload(MultipartFile file, @Nullable Long teamId, Long userId) {
        documentValidator.validate(file);

        // 校验成员上传额度
        verifyUploadQuota(teamId, userId, file.getSize());

        String bucket = bucketResolver.resolve(teamId);
        fileStorageService.ensureBucketExists(bucket);
        String storageKey = UUID.randomUUID().toString();
        fileStorageService.upload(bucket, storageKey, file.getResource(), file.getContentType());

        boolean autoApproved = isAutoApproved(teamId, userId);
        String fileMd5 = computeMd5(file);
        RagDocument ragDoc = persistDocument(file.getOriginalFilename(), file.getSize(),
                file.getContentType(), storageKey, bucket, userId, teamId, autoApproved, fileMd5);

        if (!autoApproved) {
            createApprovalRecord(teamId, ragDoc.getId(), userId);
        }

        // 管理员/创建者直接触发 ETL，普通成员等审批
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
        String bucket = bucketResolver.resolve(teamId);
        fileStorageService.ensureBucketExists(bucket);

        boolean autoApproved = isAutoApproved(teamId, userId);

        // 批量校验额度
        long totalSize = files.stream().mapToLong(MultipartFile::getSize).sum();
        verifyUploadQuota(teamId, userId, totalSize);

        List<DocumentUploadResponse> responses = new ArrayList<>();
        List<RagDocument> autoApprovedDocs = new ArrayList<>();

        for (MultipartFile file : files) {
            documentValidator.validate(file);
            String storageKey = UUID.randomUUID().toString();
            fileStorageService.upload(bucket, storageKey, file.getResource(), file.getContentType());

            RagDocument ragDoc = persistDocument(file.getOriginalFilename(), file.getSize(),
                    file.getContentType(), storageKey, bucket, userId, teamId, autoApproved, computeMd5(file));

            if (!autoApproved) {
                createApprovalRecord(teamId, ragDoc.getId(), userId);
            }

            if (ragDoc.getStatus() == EtlStatus.PROCESSING) {
                autoApprovedDocs.add(ragDoc);
            }
            responses.add(new DocumentUploadResponse(ragDoc.getId(), file.getOriginalFilename(), ragDoc.getStatus()));
        }

        for (RagDocument doc : autoApprovedDocs) {
            etlDispatchService.dispatchAsync(doc.getId(), doc.getBucket(), doc.getStorageKey(),
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
            throw new BusinessException(ErrorCode.UPLOAD_QUOTA_EXCEEDED);
        }
    }

    /**
     * 查询成员已上传文档总大小（排除 REJECTED）
     */
    private long getUsedBytes(Long teamId, Long userId) {
        // 使用 MyBatis-Plus 聚合查询
        List<RagDocument> docs = ragDocumentMapper.selectList(
                new LambdaQueryWrapper<RagDocument>()
                        .eq(RagDocument::getTeamId, teamId)
                        .eq(RagDocument::getUserId, userId)
                        .ne(RagDocument::getStatus, EtlStatus.REJECTED));
        return docs.stream().mapToLong(RagDocument::getFileSize).sum();
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
     * 持久化文档记录
     */
    private RagDocument persistDocument(String fileName, long fileSize, String mimeType,
                                        String storageKey, String bucket, Long userId,
                                        Long teamId, boolean autoApproved, String fileMd5) {
        RagDocument doc = new RagDocument();
        doc.setFileName(fileName);
        doc.setFileSize(fileSize);
        doc.setMimeType(mimeType);
        doc.setStorageKey(storageKey);
        doc.setBucket(bucket);
        doc.setUserId(userId);
        doc.setTeamId(teamId);
        doc.setFileMd5(fileMd5);
        doc.setStatus(autoApproved ? EtlStatus.PROCESSING : EtlStatus.PENDING_APPROVAL);
        ragDocumentMapper.insert(doc);
        return doc;
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
