package com.demo.chat.chat.controller;

import com.demo.chat.chat.dto.ChatRequest;
import com.demo.chat.chat.dto.ChatResponse;
import com.demo.chat.chat.dto.ProviderModelInfo;
import com.demo.chat.chat.service.ChatService;
import com.demo.chat.chat.service.ModelService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
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
@Validated
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
     * <p>
     * ChatRequest 已通过 @NotBlank 注解定义校验规则，@Valid 触发校验，
     * GlobalExceptionHandler 统一处理 MethodArgumentNotValidException。
     */
    @PostMapping("/chat")
    public ChatResponse chat(@Valid @RequestBody ChatRequest request) {
        return chatService.chat(request);
    }

    /**
     * SSE 流式聊天（GET 方式，方便 SSE 客户端测试）
     */
    @GetMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> chatStreamGet(
            @RequestParam @NotBlank(message = "model 不能为空") String model,
            @RequestParam @NotBlank(message = "message 不能为空") String message,
            @RequestParam(defaultValue = "default") String conversationId) {
        ChatRequest request = new ChatRequest(model, message, conversationId, false);
        return chatService.chatStream(request);
    }

    /**
     * SSE 流式聊天（POST 方式）
     */
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> chatStreamPost(@Valid @RequestBody ChatRequest request) {
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
}
