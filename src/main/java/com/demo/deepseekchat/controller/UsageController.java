package com.demo.deepseekchat.controller;

import com.demo.deepseekchat.model.dto.TokenUsageDTO;
import com.demo.deepseekchat.model.dto.UsageStats;
import com.demo.deepseekchat.service.UsageService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 用量统计 API
 * <p>
 * GET /api/usage/records               - 查询用量记录明细（按模型或对话）
 * GET /api/usage/stats/model           - 按模型聚合统计
 * GET /api/usage/stats/conversation    - 按对话聚合统计
 */
@RestController
@RequestMapping("/api/usage")
public class UsageController {

    private final UsageService usageService;

    public UsageController(UsageService usageService) {
        this.usageService = usageService;
    }

    /**
     * 查询用量记录明细
     *
     * @param model        按模型过滤（可选，与 conversation 二选一）
     * @param conversation 按对话过滤（可选，与 model 二选一）
     */
    @GetMapping("/records")
    public List<TokenUsageDTO> getRecords(
            @RequestParam(required = false) String model,
            @RequestParam(required = false) String conversation) {

        if (conversation != null && !conversation.isBlank()) {
            return usageService.getByConversation(conversation);
        }
        if (model != null && !model.isBlank()) {
            return usageService.getByModel(model);
        }
        throw new IllegalArgumentException("请指定 model 或 conversation 参数");
    }

    /**
     * 按模型聚合统计
     *
     * @param model     可选，指定模型过滤
     * @param startTime 可选，起始时间
     * @param endTime   可选，结束时间
     */
    @GetMapping("/stats/model")
    public List<UsageStats> statsByModel(
            @RequestParam(required = false) String model,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startTime,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endTime) {
        return usageService.aggregateByModel(model, startTime, endTime);
    }

    /**
     * 按对话聚合统计
     *
     * @param conversation 可选，指定对话过滤
     * @param startTime    可选，起始时间
     * @param endTime      可选，结束时间
     */
    @GetMapping("/stats/conversation")
    public List<UsageStats> statsByConversation(
            @RequestParam(required = false) String conversation,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startTime,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endTime) {
        return usageService.aggregateByConversation(conversation, startTime, endTime);
    }
}
