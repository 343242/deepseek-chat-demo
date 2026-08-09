package com.smart.rag.chat.service;

import com.smart.rag.mode.ChatRequest;
import com.smart.rag.chat.dto.ChatResponse;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 聊天服务接口
 */
public interface ChatService {

    ChatResponse chat(ChatRequest request);

    SseEmitter chatStream(ChatRequest request);

    /**
     * 取消指定会话的活跃流式生成（软取消，design chat-stream-cancel.md §4）。
     * <p>
     * 断开与 LLM 的连接（停止拉取新 token），让下游以正常 onComplete 终止，
     * 桥接层发送 {@code event:canceled} 终止帧后 complete emitter。已生成的部分内容不落库。
     *
     * @param rawConversationId 原始对话 ID（前端持有）
     * @param reason            取消原因（打点用）
     * @return 取消结果（cancelled=true 表示命中活跃流；false 表示流不存在/已结束，幂等）
     */
    com.smart.rag.chat.dto.CancelStreamResponse cancelStream(String rawConversationId, com.smart.rag.chat.dto.CancelReason reason);
}
