package com.smart.rag.chat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smart.rag.chat.dto.UsageStats;
import com.smart.rag.chat.entity.TokenUsage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Token 用量 Mapper
 * <p>
 * 封装所有数据库查询逻辑，Service 层不直接使用 LambdaQueryWrapper。
 * 复杂聚合查询通过 XML Mapper 实现。
 */
@Mapper
public interface TokenUsageMapper extends BaseMapper<TokenUsage> {

    /**
     * 按对话 ID 查询（按创建时间倒序）
     */
    List<TokenUsage> selectByConversationId(@Param("conversationId") String conversationId);

    /**
     * 按模型 ID + 对话前缀查询（用户隔离，按创建时间倒序）
     */
    List<TokenUsage> selectByModelAndUserPrefix(@Param("modelId") String modelId,
                                                 @Param("userPrefix") String userPrefix);

    List<UsageStats> aggregateByModel(@Param("modelId") String modelId,
                                      @Param("startTime") OffsetDateTime startTime,
                                      @Param("endTime") OffsetDateTime endTime);

    List<UsageStats> aggregateByConversation(@Param("conversationId") String conversationId,
                                             @Param("startTime") OffsetDateTime startTime,
                                             @Param("endTime") OffsetDateTime endTime);

    List<UsageStats> aggregateByModelForUser(@Param("modelId") String modelId,
                                             @Param("userPrefix") String userPrefix,
                                             @Param("startTime") OffsetDateTime startTime,
                                             @Param("endTime") OffsetDateTime endTime);

    List<UsageStats> aggregateByUserConversations(@Param("userPrefix") String userPrefix,
                                                   @Param("startTime") OffsetDateTime startTime,
                                                   @Param("endTime") OffsetDateTime endTime);
}
