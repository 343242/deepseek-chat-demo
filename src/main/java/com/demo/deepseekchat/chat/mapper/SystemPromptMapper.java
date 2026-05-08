package com.demo.deepseekchat.chat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.demo.deepseekchat.chat.entity.SystemPrompt;
import org.apache.ibatis.annotations.Mapper;

/**
 * 系统提示词 Mapper
 */
@Mapper
public interface SystemPromptMapper extends BaseMapper<SystemPrompt> {
}
