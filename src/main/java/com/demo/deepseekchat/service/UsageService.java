package com.demo.deepseekchat.service;

import com.demo.deepseekchat.model.dto.TokenUsageDTO;
import com.demo.deepseekchat.model.dto.UsageStats;
import com.demo.deepseekchat.model.entity.TokenUsage;
import com.demo.deepseekchat.repository.TokenUsageRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 用量统计服务
 * <p>
 * 记录每次 API 调用的 token 消耗和耗时，提供按模型/对话聚合统计。
 * 聚合查询默认限制最近 30 天，防止全表扫描。
 */
@Service
public class UsageService {

    private static final int DEFAULT_DAYS = 30;

    private final TokenUsageRepository repository;

    public UsageService(TokenUsageRepository repository) {
        this.repository = repository;
    }

    /**
     * 记录一次调用的 token 用量
     * <p>
     * 流式模式下 token 值为 -1 表示未获取到。
     */
    public void recordUsage(String conversationId, String modelId,
                            long promptTokens, long completionTokens, long totalTokens,
                            long durationMs) {
        TokenUsage usage = new TokenUsage(
                conversationId, modelId,
                promptTokens, completionTokens, totalTokens,
                durationMs
        );
        repository.save(usage);
    }

    /**
     * 查询指定对话的用量记录
     */
    public List<TokenUsageDTO> getByConversation(String conversationId) {
        return repository.findByConversationIdOrderByCreatedAtDesc(conversationId)
                .stream().map(this::toDTO).collect(Collectors.toList());
    }

    /**
     * 查询指定模型的用量记录
     */
    public List<TokenUsageDTO> getByModel(String modelId) {
        return repository.findByModelIdOrderByCreatedAtDesc(modelId)
                .stream().map(this::toDTO).collect(Collectors.toList());
    }

    /**
     * 按模型聚合统计（默认最近 30 天）
     */
    public List<UsageStats> aggregateByModel(String modelId,
                                              LocalDateTime startTime,
                                              LocalDateTime endTime) {
        LocalDateTime start = startTime != null ? startTime : LocalDateTime.now().minusDays(DEFAULT_DAYS);
        return repository.aggregateByModel(modelId, start, endTime);
    }

    /**
     * 按对话聚合统计（默认最近 30 天）
     */
    public List<UsageStats> aggregateByConversation(String conversationId,
                                                    LocalDateTime startTime,
                                                    LocalDateTime endTime) {
        LocalDateTime start = startTime != null ? startTime : LocalDateTime.now().minusDays(DEFAULT_DAYS);
        return repository.aggregateByConversation(conversationId, start, endTime);
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
