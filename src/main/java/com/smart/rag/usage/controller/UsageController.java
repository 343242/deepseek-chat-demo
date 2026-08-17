package com.smart.rag.usage.controller;

import com.smart.rag.infrastructure.llm.usage.UsageScene;
import com.smart.rag.infrastructure.request.PageRequest;
import com.smart.rag.infrastructure.response.GlobalResponse;
import com.smart.rag.infrastructure.response.PagedResult;
import com.smart.rag.infrastructure.web.util.SecurityUtils;
import com.smart.rag.usage.dto.TimelineGranularity;
import com.smart.rag.usage.dto.UsageEventDTO;
import com.smart.rag.usage.dto.UsageQueryFilter;
import com.smart.rag.usage.dto.UsageStatsDTO;
import com.smart.rag.usage.dto.UsageStatsDim;
import com.smart.rag.usage.dto.UsageStatsOrder;
import com.smart.rag.usage.dto.UsageStatsSort;
import com.smart.rag.usage.dto.UsageSummaryDTO;
import com.smart.rag.usage.dto.UsageTimelinePointDTO;
import com.smart.rag.usage.service.UsageEventService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 用量统计 API（V28 重写：显式 user 维度 + 时间桶 + 总计 + 分页）
 * <p>
 * 类级 {@code usage:view} 查本人维度；方法级 SpEL 限定跨用户访问：
 * 传 {@code userId} 查他人、或 {@code dim=USER} 全员聚合，均需 {@code usage:view:all}（仅 ADMIN 绑定）。
 */
@RestController
@RequestMapping("/api/usage")
@PreAuthorize("hasAuthority('usage:view')")
public class UsageController {

    private final UsageEventService usageEventService;

    public UsageController(UsageEventService usageEventService) {
        this.usageEventService = usageEventService;
    }

    /** 用量明细分页（不再强制 model/conversation 过滤条件） */
    @GetMapping("/records")
    @PreAuthorize("#userId == null or hasAuthority('usage:view:all')")
    public GlobalResponse<PagedResult<UsageEventDTO>> records(
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) UsageScene scene,
            @RequestParam(required = false) String model,
            @RequestParam(required = false) String conversation,
            @RequestParam(required = false) OffsetDateTime start,
            @RequestParam(required = false) OffsetDateTime end,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        UsageQueryFilter filter = selfOrDefault(userId, scene, model, conversation, start, end);
        return GlobalResponse.ok(usageEventService.getRecords(filter, PageRequest.of(page, size)));
    }

    /** 用量总计（请求数/成功率/token 求和/时长） */
    @GetMapping("/summary")
    @PreAuthorize("#userId == null or hasAuthority('usage:view:all')")
    public GlobalResponse<UsageSummaryDTO> summary(
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) UsageScene scene,
            @RequestParam(required = false) String model,
            @RequestParam(required = false) String conversation,
            @RequestParam(required = false) OffsetDateTime start,
            @RequestParam(required = false) OffsetDateTime end) {
        UsageQueryFilter filter = selfOrDefault(userId, scene, model, conversation, start, end);
        return GlobalResponse.ok(usageEventService.getSummary(filter));
    }

    /** 时间桶聚合（day/month，空桶补零，供图表直连；缺省近 30 天） */
    @GetMapping("/timeline")
    @PreAuthorize("#userId == null or hasAuthority('usage:view:all')")
    public GlobalResponse<List<UsageTimelinePointDTO>> timeline(
            @RequestParam(defaultValue = "DAY") TimelineGranularity granularity,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) UsageScene scene,
            @RequestParam(required = false) String model,
            @RequestParam(required = false) OffsetDateTime start,
            @RequestParam(required = false) OffsetDateTime end) {
        UsageQueryFilter filter = selfOrDefault(userId, scene, model, null, start, end);
        return GlobalResponse.ok(usageEventService.getTimeline(granularity, filter));
    }

    /** 分组聚合（dim=model|scene|user，可排序；dim=USER 跨用户聚合仅管理员） */
    @GetMapping("/stats")
    @PreAuthorize("(#userId == null and #dim != T(com.smart.rag.usage.dto.UsageStatsDim).USER) or hasAuthority('usage:view:all')")
    public GlobalResponse<List<UsageStatsDTO>> stats(
            @RequestParam(defaultValue = "MODEL") UsageStatsDim dim,
            @RequestParam(defaultValue = "TOTAL_TOKENS") UsageStatsSort sort,
            @RequestParam(defaultValue = "DESC") UsageStatsOrder order,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) UsageScene scene,
            @RequestParam(required = false) String model,
            @RequestParam(required = false) OffsetDateTime start,
            @RequestParam(required = false) OffsetDateTime end) {
        UsageQueryFilter filter = selfOrDefault(userId, scene, model, null, start, end);
        return GlobalResponse.ok(usageEventService.getStats(dim, sort, order, filter));
    }

    /** 目标用户缺省为当前登录用户（本人维度） */
    private static UsageQueryFilter selfOrDefault(Long userId, UsageScene scene, String model,
                                                  String conversation,
                                                  OffsetDateTime start, OffsetDateTime end) {
        return new UsageQueryFilter(userId != null ? userId : SecurityUtils.getCurrentUserId(),
            scene != null ? scene.name() : null, model, conversation, start, end);
    }
}
