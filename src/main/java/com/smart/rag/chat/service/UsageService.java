package com.smart.rag.chat.service;

import com.smart.rag.chat.dto.TokenUsageDTO;
import com.smart.rag.chat.dto.UsageStats;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 用量统计服务接口
 */
public interface UsageService {

    void recordUsage(String conversationId, String modelId,
                     long promptTokens, long completionTokens, long totalTokens,
                     long durationMs);

    List<TokenUsageDTO> getRecords(Long userId, String conversation, String model);

    List<UsageStats> statsByModel(Long userId, String model,
                                  LocalDateTime startTime, LocalDateTime endTime);

    List<UsageStats> statsByConversation(Long userId, String conversation,
                                         LocalDateTime startTime, LocalDateTime endTime);

    // ---- 内部查询方法（保留，供其他服务复用）----

    List<TokenUsageDTO> getByConversation(String conversationId);

    List<TokenUsageDTO> getByModelAndUser(String modelId, String userPrefix);

    List<UsageStats> aggregateByModel(String modelId,
                                       LocalDateTime startTime,
                                       LocalDateTime endTime);

    List<UsageStats> aggregateByModelForUser(String modelId, String userPrefix,
                                              LocalDateTime startTime, LocalDateTime endTime);

    List<UsageStats> aggregateByConversation(String conversationId,
                                              LocalDateTime startTime,
                                              LocalDateTime endTime);

    List<UsageStats> aggregateByUserConversations(String userPrefix,
                                                    LocalDateTime startTime,
                                                    LocalDateTime endTime);
}
