package com.demo.chat.team.controller;

import com.demo.chat.common.request.PageRequest;
import com.demo.chat.common.response.GlobalResponse;
import com.demo.chat.common.response.PagedResult;
import com.demo.chat.team.dto.ApprovalReviewRequest;
import com.demo.chat.team.dto.ApprovalVO;
import com.demo.chat.team.dto.MyApprovalVO;
import com.demo.chat.team.service.TeamApprovalService;
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
