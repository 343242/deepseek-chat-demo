package com.smart.rag.chat.controller;

import com.smart.rag.chat.dto.ChatRequest;
import com.smart.rag.chat.dto.ChatResponse;
import com.smart.rag.infrastructure.model.ProviderModelInfo;
import com.smart.rag.chat.service.ChatService;
import com.smart.rag.chat.service.ModelService;
import com.smart.rag.infrastructure.response.GlobalResponse;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

/**
 * 聊天 API 控制器
 */
@RestController
@RequestMapping("/api/v1")
@PreAuthorize("hasAuthority('chat:send')")
@Validated
public class ChatController {

    private final ModelService modelService;
    private final ChatService chatService;

    public ChatController(ModelService modelService, ChatService chatService) {
        this.modelService = modelService;
        this.chatService = chatService;
    }

    @GetMapping("/models")
    public GlobalResponse<List<ProviderModelInfo>> listModels() {
        return GlobalResponse.ok(modelService.listModels());
    }

    @PostMapping("/chat")
    public GlobalResponse<ChatResponse> chat(@Valid @RequestBody ChatRequest request) {
        return GlobalResponse.ok(chatService.chat(request));
    }

    /**
     * SSE 流式聊天 — 不走 GlobalResponse 包装，保持 text/event-stream 原样
     */
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStreamPost(@Valid @RequestBody ChatRequest request) {
        return chatService.chatStream(request);
    }

    @PostMapping("/models/refresh")
    public GlobalResponse<Void> refreshModels() {
        boolean success = modelService.refreshModels();
        if (success) {
            return GlobalResponse.ok("Models refreshed successfully");
        }
        return GlobalResponse.ok("Failed to refresh models, existing models remain available");
    }
}
