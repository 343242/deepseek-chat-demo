package com.demo.chat.chat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.demo.chat.chat.entity.SystemPrompt;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Delete;

import java.util.List;

/**
 * System Prompt Mapper
 * <p>
 * 封装所有数据库查询逻辑，Service 层不直接使用 LambdaQueryWrapper。
 */
@Mapper
public interface SystemPromptMapper extends BaseMapper<SystemPrompt> {

    @Select("SELECT * FROM system_prompt WHERE model_id = #{modelId}")
    SystemPrompt selectByModelId(@Param("modelId") String modelId);

    @Select("SELECT * FROM system_prompt ORDER BY model_id")
    List<SystemPrompt> selectAllOrdered();

    @Delete("DELETE FROM system_prompt WHERE model_id = #{modelId}")
    int deleteByModelId(@Param("modelId") String modelId);
}
