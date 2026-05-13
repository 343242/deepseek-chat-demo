package com.demo.chat.chat.controller;

import com.demo.chat.chat.dto.ChatRequest;
import com.demo.chat.chat.dto.ChatResponse;
import com.demo.chat.chat.dto.ProviderModelInfo;
import com.demo.chat.chat.service.ChatService;
import com.demo.chat.chat.service.ModelService;
import com.demo.chat.common.response.GlobalResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * 聊天 API 控制器
 */
@RestController
@RequestMapping("/api")
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
    @GetMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> chatStreamGet(
            @RequestParam @NotBlank(message = "model 不能为空") String model,
            @RequestParam @NotBlank(message = "message 不能为空") String message,
            @RequestParam(required = false) String conversationId,
            @RequestParam(defaultValue = "SIMPLE") String mode,
            @RequestParam(defaultValue = "false") boolean ragEnabled,
            @RequestParam(defaultValue = "false") boolean enableThinking) {
        ChatRequest request = new ChatRequest(model, message, conversationId, ragEnabled, mode, enableThinking, null);
        return chatService.chatStream(request);
    }

    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> chatStreamPost(@Valid @RequestBody ChatRequest request) {
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
