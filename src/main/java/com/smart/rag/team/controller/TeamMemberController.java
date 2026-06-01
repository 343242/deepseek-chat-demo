package com.smart.rag.team.controller;

import com.smart.rag.infrastructure.request.PageRequest;
import com.smart.rag.infrastructure.response.GlobalResponse;
import com.smart.rag.infrastructure.response.PagedResult;
import com.smart.rag.team.dto.MemberRoleUpdateRequest;
import com.smart.rag.team.dto.MemberUploadLimitRequest;
import com.smart.rag.team.dto.TeamMemberVO;
import com.smart.rag.team.service.TeamMemberService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * 团队成员管理 REST 控制器
 */
@RestController
@RequestMapping("/api/teams/{teamId}/members")
@PreAuthorize("isAuthenticated()")
public class TeamMemberController {

    private final TeamMemberService teamMemberService;

    public TeamMemberController(TeamMemberService teamMemberService) {
        this.teamMemberService = teamMemberService;
    }

    @PostMapping("/{userId}")
    public GlobalResponse<TeamMemberVO> addMember(@PathVariable Long teamId,
                                                  @PathVariable Long userId) {
        return GlobalResponse.ok(teamMemberService.addMember(teamId, userId));
    }

    @PostMapping("/{userId}/delete")
    public GlobalResponse<Void> removeMember(@PathVariable Long teamId,
                                              @PathVariable Long userId) {
        teamMemberService.removeMember(teamId, userId);
        return GlobalResponse.ok(null);
    }

    @PostMapping("/leave")
    public GlobalResponse<Void> leaveTeam(@PathVariable Long teamId) {
        teamMemberService.leaveTeam(teamId);
        return GlobalResponse.ok(null);
    }

    @PostMapping("/{userId}/role")
    public GlobalResponse<Void> updateMemberRole(@PathVariable Long teamId,
                                                  @PathVariable Long userId,
                                                  @Valid @RequestBody MemberRoleUpdateRequest request) {
        teamMemberService.updateMemberRole(teamId, userId, request);
        return GlobalResponse.ok(null);
    }

    @PostMapping("/{userId}/upload-limit")
    public GlobalResponse<Void> setMemberUploadLimit(@PathVariable Long teamId,
                                                      @PathVariable Long userId,
                                                      @Valid @RequestBody MemberUploadLimitRequest request) {
        teamMemberService.setMemberUploadLimit(teamId, userId, request);
        return GlobalResponse.ok(null);
    }

    @GetMapping
    public GlobalResponse<PagedResult<TeamMemberVO>> listMembers(
            @PathVariable Long teamId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        PageRequest req = PageRequest.of(page, size);
        return GlobalResponse.ok(teamMemberService.listMembers(teamId, req));
    }
}
