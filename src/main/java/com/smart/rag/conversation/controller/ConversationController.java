package com.smart.rag.conversation.controller;

import com.smart.rag.infrastructure.response.GlobalResponse;
import com.smart.rag.infrastructure.response.PagedResult;
import com.smart.rag.conversation.dto.ConversationCreateRequest;
import com.smart.rag.conversation.dto.ConversationDetail;
import com.smart.rag.conversation.dto.ConversationSummary;
import com.smart.rag.conversation.dto.ConversationUpdateRequest;
import com.smart.rag.conversation.dto.MessageCursorPage;
import com.smart.rag.conversation.service.ConversationService;
import com.smart.rag.infrastructure.web.util.SecurityUtils;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/**
 * 会话管理 API
 * <p>
 * 所有接口自动绑定当前登录用户，用户只能查看和管理自己的会话。
 * <p>
 * 路径变量 {@code conversationId} 一律为 isolated id（{@code u_{userId}_{raw}}），
 * 即列表接口返回的值；归属与存在性校验统一在 service 层 findAndVerify 完成。
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

    @PostMapping
    public GlobalResponse<ConversationSummary> create(@Valid @RequestBody ConversationCreateRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        return GlobalResponse.ok(conversationService.create(userId, request));
    }

    @GetMapping
    public GlobalResponse<PagedResult<ConversationSummary>> list(
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(500) int size,
            @RequestParam(required = false) String status) {
        Long userId = SecurityUtils.getCurrentUserId();
        return GlobalResponse.ok(conversationService.list(userId, status, page, size));
    }

    @GetMapping("/{conversationId}")
    public GlobalResponse<ConversationDetail> getDetail(@PathVariable String conversationId) {
        Long userId = SecurityUtils.getCurrentUserId();
        return GlobalResponse.ok(conversationService.getDetail(userId, conversationId));
    }

    @GetMapping("/{conversationId}/messages")
    public GlobalResponse<MessageCursorPage> listMessages(
            @PathVariable String conversationId,
            @RequestParam(value = "limit", defaultValue = "20") @Min(1) @Max(50) int limit,
            @RequestParam(value = "before", required = false) Long before) {
        Long userId = SecurityUtils.getCurrentUserId();
        return GlobalResponse.ok(conversationService.listMessagesPaged(userId, conversationId, before, limit));
    }

    @PostMapping("/{conversationId}/update")
    public GlobalResponse<Void> update(
            @PathVariable String conversationId,
            @Valid @RequestBody ConversationUpdateRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        conversationService.update(userId, conversationId, request);
        return GlobalResponse.ok("会话已更新");
    }

    @PostMapping("/{conversationId}/delete")
    public GlobalResponse<Void> delete(@PathVariable String conversationId) {
        Long userId = SecurityUtils.getCurrentUserId();
        conversationService.delete(userId, conversationId);
        return GlobalResponse.ok("会话已删除");
    }
}
