package com.demo.chat.team.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.demo.chat.team.entity.Team;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface TeamMapper extends BaseMapper<Team> {

    @Select("SELECT * FROM team WHERE id = #{id} FOR UPDATE")
    Team selectByIdForUpdate(@Param("id") Long id);
}
