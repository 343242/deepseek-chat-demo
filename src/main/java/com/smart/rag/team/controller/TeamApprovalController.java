package com.smart.rag.team.controller;

import com.smart.rag.common.request.PageRequest;
import com.smart.rag.common.response.GlobalResponse;
import com.smart.rag.common.response.PagedResult;
import com.smart.rag.team.dto.ApprovalReviewRequest;
import com.smart.rag.team.dto.ApprovalVO;
import com.smart.rag.team.dto.MyApprovalVO;
import com.smart.rag.team.service.TeamApprovalService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * 团队审批 REST 控制器
 */
@RestController
@RequestMapping("/api/teams/{teamId}/approvals")
@PreAuthorize("isAuthenticated()")
public class TeamApprovalController {

    private final TeamApprovalService approvalService;

    public TeamApprovalController(TeamApprovalService approvalService) {
        this.approvalService = approvalService;
    }

    @GetMapping("/pending")
    public GlobalResponse<PagedResult<ApprovalVO>> listPending(
            @PathVariable Long teamId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        PageRequest req = PageRequest.of(page, size);
        return GlobalResponse.ok(approvalService.listPending(teamId, req));
    }

    @PostMapping("/{approvalId}/review")
    public GlobalResponse<Void> review(@PathVariable Long teamId,
                                        @PathVariable Long approvalId,
                                        @Valid @RequestBody ApprovalReviewRequest request) {
        approvalService.review(teamId, approvalId, request);
        return GlobalResponse.ok(null);
    }

    @GetMapping("/mine")
    public GlobalResponse<PagedResult<MyApprovalVO>> listMyApprovals(
            @PathVariable Long teamId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        PageRequest req = PageRequest.of(page, size);
        return GlobalResponse.ok(approvalService.listMyApprovals(teamId, req));
    }
}
