package com.smart.rag.chat.controller;

import com.smart.rag.chat.dto.CancelReason;
import com.smart.rag.chat.dto.CancelStreamRequest;
import com.smart.rag.chat.dto.CancelStreamResponse;
import com.smart.rag.mode.ChatRequest;
import com.smart.rag.chat.dto.ChatResponse;
import com.smart.rag.chat.dto.ModelVO;
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
    public GlobalResponse<List<String>> listModels() {
        // 聊天端点仅暴露 CHAT 模型；Embedding/Rerank 通过 /api/admin/llm-config（user:manage）审计
        return GlobalResponse.ok(modelService.listChatModelIds());
    }

    /**
     * 模型目录详情（含 provider / 能力标签 / 可用状态）。
     * <p>
     * 能力可见性按用途分流：
     * <ul>
     *   <li>CHAT（含不传 capability）—— 任何能聊天的用户（chat:send）可见，供 ModelSelector 使用</li>
     *   <li>EMBEDDING / RERANKING —— 仅 model:config 持有者可见，供管理界面配置向量/重排模型</li>
     * </ul>
     * 普通用户传非 CHAT capability 会被 Spring Security 拒绝（403），而非返回空列表——
     * 显式拒绝比静默空结果更安全，避免误导前端以为是「暂时无模型」。
     */
    @GetMapping("/models/detail")
    @PreAuthorize("#capability == null or #capability.isBlank() or #capability.equalsIgnoreCase('CHAT') or hasAuthority('model:config')")
    public GlobalResponse<List<ModelVO>> listModelDetails(
            @RequestParam(value = "capability", required = false) String capability) {
        return GlobalResponse.ok(modelService.listModelDetails(capability));
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

    /**
     * 取消流式生成（design chat-stream-cancel.md §6.1）。
     * <p>
     * 软取消：断开与 LLM 的连接（停止拉取新 token），让下游以正常 onComplete 终止，
     * 桥接层发送 {@code event:canceled} 终止帧后 complete emitter。已生成内容不落库。
     * <p>
     * 幂等：流不存在/已结束时返回 {@code cancelled:false}，不报错。
     * 权限沿用类级 {@code @PreAuthorize("hasAuthority('chat:send')")}，端点级不重复。
     */
    @PostMapping("/chat/stream/cancel")
    public GlobalResponse<CancelStreamResponse> cancelStream(@Valid @RequestBody CancelStreamRequest request) {
        return GlobalResponse.ok(chatService.cancelStream(request.conversationId(), request.reason()));
    }

    @PostMapping("/models/refresh")
    @PreAuthorize("hasAuthority('model:config')")
    public GlobalResponse<Void> refreshModels() {
        boolean success = modelService.refreshModels();
        if (success) {
            return GlobalResponse.ok("Models refreshed successfully");
        }
        return GlobalResponse.ok("Failed to refresh models, existing models remain available");
    }
}
