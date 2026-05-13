package com.demo.chat.team.controller;

import com.demo.chat.common.response.GlobalResponse;
import com.demo.chat.team.dto.*;
import com.demo.chat.team.service.TeamService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 团队管理 REST 控制器
 */
@RestController
@RequestMapping("/api/teams")
@PreAuthorize("isAuthenticated()")
public class TeamController {

    private final TeamService teamService;

    public TeamController(TeamService teamService) {
        this.teamService = teamService;
    }

    @PostMapping
    public GlobalResponse<TeamVO> createTeam(@Valid @RequestBody TeamCreateRequest request) {
        return GlobalResponse.ok(teamService.createTeam(request));
    }

    @GetMapping("/{teamId}")
    public GlobalResponse<TeamDetailVO> getTeamDetail(@PathVariable Long teamId) {
        return GlobalResponse.ok(teamService.getTeamDetail(teamId));
    }

    @GetMapping
    public GlobalResponse<List<TeamVO>> listMyTeams() {
        return GlobalResponse.ok(teamService.listMyTeams());
    }

    @PutMapping("/{teamId}")
    public GlobalResponse<TeamVO> updateTeam(@PathVariable Long teamId,
                                             @Valid @RequestBody TeamUpdateRequest request) {
        return GlobalResponse.ok(teamService.updateTeam(teamId, request));
    }

    @DeleteMapping("/{teamId}")
    public GlobalResponse<Void> dissolveTeam(@PathVariable Long teamId) {
        teamService.dissolveTeam(teamId);
        return GlobalResponse.ok(null);
    }

    @PutMapping("/{teamId}/creator-quota")
    @PreAuthorize("hasAuthority('team:manage')")
    public GlobalResponse<Void> setCreatorQuota(@PathVariable Long teamId,
                                                @Valid @RequestBody CreatorQuotaRequest request) {
        teamService.setCreatorQuota(teamId, request.maxUploadMb());
        return GlobalResponse.ok(null);
    }
}
