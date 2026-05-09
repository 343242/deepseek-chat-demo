package com.demo.chat.chat.service;

import com.demo.chat.chat.dto.TokenUsageDTO;
import com.demo.chat.chat.dto.UsageStats;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 用量统计服务接口
 */
public interface UsageService {

    void recordUsage(String conversationId, String modelId,
                     long promptTokens, long completionTokens, long totalTokens,
                     long durationMs);

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
