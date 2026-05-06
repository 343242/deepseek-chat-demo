package com.demo.deepseekchat.controller;

import com.demo.deepseekchat.model.dto.ChatRequest;
import com.demo.deepseekchat.model.dto.ChatResponse;
import com.demo.deepseekchat.model.dto.ModelInfo;
import com.demo.deepseekchat.service.ChatService;
import com.demo.deepseekchat.service.ModelService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * 聊天 API 控制器
 * <p>
 * GET  /api/models        - 获取可用模型列表
 * POST /api/chat           - 阻塞式聊天
 * GET  /api/chat/stream    - SSE 流式聊天
 */
@RestController
@RequestMapping("/api")
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
    public List<ModelInfo> listModels() {
        return modelService.listModels();
    }

    /**
     * 阻塞式聊天
     */
    @PostMapping("/chat")
    public ChatResponse chat(@RequestBody ChatRequest request) {
        return chatService.chat(request);
    }

    /**
     * SSE 流式聊天
     * <p>
     * 使用 GET 请求 + query params 方便 SSE 客户端测试，
     * 也支持 POST + body 方式
     */
    @GetMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> chatStreamGet(
            @RequestParam String model,
            @RequestParam String message,
            @RequestParam(defaultValue = "default") String conversationId) {
        ChatRequest request = new ChatRequest(model, message, conversationId);
        return chatService.chatStream(request);
    }

    /**
     * SSE 流式聊天（POST 方式）
     */
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> chatStreamPost(@RequestBody ChatRequest request) {
        return chatService.chatStream(request);
    }

    /**
     * 刷新模型列表
     */
    @PostMapping("/models/refresh")
    public String refreshModels() {
        modelService.refreshModels();
        return "Models refreshed";
    }
}
