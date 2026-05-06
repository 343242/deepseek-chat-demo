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
 */
@Service
public class UsageService {

    private final TokenUsageRepository repository;

    public UsageService(TokenUsageRepository repository) {
        this.repository = repository;
    }

    /**
     * 记录一次调用的 token 用量
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
     * 按模型聚合统计
     *
     * @param modelId   可选，指定模型过滤
     * @param startTime 可选，起始时间
     * @param endTime   可选，结束时间
     */
    public List<UsageStats> aggregateByModel(String modelId,
                                              LocalDateTime startTime,
                                              LocalDateTime endTime) {
        return repository.aggregateByModel(modelId, startTime, endTime);
    }

    /**
     * 按对话聚合统计
     *
     * @param conversationId 可选，指定对话过滤
     * @param startTime      可选，起始时间
     * @param endTime        可选，结束时间
     */
    public List<UsageStats> aggregateByConversation(String conversationId,
                                                    LocalDateTime startTime,
                                                    LocalDateTime endTime) {
        return repository.aggregateByConversation(conversationId, startTime, endTime);
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
