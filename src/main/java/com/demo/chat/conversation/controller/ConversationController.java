package com.demo.chat.conversation.controller;

import com.demo.chat.conversation.dto.ConversationCreateRequest;
import com.demo.chat.conversation.dto.ConversationDetail;
import com.demo.chat.conversation.dto.ConversationSummary;
import com.demo.chat.conversation.dto.ConversationUpdateRequest;
import com.demo.chat.conversation.dto.MessageVO;
import com.demo.chat.conversation.service.ConversationService;
import com.demo.chat.conversation.util.ConversationIdUtil;
import com.demo.chat.security.util.SecurityUtils;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 会话管理 API
 * <p>
 * 所有接口自动绑定当前登录用户，用户只能查看和管理自己的会话。
 * <p>
 * POST   /api/conversations                      - 创建新会话
 * GET    /api/conversations                      - 查询会话列表（分页，支持状态过滤）
 * GET    /api/conversations/{conversationId}     - 获取会话详情
 * GET    /api/conversations/{conversationId}/messages - 获取消息列表（树形）
 * PUT    /api/conversations/{conversationId}     - 更新会话（标题/置顶/归档）
 * DELETE /api/conversations/{conversationId}     - 删除会话（软删除）
 */
@RestController
@RequestMapping("/api/conversations")
@PreAuthorize("hasAuthority('conversation:manage')")
@Validated
public class ConversationController {

    private final ConversationService conversationService;

    public ConversationController(ConversationService conversationService) {
        this.conversationService = conversationService;
    }

    /**
     * 创建新会话
     */
    @PostMapping
    public ConversationSummary create(@Valid @RequestBody ConversationCreateRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        return conversationService.create(userId, request);
    }

    /**
     * 查询会话列表（分页，支持状态过滤）
     *
     * @param page   页码（默认 1）
     * @param size   每页大小（默认 50，最大 500）
     * @param status 状态过滤（可选：ACTIVE / ARCHIVED）
     */
    @GetMapping
    public List<ConversationSummary> list(
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(500) int size,
            @RequestParam(required = false) String status) {
        Long userId = SecurityUtils.getCurrentUserId();
        return conversationService.list(userId, status, page, size);
    }

    /**
     * 获取会话详情（含消息树）
     *
     * @param conversationId 原始对话 ID（自动拼接 userId 前缀）
     */
    @GetMapping("/{conversationId}")
    public ResponseEntity<ConversationDetail> getDetail(@PathVariable String conversationId) {
        Long userId = SecurityUtils.getCurrentUserId();
        String isolatedId = ConversationIdUtil.buildIsolatedId(userId, conversationId);
        ConversationDetail detail = conversationService.getDetail(userId, isolatedId);
        return ResponseEntity.ok(detail);
    }

    /**
     * 获取会话的消息列表（树形结构）
     */
    @GetMapping("/{conversationId}/messages")
    public ResponseEntity<List<MessageVO>> listMessages(@PathVariable String conversationId) {
        Long userId = SecurityUtils.getCurrentUserId();
        String isolatedId = ConversationIdUtil.buildIsolatedId(userId, conversationId);
        List<MessageVO> messages = conversationService.listMessages(userId, isolatedId);
        if (messages.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(messages);
    }

    /**
     * 更新会话（标题/置顶/归档）
     */
    @PutMapping("/{conversationId}")
    public ResponseEntity<Map<String, Object>> update(
            @PathVariable String conversationId,
            @Valid @RequestBody ConversationUpdateRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        String isolatedId = ConversationIdUtil.buildIsolatedId(userId, conversationId);
        conversationService.update(userId, isolatedId, request);
        return ResponseEntity.ok(Map.of(
                "conversationId", conversationId,
                "message", "会话已更新"
        ));
    }

    /**
     * 删除会话（软删除 + 清空消息）
     */
    @DeleteMapping("/{conversationId}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable String conversationId) {
        Long userId = SecurityUtils.getCurrentUserId();
        String isolatedId = ConversationIdUtil.buildIsolatedId(userId, conversationId);
        conversationService.delete(userId, isolatedId);
        return ResponseEntity.ok(Map.of(
                "conversationId", conversationId,
                "message", "会话已删除"
        ));
    }
}
