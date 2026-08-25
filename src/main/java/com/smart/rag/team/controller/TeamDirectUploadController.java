package com.smart.rag.team.controller;

import com.smart.rag.infrastructure.exception.ClientException;
import com.smart.rag.infrastructure.exception.ServiceException;
import com.smart.rag.infrastructure.exception.errorcode.ClientErrorCode;
import com.smart.rag.infrastructure.exception.errorcode.ServiceErrorCode;
import com.smart.rag.infrastructure.response.GlobalResponse;
import com.smart.rag.infrastructure.web.util.SecurityUtils;
import com.smart.rag.rag.dto.DocumentUploadResponse;
import com.smart.rag.rag.upload.DirectUploadCommitRequest;
import com.smart.rag.rag.upload.DirectUploadInitRequest;
import com.smart.rag.rag.upload.DirectUploadInitResult;
import com.smart.rag.rag.upload.DirectUploadPartUrlsRequest;
import com.smart.rag.rag.upload.DirectUploadPartUrlsResult;
import com.smart.rag.rag.upload.DirectUploadService;
import com.smart.rag.rag.upload.DirectUploadStatusResponse;
import com.smart.rag.team.service.TeamStatusService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 团队 Presigned 直传 REST 控制器。
 * <p>
 * 路径与个人直传（{@code /api/documents/direct-uploads}）平行：
 * {@code /api/teams/{teamId}/documents/direct-uploads}。teamId 由 URL 路径提供，
 * 注入 init 请求并作为会话端点的团队一致性校验值（对齐 TeamChunkUploadController 惯例）。
 * 链路与个人端共用同一 {@link DirectUploadService}（teamId 仅是会话字段，非链路分裂）。
 */
@RestController
@RequestMapping("/api/teams/{teamId}/documents/direct-uploads")
@PreAuthorize("isAuthenticated()")
public class TeamDirectUploadController {

    private final DirectUploadService directUploadService;
    private final TeamStatusService teamStatusService;

    public TeamDirectUploadController(DirectUploadService directUploadService,
                                      TeamStatusService teamStatusService) {
        this.directUploadService = directUploadService;
        this.teamStatusService = teamStatusService;
    }

    /** 校验团队存在且当前用户是活跃成员（对齐 TeamChunkUploadController.verifyTeamAccess）。 */
    private void verifyTeamAccess(Long teamId) {
        if (!teamStatusService.isTeamActive(teamId)) {
            throw new ServiceException(ServiceErrorCode.TEAM_NOT_FOUND);
        }
        Long userId = SecurityUtils.getCurrentUserId();
        if (!teamStatusService.isTeamMember(teamId, userId)) {
            throw new ServiceException(ServiceErrorCode.NOT_TEAM_MEMBER);
        }
    }

    /** init：teamId 由路径注入请求。 */
    @PostMapping
    public ResponseEntity<GlobalResponse<DirectUploadInitResult>> init(
            @PathVariable Long teamId,
            @Valid @RequestBody DirectUploadInitRequest request) {
        verifyTeamAccess(teamId);
        DirectUploadInitResult result = directUploadService.init(new DirectUploadInitRequest(
                request.fileName(), request.fileSize(), request.mimeType(), request.fileChecksum(),
                teamId, request.replaceDocumentId()));
        if (DirectUploadInitResult.MODE_INSTANT.equals(result.mode())) {
            return ResponseEntity.ok(GlobalResponse.ok(result, "秒传成功"));
        }
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(GlobalResponse.ok(result, "直传会话已创建"));
    }

    @PostMapping("/{sessionId}/part-urls")
    public GlobalResponse<DirectUploadPartUrlsResult> partUrls(
            @PathVariable Long teamId,
            @PathVariable String sessionId,
            @Valid @RequestBody DirectUploadPartUrlsRequest request) {
        return GlobalResponse.ok(directUploadService.partUrls(sessionId, request, teamId));
    }

    @GetMapping("/{sessionId}")
    public GlobalResponse<DirectUploadStatusResponse> status(
            @PathVariable Long teamId,
            @PathVariable String sessionId) {
        return GlobalResponse.ok(directUploadService.status(sessionId, teamId));
    }

    @PostMapping("/{sessionId}/commit")
    public GlobalResponse<DocumentUploadResponse> commit(
            @PathVariable Long teamId,
            @PathVariable String sessionId,
            @Valid @RequestBody DirectUploadCommitRequest request) {
        return GlobalResponse.ok(directUploadService.commit(sessionId, request, teamId), "上传完成");
    }

    @PostMapping("/{sessionId}/abort")
    public ResponseEntity<GlobalResponse<Void>> abort(
            @PathVariable Long teamId,
            @PathVariable String sessionId) {
        directUploadService.abort(sessionId, teamId);
        return ResponseEntity.status(HttpStatus.NO_CONTENT)
                .body(GlobalResponse.ok(null, "已取消"));
    }
}
