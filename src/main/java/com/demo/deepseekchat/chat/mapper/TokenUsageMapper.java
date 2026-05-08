package com.demo.deepseekchat.chat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.demo.deepseekchat.chat.dto.UsageStats;
import com.demo.deepseekchat.chat.entity.TokenUsage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Token 用量 Mapper
 */
@Mapper
public interface TokenUsageMapper extends BaseMapper<TokenUsage> {

    List<UsageStats> aggregateByModel(@Param("modelId") String modelId,
                                      @Param("startTime") LocalDateTime startTime,
                                      @Param("endTime") LocalDateTime endTime);

    List<UsageStats> aggregateByConversation(@Param("conversationId") String conversationId,
                                             @Param("startTime") LocalDateTime startTime,
                                             @Param("endTime") LocalDateTime endTime);

    List<UsageStats> aggregateByModelForUser(@Param("modelId") String modelId,
                                             @Param("userPrefix") String userPrefix,
                                             @Param("startTime") LocalDateTime startTime,
                                             @Param("endTime") LocalDateTime endTime);

    List<UsageStats> aggregateByUserConversations(@Param("userPrefix") String userPrefix,
                                                   @Param("startTime") LocalDateTime startTime,
                                                   @Param("endTime") LocalDateTime endTime);
}
