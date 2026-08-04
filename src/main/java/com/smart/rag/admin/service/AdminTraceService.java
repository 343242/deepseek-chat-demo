package com.smart.rag.admin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smart.rag.admin.dto.AgentEventVO;
import com.smart.rag.admin.dto.TraceEventVO;
import com.smart.rag.agent.event.AgentSessionEvent;
import com.smart.rag.agent.event.AgentEventMapper;
import com.smart.rag.infrastructure.request.PageRequest;
import com.smart.rag.infrastructure.response.PagedResult;
import com.smart.rag.infrastructure.trace.TraceEvent;
import com.smart.rag.infrastructure.trace.TraceMapper;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 管理员链路追踪查询服务（只读，全局视角，不按当前用户隔离）。
 * <p>
 * 两个查询维度：
 * <ul>
 *   <li>{@link #listTraces} — trace_event（检索步骤明细：改写/检索/融合/重排等）</li>
 *   <li>{@link #listAgentEvents} — agent_session_event（Agent 事件流：意图/自省/工具/护栏等）</li>
 * </ul>
 * 均支持按 sessionId / userId 过滤下钻，不传则全局浏览。管理员审计场景用页码分页。
 */
@Service
public class AdminTraceService {

    private static final Logger log = LoggerFactory.getLogger(AdminTraceService.class);
    private static final TypeReference<List<Map<String, Object>>> DOCS_TYPE = new TypeReference<>() {};

    private final TraceMapper traceMapper;
    private final AgentEventMapper agentEventMapper;
    private final ObjectMapper objectMapper;

    public AdminTraceService(TraceMapper traceMapper, AgentEventMapper agentEventMapper,
                             ObjectMapper objectMapper) {
        this.traceMapper = traceMapper;
        this.agentEventMapper = agentEventMapper;
        this.objectMapper = objectMapper;
    }

    /**
     * 分页查询 trace_event（检索步骤明细）。
     * <p>
     * documents JSONB 在返回前剥离 {@code content} 字段（脱敏：只保留 id/元数据）。
     *
     * @param page      页码（≥1）
     * @param size      每页大小（1-100）
     * @param sessionId 可选会话过滤
     * @param userId    可选用户过滤
     * @param stepType  可选步骤类型过滤（QUERY_REWRITE/VECTOR_SEARCH/...）
     * @param success   可选成功/失败过滤
     */
    public PagedResult<TraceEventVO> listTraces(int page, int size,
                                                @Nullable String sessionId,
                                                @Nullable Long userId,
                                                @Nullable String stepType,
                                                @Nullable Boolean success) {
        PageRequest req = PageRequest.of(page, size);

        LambdaQueryWrapper<TraceEvent> wrapper = new LambdaQueryWrapper<>();
        if (sessionId != null && !sessionId.isBlank()) {
            wrapper.eq(TraceEvent::getSessionId, sessionId);
        }
        if (userId != null) {
            wrapper.eq(TraceEvent::getUserId, userId);
        }
        if (stepType != null && !stepType.isBlank()) {
            wrapper.eq(TraceEvent::getStepType, stepType);
        }
        if (success != null) {
            wrapper.eq(TraceEvent::isSuccess, success);
        }
        wrapper.orderByDesc(TraceEvent::getCreatedAt);

        Page<TraceEvent> result = traceMapper.selectPage(req.toPage(), wrapper);
        return PagedResult.of(result, e -> TraceEventVO.of(e, sanitizeDocuments(e.getDocuments())));
    }

    /**
     * 分页查询 agent_session_event（Agent 事件流）。
     * <p>
     * data JSONB 已天然脱敏（设计时未存正文），直接透传。
     *
     * @param page      页码（≥1）
     * @param size      每页大小（1-100）
     * @param sessionId 可选会话过滤
     * @param userId    可选用户过滤
     * @param eventType 可选事件类型过滤（INTENT_CLASSIFIED/TOOL_CALLED/...）
     */
    public PagedResult<AgentEventVO> listAgentEvents(int page, int size,
                                                     @Nullable String sessionId,
                                                     @Nullable Long userId,
                                                     @Nullable String eventType) {
        PageRequest req = PageRequest.of(page, size);

        LambdaQueryWrapper<AgentSessionEvent> wrapper = new LambdaQueryWrapper<>();
        if (sessionId != null && !sessionId.isBlank()) {
            wrapper.eq(AgentSessionEvent::getSessionId, sessionId);
        }
        if (userId != null) {
            wrapper.eq(AgentSessionEvent::getUserId, userId);
        }
        // eventType 有 typeHandler（仅结果映射时生效），WHERE 条件传字符串值匹配 VARCHAR 列
        if (eventType != null && !eventType.isBlank()) {
            wrapper.eq(AgentSessionEvent::getEventType, eventType);
        }
        wrapper.orderByDesc(AgentSessionEvent::getCreatedAt);

        Page<AgentSessionEvent> result = agentEventMapper.selectPage(req.toPage(), wrapper);
        return PagedResult.of(result, AgentEventVO::of);
    }

    /**
     * 反序列化 documents JSONB 并剥离 {@code content} 字段（脱敏）。
     * <p>
     * 解析失败时返回 null（不阻断查询），仅 WARN 日志。
     */
    @Nullable
    List<Map<String, Object>> sanitizeDocuments(@Nullable String documentsJson) {
        if (documentsJson == null || documentsJson.isBlank()) {
            return null;
        }
        try {
            List<Map<String, Object>> docs = objectMapper.readValue(documentsJson, DOCS_TYPE);
            docs.forEach(doc -> doc.remove("content"));
            return docs;
        } catch (Exception e) {
            log.warn("Failed to parse/strip documents JSONB for trace sanitization: {}", e.getMessage());
            return null;
        }
    }
}
