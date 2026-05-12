package com.demo.chat.chat.controller;

import com.demo.chat.chat.dto.TokenUsageDTO;
import com.demo.chat.chat.dto.UsageStats;
import com.demo.chat.chat.service.UsageService;
import com.demo.chat.common.errorcode.ErrorCode;
import com.demo.chat.common.response.GlobalResponse;
import com.demo.chat.conversation.util.ConversationIdUtil;
import com.demo.chat.exception.BusinessException;
import com.demo.chat.security.util.SecurityUtils;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 用量统计 API（用户隔离版）
 */
@RestController
@RequestMapping("/api/usage")
@PreAuthorize("hasAuthority('usage:view')")
public class UsageController {

    private final UsageService usageService;

    public UsageController(UsageService usageService) {
        this.usageService = usageService;
    }

    @GetMapping("/records")
    public GlobalResponse<List<TokenUsageDTO>> getRecords(
            @RequestParam(required = false) String model,
            @RequestParam(required = false) String conversation) {

        Long userId = SecurityUtils.getCurrentUserId();

        if (conversation != null && !conversation.isBlank()) {
            String isolatedId = ConversationIdUtil.buildIsolatedId(userId, conversation);
            return GlobalResponse.ok(usageService.getByConversation(isolatedId));
        }
        if (model != null && !model.isBlank()) {
            String prefix = ConversationIdUtil.buildLikePrefix(userId);
            return GlobalResponse.ok(usageService.getByModelAndUser(model, prefix));
        }
        throw new BusinessException(ErrorCode.USAGE_PARAM_MISSING);
    }

    @GetMapping("/stats/model")
    public GlobalResponse<List<UsageStats>> statsByModel(
            @RequestParam(required = false) String model,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startTime,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endTime) {
        Long userId = SecurityUtils.getCurrentUserId();
        String prefix = ConversationIdUtil.buildLikePrefix(userId);
        return GlobalResponse.ok(usageService.aggregateByModelForUser(model, prefix, startTime, endTime));
    }

    @GetMapping("/stats/conversation")
    public GlobalResponse<List<UsageStats>> statsByConversation(
            @RequestParam(required = false) String conversation,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startTime,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endTime) {
        Long userId = SecurityUtils.getCurrentUserId();
        if (conversation != null && !conversation.isBlank()) {
            String isolatedId = ConversationIdUtil.buildIsolatedId(userId, conversation);
            return GlobalResponse.ok(usageService.aggregateByConversation(isolatedId, startTime, endTime));
        }
        String prefix = ConversationIdUtil.buildLikePrefix(userId);
        return GlobalResponse.ok(usageService.aggregateByUserConversations(prefix, startTime, endTime));
    }
}
