package com.demo.chat.team.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.demo.chat.team.entity.TeamMember;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

@Mapper
public interface TeamMemberMapper extends BaseMapper<TeamMember> {

    @Select("SELECT * FROM team_member WHERE team_id = #{teamId} AND user_id = #{userId} AND status = 1 LIMIT 1")
    TeamMember selectByTeamAndUser(@Param("teamId") Long teamId, @Param("userId") Long userId);

    /**
     * 按团队 ID 批量统计活跃成员数
     *
     * @return [{team_id: Long, cnt: Long}, ...]
     */
    @Select("<script>SELECT team_id AS team_id, COUNT(*) AS cnt FROM team_member WHERE status = 1 AND team_id IN <foreach collection='teamIds' item='id' open='(' separator=',' close=')'>#{id}</foreach> GROUP BY team_id</script>")
    List<Map<String, Object>> selectMemberCountByTeamIds(@Param("teamIds") List<Long> teamIds);
}
