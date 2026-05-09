package com.demo.chat.chat.service;

import com.demo.chat.chat.dto.ChatRequest;
import com.demo.chat.chat.dto.ChatResponse;
import reactor.core.publisher.Flux;

/**
 * 聊天服务接口
 */
public interface ChatService {

    ChatResponse chat(ChatRequest request);

    Flux<String> chatStream(ChatRequest request);
}
