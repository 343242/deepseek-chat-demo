package com.smart.rag.team.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smart.rag.team.entity.Team;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface TeamMapper extends BaseMapper<Team> {

    Team selectByIdForUpdate(@Param("id") Long id);
}
