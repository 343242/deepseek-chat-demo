package com.demo.chat.team.controller;

import com.demo.chat.common.response.GlobalResponse;
import com.demo.chat.team.dto.ApprovalReviewRequest;
import com.demo.chat.team.dto.ApprovalVO;
import com.demo.chat.team.dto.MyApprovalVO;
import com.demo.chat.team.service.TeamApprovalService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

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
    public GlobalResponse<List<ApprovalVO>> listPending(@PathVariable Long teamId) {
        return GlobalResponse.ok(approvalService.listPending(teamId));
    }

    @PostMapping("/{approvalId}/review")
    public GlobalResponse<Void> review(@PathVariable Long teamId,
                                        @PathVariable Long approvalId,
                                        @Valid @RequestBody ApprovalReviewRequest request) {
        approvalService.review(teamId, approvalId, request);
        return GlobalResponse.ok(null);
    }

    @GetMapping("/mine")
    public GlobalResponse<List<MyApprovalVO>> listMyApprovals(@PathVariable Long teamId) {
        return GlobalResponse.ok(approvalService.listMyApprovals(teamId));
    }
}
