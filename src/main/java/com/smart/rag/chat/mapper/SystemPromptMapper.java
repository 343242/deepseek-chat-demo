package com.smart.rag.chat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smart.rag.chat.entity.SystemPrompt;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * System Prompt Mapper
 * <p>
 * 封装所有数据库查询逻辑，Service 层不直接使用 LambdaQueryWrapper。
 */
@Mapper
public interface SystemPromptMapper extends BaseMapper<SystemPrompt> {

    SystemPrompt selectByModelId(@Param("modelId") String modelId);

    List<SystemPrompt> selectAllOrdered();

    int deleteByModelId(@Param("modelId") String modelId);
}
