package com.smart.rag.chat.controller;

import com.smart.rag.chat.dto.TokenUsageDTO;
import com.smart.rag.chat.dto.UsageStats;
import com.smart.rag.chat.service.UsageService;
import com.smart.rag.common.response.GlobalResponse;
import com.smart.rag.security.util.SecurityUtils;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 用量统计 API（用户隔离版）
 */
@RestController
@RequestMapping("/api/v1/usage")
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
        return GlobalResponse.ok(usageService.getRecords(userId, conversation, model));
    }

    @GetMapping("/stats/model")
    public GlobalResponse<List<UsageStats>> statsByModel(
            @RequestParam(required = false) String model,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startTime,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endTime) {
        Long userId = SecurityUtils.getCurrentUserId();
        return GlobalResponse.ok(usageService.statsByModel(userId, model, startTime, endTime));
    }

    @GetMapping("/stats/conversation")
    public GlobalResponse<List<UsageStats>> statsByConversation(
            @RequestParam(required = false) String conversation,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startTime,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endTime) {
        Long userId = SecurityUtils.getCurrentUserId();
        return GlobalResponse.ok(usageService.statsByConversation(userId, conversation, startTime, endTime));
    }
}
