package com.smart.rag.chat.service;

import java.io.Serial;
import java.io.Serializable;

/**
 * 消息持久化失败后的死信记录
 */
public class DeadLetterEntry implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    static final String QUEUE_KEY = "chat:dead-letter";
    static final int MAX_RETRIES = 3;

    private final String conversationId;
    private final String userContent;
    private final String assistantContent;
    private final String modelId;
    private final int totalTokens;
    private final long durationMs;
    private int retryCount;
    private final long createdAt;

    public DeadLetterEntry(String conversationId, String userContent, String assistantContent,
                           String modelId, int totalTokens, long durationMs) {
        this.conversationId = conversationId;
        this.userContent = userContent;
        this.assistantContent = assistantContent;
        this.modelId = modelId;
        this.totalTokens = totalTokens;
        this.durationMs = durationMs;
        this.retryCount = 0;
        this.createdAt = System.currentTimeMillis();
    }

    public String conversationId() { return conversationId; }
    public String userContent() { return userContent; }
    public String assistantContent() { return assistantContent; }
    public String modelId() { return modelId; }
    public int totalTokens() { return totalTokens; }
    public long durationMs() { return durationMs; }
    public int retryCount() { return retryCount; }
    public long createdAt() { return createdAt; }

    public void incrementRetry() { retryCount++; }

    public boolean isRetryable() { return retryCount < MAX_RETRIES; }
}
