package com.demo.deepseekchat.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.demo.deepseekchat.mapper.TokenUsageMapper;
import com.demo.deepseekchat.model.dto.TokenUsageDTO;
import com.demo.deepseekchat.model.dto.UsageStats;
import com.demo.deepseekchat.model.entity.TokenUsage;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 用量统计服务
 * <p>
 * 记录每次 API 调用的 token 消耗和耗时，提供按模型/对话聚合统计。
 * 支持用户级隔离查询，通过 conversationId 的 "u_{userId}_" 前缀过滤。
 */
@Service
public class UsageService {

    private static final int DEFAULT_DAYS = 30;

    private final TokenUsageMapper mapper;

    public UsageService(TokenUsageMapper mapper) {
        this.mapper = mapper;
    }

    public void recordUsage(String conversationId, String modelId,
                            long promptTokens, long completionTokens, long totalTokens,
                            long durationMs) {
        TokenUsage usage = new TokenUsage(
                conversationId, modelId,
                promptTokens, completionTokens, totalTokens,
                durationMs
        );
        mapper.insert(usage);
    }

    public List<TokenUsageDTO> getByConversation(String conversationId) {
        return mapper.selectList(
                new LambdaQueryWrapper<TokenUsage>()
                        .eq(TokenUsage::getConversationId, conversationId)
                        .orderByDesc(TokenUsage::getCreatedAt))
                .stream().map(this::toDTO).collect(Collectors.toList());
    }

    /**
     * 查询指定模型 + 用户前缀的用量记录（用户隔离）
     */
    public List<TokenUsageDTO> getByModelAndUser(String modelId, String userPrefix) {
        return mapper.selectList(
                new LambdaQueryWrapper<TokenUsage>()
                        .eq(TokenUsage::getModelId, modelId)
                        .likeRight(TokenUsage::getConversationId, userPrefix)
                        .orderByDesc(TokenUsage::getCreatedAt))
                .stream().map(this::toDTO).collect(Collectors.toList());
    }

    public List<UsageStats> aggregateByModel(String modelId,
                                              LocalDateTime startTime,
                                              LocalDateTime endTime) {
        LocalDateTime start = startTime != null ? startTime : LocalDateTime.now().minusDays(DEFAULT_DAYS);
        return mapper.aggregateByModel(modelId, start, endTime);
    }

    /**
     * 按模型聚合统计（用户隔离，仅统计当前用户前缀的记录）
     */
    public List<UsageStats> aggregateByModelForUser(String modelId, String userPrefix,
                                                     LocalDateTime startTime, LocalDateTime endTime) {
        LocalDateTime start = startTime != null ? startTime : LocalDateTime.now().minusDays(DEFAULT_DAYS);
        return mapper.aggregateByModelForUser(modelId, userPrefix, start, endTime);
    }

    public List<UsageStats> aggregateByConversation(String conversationId,
                                                    LocalDateTime startTime,
                                                    LocalDateTime endTime) {
        LocalDateTime start = startTime != null ? startTime : LocalDateTime.now().minusDays(DEFAULT_DAYS);
        return mapper.aggregateByConversation(conversationId, start, endTime);
    }

    /**
     * 按用户所有对话聚合统计
     */
    public List<UsageStats> aggregateByUserConversations(String userPrefix,
                                                          LocalDateTime startTime,
                                                          LocalDateTime endTime) {
        LocalDateTime start = startTime != null ? startTime : LocalDateTime.now().minusDays(DEFAULT_DAYS);
        return mapper.aggregateByUserConversations(userPrefix, start, endTime);
    }

    private TokenUsageDTO toDTO(TokenUsage entity) {
        return new TokenUsageDTO(
                entity.getConversationId(),
                entity.getModelId(),
                entity.getPromptTokens(),
                entity.getCompletionTokens(),
                entity.getTotalTokens(),
                entity.getDurationMs(),
                entity.getCreatedAt()
        );
    }
}
