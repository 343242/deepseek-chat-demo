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
    private final MessageDeadLetterQueue deadLetterQueue;

    public ChatConversationHelper(
            ConversationService conversationService,
            ConversationMessageService conversationMessageService,
            TransactionTemplate transactionTemplate,
            ChatMemory chatMemory,
            MessageDeadLetterQueue deadLetterQueue) {
        this.conversationService = conversationService;
        this.conversationMessageService = conversationMessageService;
        this.transactionTemplate = transactionTemplate;
        this.chatMemory = chatMemory;
        this.deadLetterQueue = deadLetterQueue;
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
     *
     * @param conversationId  会话 ID
     * @param userContent     用户消息内容
     * @param assistantContent AI 回复内容
     * @param modelId         模型 ID
     * @param aiResponse      AI 响应（含 usage 元数据，可为 null）
     * @param durationMs      调用耗时（毫秒）
     */
    public void saveMessagesAndNotify(String conversationId, String userContent, String assistantContent,
                                      String modelId,
                                      org.springframework.ai.chat.model.ChatResponse aiResponse,
                                      long durationMs) {
        try {
            transactionTemplate.executeWithoutResult(status -> {
                // 写入 USER 消息
                Message userMsg = Message.userMessage(conversationId, null, userContent);
                conversationMessageService.saveMessage(userMsg);

                // 写入 ASSISTANT 消息
                int totalTokens = -1;
                if (aiResponse != null && aiResponse.getMetadata().getUsage() != null) {
                    Usage usage = aiResponse.getMetadata().getUsage();
                    totalTokens = usage.getTotalTokens() != null ? usage.getTotalTokens().intValue() : -1;
                }
                Message assistantMsg = Message.assistantMessage(
                        conversationId, userMsg.getId(), assistantContent,
                        modelId, totalTokens, durationMs);
                conversationMessageService.saveMessage(assistantMsg);

                // 通知会话更新计数和标题
                conversationService.onNewMessages(conversationId, userContent, 2);
            });
        } catch (Exception e) {
            // 消息持久化失败不影响已返回给用户的响应，但必须记录完整异常栈
            log.error("Failed to save message records: conversationId={}, model={}",
                    ConversationIdUtil.mask(conversationId), modelId, e);

            int totalTokens = -1;
            if (aiResponse != null && aiResponse.getMetadata() != null && aiResponse.getMetadata().getUsage() != null) {
                Usage usage = aiResponse.getMetadata().getUsage();
                totalTokens = usage.getTotalTokens() != null ? usage.getTotalTokens().intValue() : -1;
            }
            deadLetterQueue.enqueue(new DeadLetterEntry(
                    conversationId, userContent, assistantContent, modelId, totalTokens, durationMs));
        }
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
