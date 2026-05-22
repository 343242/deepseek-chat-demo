package com.smart.rag.team.service;

import com.smart.rag.team.dto.TeamCreateRequest;
import com.smart.rag.team.dto.TeamDetailVO;
import com.smart.rag.team.dto.TeamUpdateRequest;
import com.smart.rag.team.dto.TeamVO;

import java.util.List;

/**
 * 团队服务接口
 */
public interface TeamService {

    /**
     * 创建团队（创建者自动成为 CREATOR 成员）
     */
    TeamVO createTeam(TeamCreateRequest request);

    /**
     * 获取团队详情
     */
    TeamDetailVO getTeamDetail(Long teamId);

    /**
     * 获取我加入的团队列表
     */
    List<TeamVO> listMyTeams();

    /**
     * 更新团队信息（仅创建者）
     */
    TeamVO updateTeam(Long teamId, TeamUpdateRequest request);

    /**
     * 解散团队（仅创建者）
     */
    void dissolveTeam(Long teamId);

    /**
     * 设置创建者上传额度（系统管理员）
     */
    void setCreatorQuota(Long teamId, long maxUploadMb);
}
