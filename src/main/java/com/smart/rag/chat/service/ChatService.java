package com.smart.rag.chat.service;

import com.smart.rag.chat.dto.ChatRequest;
import com.smart.rag.chat.dto.ChatResponse;
import reactor.core.publisher.Flux;

/**
 * 聊天服务接口
 */
public interface ChatService {

    ChatResponse chat(ChatRequest request);

    Flux<String> chatStream(ChatRequest request);
}
