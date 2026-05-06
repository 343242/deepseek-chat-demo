package com.demo.deepseekchat.controller;

import com.demo.deepseekchat.model.dto.ConversationMessage;
import com.demo.deepseekchat.model.dto.ConversationSummary;
import com.demo.deepseekchat.service.ConversationService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 对话管理 API
 * <p>
 * GET    /api/conversations          - 查询历史对话列表（分页）
 * GET    /api/conversations/{id}     - 获取指定对话消息
 * DELETE /api/conversations/{id}     - 清空指定对话
 * GET    /api/conversations/{id}/export - 导出对话记录
 */
@RestController
@RequestMapping("/api/conversations")
@PreAuthorize("hasAuthority('conversation:manage')")
public class ConversationController {

    private final ConversationService conversationService;

    public ConversationController(ConversationService conversationService) {
        this.conversationService = conversationService;
    }

    /**
     * 查询历史对话列表（分页）
     */
    @GetMapping
    public List<ConversationSummary> listConversations(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int size) {
        return conversationService.listConversations(page, size);
    }

    /**
     * 获取指定对话的消息列表
     */
    @GetMapping("/{conversationId}")
    public ResponseEntity<List<ConversationMessage>> getMessages(@PathVariable String conversationId) {
        List<ConversationMessage> messages = conversationService.getConversationMessages(conversationId);
        if (messages.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(messages);
    }

    /**
     * 清空指定对话
     */
    @DeleteMapping("/{conversationId}")
    public ResponseEntity<Map<String, Object>> clearConversation(@PathVariable String conversationId) {
        conversationService.clearConversation(conversationId);
        return ResponseEntity.ok(Map.of(
                "conversationId", conversationId,
                "message", "对话已清空"
        ));
    }

    /**
     * 导出对话记录（JSON 格式）
     */
    @GetMapping("/{conversationId}/export")
    public ResponseEntity<Map<String, Object>> exportConversation(@PathVariable String conversationId) {
        List<ConversationMessage> messages = conversationService.getConversationMessages(conversationId);
        if (messages.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(Map.of(
                "conversationId", conversationId,
                "messageCount", messages.size(),
                "messages", messages
        ));
    }
}
