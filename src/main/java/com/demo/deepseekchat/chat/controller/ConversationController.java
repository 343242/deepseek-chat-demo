package com.demo.deepseekchat.chat.controller;

import com.demo.deepseekchat.chat.dto.ConversationMessage;
import com.demo.deepseekchat.chat.dto.ConversationSummary;
import com.demo.deepseekchat.chat.service.ConversationService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 对话管理 API（用户隔离版）
 * <p>
 * 所有接口自动绑定当前登录用户，用户只能查看和管理自己的对话记录。
 * <p>
 * GET    /api/conversations          - 查询当前用户的对话列表（分页）
 * GET    /api/conversations/{id}     - 获取当前用户指定对话的消息
 * DELETE /api/conversations/{id}     - 清空当前用户指定对话
 * GET    /api/conversations/{id}/export - 导出当前用户对话记录
 */
@RestController
@RequestMapping("/api/conversations")
@PreAuthorize("hasAuthority('conversation:manage')")
public class ConversationController {

    private final ConversationService conversationService;

    public ConversationController(ConversationService conversationService) {
        this.conversationService = conversationService;
    }

    @GetMapping
    public List<ConversationSummary> listConversations(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int size) {
        return conversationService.listConversations(page, size);
    }

    @GetMapping("/{conversationId}")
    public ResponseEntity<List<ConversationMessage>> getMessages(@PathVariable String conversationId) {
        List<ConversationMessage> messages = conversationService.getConversationMessages(conversationId);
        if (messages.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(messages);
    }

    @DeleteMapping("/{conversationId}")
    public ResponseEntity<Map<String, Object>> clearConversation(@PathVariable String conversationId) {
        conversationService.clearConversation(conversationId);
        return ResponseEntity.ok(Map.of(
                "conversationId", conversationId,
                "message", "对话已清空"
        ));
    }

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
