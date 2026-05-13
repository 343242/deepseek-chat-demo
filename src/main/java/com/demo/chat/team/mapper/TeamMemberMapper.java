package com.demo.chat.team.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.demo.chat.team.entity.TeamMember;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface TeamMemberMapper extends BaseMapper<TeamMember> {

    @Select("SELECT * FROM team_member WHERE team_id = #{teamId} AND user_id = #{userId} AND status = 1 LIMIT 1")
    TeamMember selectByTeamAndUser(@Param("teamId") Long teamId, @Param("userId") Long userId);
}
