package com.smart.rag.chat.service;

import com.smart.rag.chat.dto.TokenUsageDTO;
import com.smart.rag.chat.dto.UsageStats;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 用量统计服务接口
 */
public interface UsageService {

    /**
     * 记录一次调用的 token 用量与耗时。
     * <p>
     * 由 {@code UsageRecordConsumer}（消息总线消费端）调用落库；
     * {@code candidateId} 为 registry candidate ID（写入 {@code token_usage.model_id} 列，
     * 列名保留兼容历史数据，见 messaging-bus.md §7.2）。
     *
     * @param conversationId   会话 ID
     * @param candidateId      候选模型 ID（registry candidate ID）
     * @param promptTokens     输入 token 数，{@code -1} 表示未知
     * @param completionTokens 输出 token 数，{@code -1} 表示未知
     * @param totalTokens      总 token 数，{@code -1} 表示未知
     * @param durationMs       调用耗时（毫秒）
     */
    void recordUsage(String conversationId, String candidateId,
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
