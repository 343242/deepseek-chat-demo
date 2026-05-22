package com.smart.rag.team.service;

import com.smart.rag.common.request.PageRequest;
import com.smart.rag.common.response.PagedResult;
import com.smart.rag.team.dto.MemberRoleUpdateRequest;
import com.smart.rag.team.dto.MemberUploadLimitRequest;
import com.smart.rag.team.dto.TeamMemberVO;

/**
 * 团队成员服务接口
 */
public interface TeamMemberService {

    /**
     * 邀请用户加入团队（创建者/管理员）
     */
    TeamMemberVO addMember(Long teamId, Long userId);

    /**
     * 移除成员（创建者/管理员，不能移除创建者）
     */
    void removeMember(Long teamId, Long userId);

    /**
     * 退出团队（不能退出自己是创建者的团队）
     */
    void leaveTeam(Long teamId);

    /**
     * 修改成员角色（仅创建者）
     */
    void updateMemberRole(Long teamId, Long userId, MemberRoleUpdateRequest request);

    /**
     * 设置成员上传额度（创建者/管理员）
     */
    void setMemberUploadLimit(Long teamId, Long userId, MemberUploadLimitRequest request);

    /**
     * 获取团队成员列表
     */
    PagedResult<TeamMemberVO> listMembers(Long teamId, PageRequest req);
}
