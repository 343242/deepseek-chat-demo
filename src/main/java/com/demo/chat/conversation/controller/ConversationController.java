package com.demo.chat.conversation.controller;

import com.demo.chat.common.response.GlobalResponse;
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
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 会话管理 API
 * <p>
 * 所有接口自动绑定当前登录用户，用户只能查看和管理自己的会话。
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
    public GlobalResponse<List<ConversationSummary>> list(
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(500) int size,
            @RequestParam(required = false) String status) {
        Long userId = SecurityUtils.getCurrentUserId();
        return GlobalResponse.ok(conversationService.list(userId, status, page, size));
    }

    @GetMapping("/{conversationId}")
    public GlobalResponse<ConversationDetail> getDetail(@PathVariable String conversationId) {
        Long userId = SecurityUtils.getCurrentUserId();
        String isolatedId = ConversationIdUtil.buildIsolatedId(userId, conversationId);
        return GlobalResponse.ok(conversationService.getDetail(userId, isolatedId));
    }

    @GetMapping("/{conversationId}/messages")
    public GlobalResponse<List<MessageVO>> listMessages(@PathVariable String conversationId) {
        Long userId = SecurityUtils.getCurrentUserId();
        String isolatedId = ConversationIdUtil.buildIsolatedId(userId, conversationId);
        return GlobalResponse.ok(conversationService.listMessages(userId, isolatedId));
    }

    @PutMapping("/{conversationId}")
    public GlobalResponse<Void> update(
            @PathVariable String conversationId,
            @Valid @RequestBody ConversationUpdateRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        String isolatedId = ConversationIdUtil.buildIsolatedId(userId, conversationId);
        conversationService.update(userId, isolatedId, request);
        return GlobalResponse.ok("会话已更新");
    }

    @DeleteMapping("/{conversationId}")
    public GlobalResponse<Void> delete(@PathVariable String conversationId) {
        Long userId = SecurityUtils.getCurrentUserId();
        String isolatedId = ConversationIdUtil.buildIsolatedId(userId, conversationId);
        conversationService.delete(userId, isolatedId);
        return GlobalResponse.ok("会话已删除");
    }
}
