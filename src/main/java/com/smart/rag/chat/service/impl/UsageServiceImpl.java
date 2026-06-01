package com.smart.rag.chat.service.impl;

import com.smart.rag.chat.mapper.TokenUsageMapper;
import com.smart.rag.chat.dto.TokenUsageDTO;
import com.smart.rag.chat.dto.UsageStats;
import com.smart.rag.chat.entity.TokenUsage;
import com.smart.rag.chat.service.UsageService;
import com.smart.rag.common.errorcode.ErrorCode;
import com.smart.rag.common.conversation.ConversationIdUtil;
import com.smart.rag.exception.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

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
public class UsageServiceImpl implements UsageService {

    private static final int DEFAULT_DAYS = 30;

    private final TokenUsageMapper mapper;
    private final TransactionTemplate transactionTemplate;

    public UsageServiceImpl(TokenUsageMapper mapper, TransactionTemplate transactionTemplate) {
        this.mapper = mapper;
        this.transactionTemplate = transactionTemplate;
    }

    @Override
    public void recordUsage(String conversationId, String modelId,
                            long promptTokens, long completionTokens, long totalTokens,
                            long durationMs) {
        TokenUsage usage = new TokenUsage(
                conversationId, modelId,
                promptTokens, completionTokens, totalTokens,
                durationMs
        );
        transactionTemplate.executeWithoutResult(status -> mapper.insert(usage));
    }

    @Override
    public List<TokenUsageDTO> getRecords(Long userId, String conversation, String model) {
        if (conversation != null && !conversation.isBlank()) {
            String isolatedId = ConversationIdUtil.buildIsolatedId(userId, conversation);
            return getByConversation(isolatedId);
        }
        if (model != null && !model.isBlank()) {
            String prefix = ConversationIdUtil.buildLikePrefix(userId);
            return getByModelAndUser(model, prefix);
        }
        throw new BusinessException(ErrorCode.USAGE_PARAM_MISSING);
    }

    @Override
    public List<UsageStats> statsByModel(Long userId, String model,
                                         LocalDateTime startTime, LocalDateTime endTime) {
        String prefix = ConversationIdUtil.buildLikePrefix(userId);
        return aggregateByModelForUser(model, prefix, startTime, endTime);
    }

    @Override
    public List<UsageStats> statsByConversation(Long userId, String conversation,
                                                 LocalDateTime startTime, LocalDateTime endTime) {
        if (conversation != null && !conversation.isBlank()) {
            String isolatedId = ConversationIdUtil.buildIsolatedId(userId, conversation);
            return aggregateByConversation(isolatedId, startTime, endTime);
        }
        String prefix = ConversationIdUtil.buildLikePrefix(userId);
        return aggregateByUserConversations(prefix, startTime, endTime);
    }

    @Override
    public List<TokenUsageDTO> getByConversation(String conversationId) {
        return mapper.selectByConversationId(conversationId).stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
    }

    @Override
    public List<TokenUsageDTO> getByModelAndUser(String modelId, String userPrefix) {
        return mapper.selectByModelAndUserPrefix(modelId, userPrefix).stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
    }

    @Override
    public List<UsageStats> aggregateByModel(String modelId,
                                              LocalDateTime startTime,
                                              LocalDateTime endTime) {
        LocalDateTime start = startTime != null ? startTime : LocalDateTime.now().minusDays(DEFAULT_DAYS);
        return mapper.aggregateByModel(modelId, start, endTime);
    }

    @Override
    public List<UsageStats> aggregateByModelForUser(String modelId, String userPrefix,
                                                     LocalDateTime startTime, LocalDateTime endTime) {
        LocalDateTime start = startTime != null ? startTime : LocalDateTime.now().minusDays(DEFAULT_DAYS);
        return mapper.aggregateByModelForUser(modelId, userPrefix, start, endTime);
    }

    @Override
    public List<UsageStats> aggregateByConversation(String conversationId,
                                                    LocalDateTime startTime,
                                                    LocalDateTime endTime) {
        LocalDateTime start = startTime != null ? startTime : LocalDateTime.now().minusDays(DEFAULT_DAYS);
        return mapper.aggregateByConversation(conversationId, start, endTime);
    }

    @Override
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
