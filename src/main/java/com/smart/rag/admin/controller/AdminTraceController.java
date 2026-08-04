package com.smart.rag.admin.controller;

import com.smart.rag.admin.dto.AgentEventVO;
import com.smart.rag.admin.dto.TraceEventVO;
import com.smart.rag.admin.service.AdminTraceService;
import com.smart.rag.infrastructure.response.GlobalResponse;
import com.smart.rag.infrastructure.response.PagedResult;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理员链路追踪控制器（只读，审计/排障用）。
 * <p>
 * 两个端点分别暴露 trace_event（检索步骤明细）和 agent_session_event（Agent 事件流），
 * 均支持按 sessionId/userId 下钻或全局浏览。trace_event 的 documents 已脱敏（剥离正文）。
 */
@RestController
@RequestMapping("/api/admin")
@PreAuthorize("hasAuthority('trace:view')")
@Validated
public class AdminTraceController {

    private final AdminTraceService traceService;

    public AdminTraceController(AdminTraceService traceService) {
        this.traceService = traceService;
    }

    /**
     * 检索步骤明细（trace_event）分页查询。
     * <p>
     * 不传过滤参数 → 全局最新步骤；传 sessionId → 还原某次请求的完整链路。
     */
    @GetMapping("/traces")
    public GlobalResponse<PagedResult<TraceEventVO>> listTraces(
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(required = false) String sessionId,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) String stepType,
            @RequestParam(required = false) Boolean success) {
        return GlobalResponse.ok(traceService.listTraces(page, size, sessionId, userId, stepType, success));
    }

    /**
     * Agent 事件流（agent_session_event）分页查询。
     * <p>
     * 不传过滤参数 → 全局最新事件；传 sessionId → 还原某次 Agent 请求的事件流。
     */
    @GetMapping("/agent-events")
    public GlobalResponse<PagedResult<AgentEventVO>> listAgentEvents(
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(required = false) String sessionId,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) String eventType) {
        return GlobalResponse.ok(traceService.listAgentEvents(page, size, sessionId, userId, eventType));
    }
}
