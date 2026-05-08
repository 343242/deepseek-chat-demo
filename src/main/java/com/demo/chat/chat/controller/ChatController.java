package com.demo.chat.chat.controller;

import com.demo.chat.exception.BusinessException;

import com.demo.chat.chat.dto.ChatRequest;
import com.demo.chat.chat.dto.ChatResponse;
import com.demo.chat.chat.dto.ProviderModelInfo;
import com.demo.chat.chat.service.ChatService;
import com.demo.chat.chat.service.ModelService;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

/**
 * 聊天 API 控制器
 * <p>
 * GET  /api/models        - 获取可用模型列表
 * POST /api/chat           - 阻塞式聊天
 * GET  /api/chat/stream    - SSE 流式聊天（query params）
 * POST /api/chat/stream    - SSE 流式聊天（JSON body）
 * POST /api/models/refresh - 刷新模型列表
 */
@RestController
@RequestMapping("/api")
@PreAuthorize("hasAuthority('chat:send')")
public class ChatController {

    private final ModelService modelService;
    private final ChatService chatService;

    public ChatController(ModelService modelService, ChatService chatService) {
        this.modelService = modelService;
        this.chatService = chatService;
    }

    /**
     * 获取可用模型列表
     */
    @GetMapping("/models")
    public List<ProviderModelInfo> listModels() {
        return modelService.listModels();
    }

    /**
     * 阻塞式聊天
     */
    @PostMapping("/chat")
    public ChatResponse chat(@Valid @RequestBody ChatRequest request) {
        validateChatRequest(request);
        return chatService.chat(request);
    }

    /**
     * SSE 流式聊天（GET 方式，方便 SSE 客户端测试）
     */
    @GetMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> chatStreamGet(
            @RequestParam String model,
            @RequestParam String message,
            @RequestParam(defaultValue = "default") String conversationId) {
        validateParams(model, message);
        ChatRequest request = new ChatRequest(model, message, conversationId);
        return chatService.chatStream(request);
    }

    /**
     * SSE 流式聊天（POST 方式）
     */
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> chatStreamPost(@Valid @RequestBody ChatRequest request) {
        validateChatRequest(request);
        return chatService.chatStream(request);
    }

    /**
     * 刷新模型列表
     */
    @PostMapping("/models/refresh")
    public ResponseEntity<Map<String, Object>> refreshModels() {
        boolean success = modelService.refreshModels();
        if (success) {
            return ResponseEntity.ok(Map.of("message", "Models refreshed successfully"));
        }
        return ResponseEntity.internalServerError().body(Map.of(
                "message", "Failed to refresh models, existing models remain available"));
    }

    private void validateChatRequest(ChatRequest request) {
        validateRequired(request.model(), "model");
        validateRequired(request.message(), "message");
    }

    private void validateParams(String model, String message) {
        validateRequired(model, "model");
        validateRequired(message, "message");
    }

    private void validateRequired(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(fieldName + " 不能为空");
        }
    }
}
