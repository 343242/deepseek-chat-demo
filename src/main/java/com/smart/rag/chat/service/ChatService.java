package com.smart.rag.chat.service;

import com.smart.rag.chat.dto.ChatRequest;
import com.smart.rag.chat.dto.ChatResponse;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 聊天服务接口
 */
public interface ChatService {

    ChatResponse chat(ChatRequest request);

    SseEmitter chatStream(ChatRequest request);
}
