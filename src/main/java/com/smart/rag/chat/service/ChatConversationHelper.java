package com.smart.rag.chat.service;

import com.smart.rag.conversation.entity.Message;
import com.smart.rag.conversation.service.ConversationMessageService;
import com.smart.rag.conversation.service.ConversationService;
import com.smart.rag.common.util.ConversationIdUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 聊天对话助手
 * <p>
 * 从 ChatServiceImpl 提取，封装对话管理相关操作：
 * <ul>
 *   <li>确保会话记录存在（自动创建）</li>
 *   <li>保存消息记录并通知会话更新</li>
 *   <li>保存流式部分响应</li>
 * </ul>
 */
@Component
public class ChatConversationHelper {

    private static final Logger log = LoggerFactory.getLogger(ChatConversationHelper.class);

    private final ConversationService conversationService;
    private final ConversationMessageService conversationMessageService;
    private final TransactionTemplate transactionTemplate;
    private final ChatMemory chatMemory;

    public ChatConversationHelper(
            ConversationService conversationService,
            ConversationMessageService conversationMessageService,
            TransactionTemplate transactionTemplate,
            ChatMemory chatMemory) {
        this.conversationService = conversationService;
        this.conversationMessageService = conversationMessageService;
        this.transactionTemplate = transactionTemplate;
        this.chatMemory = chatMemory;
    }

    /**
     * 获取会话消息数量
     *
     * @param conversationId 会话 ID
     * @return 消息数量
     */
    public int getMessageCount(String conversationId) {
        try {
            var messages = chatMemory.get(conversationId);
            return messages != null ? messages.size() : 0;
        } catch (Exception e) {
            log.error("Failed to get message count: conversationId={}", ConversationIdUtil.mask(conversationId), e);
            return 0;
        }
    }

    /**
     * 确保会话记录存在（自动创建）
     *
     * @param userId         用户 ID
     * @param conversationId 隔离后的会话 ID
     * @param model          模型名称
     */
    public void ensureConversationExists(Long userId, String conversationId, String model) {
        try {
            conversationService.getOrCreate(userId, conversationId, model);
        } catch (DuplicateKeyException e) {
            // 并发创建冲突，唯一约束兜底，忽略
            log.debug("Conversation already exists (concurrent create): {}", ConversationIdUtil.mask(conversationId));
        } catch (Exception e) {
            log.error("Failed to ensure conversation exists: conversationId={}", ConversationIdUtil.mask(conversationId), e);
            throw e;
        }
    }

    /**
     * 保存业务消息记录并通知会话更新
     * <p>
     * 使用编程式事务保证 USER 消息 + ASSISTANT 消息 + 会话计数的原子性。
     * 事务失败时向上传播异常，调用方决定是否影响主流程。
     * <p>
     * <b>Phase C Step 2 调整</b>：参数从 {@code ChatResponse aiResponse} 改为 {@code int totalTokens}，
     * 让消息总线 publisher/consumer 链路能直接传入 payload 中已携带的 totalTokens（方案 A）。
     * AI 响应 → totalTokens 的提取逻辑由 {@link #extractTotalTokens(ChatResponse)} 统一提供，
     * publisher（构建 payload）与 publisher 的同步降级路径均复用同一逻辑。
     *
     * @param conversationId  会话 ID
     * @param userContent     用户消息内容
     * @param assistantContent AI 回复内容
     * @param modelId         模型 ID（registry candidate ID 字符串）
     * @param totalTokens     总 token 数（{@code -1} 表示未知）
     * @param durationMs      调用耗时（毫秒）
     */
    public void saveMessagesAndNotify(String conversationId, String userContent, String assistantContent,
                                      String modelId,
                                      int totalTokens,
                                      long durationMs) {
        // Phase D D-4：落库失败时异常向上传播（不再 catch + enqueue legacy DLQ + 吞咽）。
        //  - bus consumer 路径：RedisStreamConsumerRunner 捕获 → XACK + ZSET 退避重试 → 耗尽进 DLQ
        //  - publisher 侧降级：child 2 已删除 ChatMessagePublisher.saveWithBoundedRetry——
        //    outbox 行即为持久化保证（INSERT 后 relay 补投，见 OutboxMessageBus）
        // 事务模板失败已自动回滚（无半写入），重试干净。
        transactionTemplate.executeWithoutResult(status -> {
            // 写入 USER 消息
            Message userMsg = Message.userMessage(conversationId, null, userContent);
            conversationMessageService.saveMessage(userMsg);

            // 写入 ASSISTANT 消息
            Message assistantMsg = Message.assistantMessage(
                    conversationId, userMsg.getId(), assistantContent,
                    modelId, totalTokens, durationMs);
            conversationMessageService.saveMessage(assistantMsg);

            // 通知会话更新计数和标题
            conversationService.onNewMessages(conversationId, userContent, 2);
        });
    }

    /**
     * 从 AI 响应安全提取 totalTokens；{@code aiResponse} / {@code metadata} / {@code usage}
     * 任一为空或值为 {@code null} 时返回 {@code -1}。
     * <p>
     * Publisher 构建消息 payload、以及 publisher 端 {@code MessageBus} 故障时的同步降级路径
     * 均通过此方法提取 token 数，避免逻辑重复。
     */
    public static int extractTotalTokens(org.springframework.ai.chat.model.ChatResponse aiResponse) {
        if (aiResponse == null || aiResponse.getMetadata() == null) {
            return -1;
        }
        Usage usage = aiResponse.getMetadata().getUsage();
        if (usage == null || usage.getTotalTokens() == null) {
            return -1;
        }
        return usage.getTotalTokens().intValue();
    }

    /**
     * 保存流式部分响应（仅在流中断时调用）
     *
     * @param conversationId 会话 ID
     * @param content        已收集的部分内容
     */
    public void savePartialResponse(String conversationId, String content) {
        if (content != null && !content.isBlank()) {
            try {
                var history = chatMemory.get(conversationId);
                if (!history.isEmpty()) {
                    var lastMsg = history.getLast();
                    if (lastMsg instanceof AssistantMessage am && content.equals(am.getText())) {
                        log.debug("MessageChatMemoryAdvisor already saved identical response, skipping");
                        return;
                    }
                }
                chatMemory.add(conversationId, new AssistantMessage(content));
                log.info("Saved partial stream response for conversation: {}", ConversationIdUtil.mask(conversationId));
            } catch (Exception e) {
                log.error("Failed to save partial stream response: conversationId={}",
                        ConversationIdUtil.mask(conversationId), e);
            }
        }
    }
}
