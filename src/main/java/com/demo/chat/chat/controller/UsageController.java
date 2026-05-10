package com.demo.chat.chat.controller;

import com.demo.chat.chat.dto.TokenUsageDTO;
import com.demo.chat.chat.dto.UsageStats;
import com.demo.chat.chat.service.UsageService;
import com.demo.chat.chat.util.ConversationIdUtil;
import com.demo.chat.exception.BusinessException;
import com.demo.chat.security.util.SecurityUtils;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 用量统计 API（用户隔离版）
 * <p>
 * 所有查询自动绑定当前用户，用户只能查看自己的用量数据。
 * conversationId 参数传入原始 ID（不带 u_{userId}_ 前缀），由 Controller 层拼接隔离。
 * <p>
 * GET /api/usage/records               - 查询当前用户用量明细
 * GET /api/usage/stats/model           - 按模型聚合统计（当前用户）
 * GET /api/usage/stats/conversation    - 按对话聚合统计（当前用户）
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
    public List<TokenUsageDTO> getRecords(
            @RequestParam(required = false) String model,
            @RequestParam(required = false) String conversation) {

        Long userId = SecurityUtils.getCurrentUserId();

        if (conversation != null && !conversation.isBlank()) {
            String isolatedId = ConversationIdUtil.buildIsolatedId(userId, conversation);
            return usageService.getByConversation(isolatedId);
        }
        if (model != null && !model.isBlank()) {
            String prefix = ConversationIdUtil.buildLikePrefix(userId);
            return usageService.getByModelAndUser(model, prefix);
        }
        throw new BusinessException("请指定 model 或 conversation 参数");
    }

    @GetMapping("/stats/model")
    public List<UsageStats> statsByModel(
            @RequestParam(required = false) String model,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startTime,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endTime) {
        Long userId = SecurityUtils.getCurrentUserId();
        String prefix = ConversationIdUtil.buildLikePrefix(userId);
        return usageService.aggregateByModelForUser(model, prefix, startTime, endTime);
    }

    @GetMapping("/stats/conversation")
    public List<UsageStats> statsByConversation(
            @RequestParam(required = false) String conversation,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startTime,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endTime) {
        Long userId = SecurityUtils.getCurrentUserId();
        if (conversation != null && !conversation.isBlank()) {
            String isolatedId = ConversationIdUtil.buildIsolatedId(userId, conversation);
            return usageService.aggregateByConversation(isolatedId, startTime, endTime);
        }
        String prefix = ConversationIdUtil.buildLikePrefix(userId);
        return usageService.aggregateByUserConversations(prefix, startTime, endTime);
    }
}
