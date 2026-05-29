package com.smart.rag.team.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smart.rag.team.entity.TeamMember;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

@Mapper
public interface TeamMemberMapper extends BaseMapper<TeamMember> {

    TeamMember selectByTeamAndUser(@Param("teamId") Long teamId, @Param("userId") Long userId);

    /**
     * 查找用户的团队成员记录（包含已退出的），用于重新加入判断
     */
    TeamMember selectLatestByTeamAndUser(@Param("teamId") Long teamId, @Param("userId") Long userId);

    /**
     * 按团队 ID 批量统计活跃成员数
     *
     * @return [{team_id: Long, cnt: Long}, ...]
     */
    List<Map<String, Object>> selectMemberCountByTeamIds(@Param("teamIds") List<Long> teamIds);
}
