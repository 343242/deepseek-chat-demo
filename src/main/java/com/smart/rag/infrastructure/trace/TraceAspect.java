package com.smart.rag.infrastructure.trace;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 链路追踪切面 —— 拦截 {@link TracedStep} 标注的方法，把步骤明细异步写入 {@code trace_event}。
 * <p>
 * 横切关注点：可服务于 RAG / Agent / Chat 任意链路。
 * <p>
 * <b>信息提取策略</b>（按方法参数和返回值类型自适应）：
 * <ul>
 *   <li><b>sessionId/userId</b>：优先从参数中的 {@code ToolWorkspace}（Agent 路径，反射调用 getSessionId/getUserId）取；
 *       其次从 {@code StrategyExecutionContext}（Chat 路径，反射调用 conversationId/userId）取；
 *       最后从 MDC {@code ragSessionId} 兜底（Chat 路径入口注入）。</li>
 *   <li><b>traceId</b>：从 MDC {@code traceId} 取（micrometer 自动注入）。</li>
 *   <li><b>返回值</b>：
 *     <ul>
 *       <li>Agent 路径返回 {@code String}（ToolResult JSON）：反序列化提取 success/documents/documentCount。</li>
 *       <li>Chat 路径返回 {@code List<Document>}：提取文档元数据。</li>
 *       <li>其他类型（如 {@code ChatRefResult}）：尝试从参数找 {@code List<Document>}。</li>
 *     </ul>
 *   </li>
 * </ul>
 * <p>
 * <b>容错</b>：任何提取异常都不影响主流程（catch + 降级只记基础字段）。
 */
@Aspect
@Component
public class TraceAspect {

    private static final Logger log = LoggerFactory.getLogger(TraceAspect.class);
    private static final String MDC_TRACE_ID = "traceId";
    /** Chat 路径入口（AbstractModeStrategy）把 conversationId 放入 MDC，供下游切面兜底取 sessionId */
    private static final String MDC_SESSION_ID = "ragSessionId";
    /** MDC key：检索路径模式（入口注入，供 PATH_RECALL 等非 AOP 路径兜底取 mode） */
    private static final String MDC_MODE = "ragMode";

    private final TraceRecorder recorder;
    private final ObjectMapper objectMapper;
    private final List<TraceContextProvider> contextProviders;

    public TraceAspect(TraceRecorder recorder, ObjectMapper objectMapper,
                       List<TraceContextProvider> contextProviders) {
        this.recorder = recorder;
        this.objectMapper = objectMapper;
        this.contextProviders = contextProviders;
    }

    @Around("@annotation(traced)")
    public Object traceStep(ProceedingJoinPoint pjp, TracedStep traced) throws Throwable {
        long start = System.currentTimeMillis();
        Object[] args = pjp.getArgs();
        StepContext ctx = extractContext(args);
        String stepType = traced.value();
        String toolName = pjp.getSignature().getDeclaringType().getSimpleName();

        Throwable error = null;
        Object result = null;
        try {
            result = pjp.proceed();
            return result;
        } catch (Throwable e) {
            error = e;
            throw e;
        } finally {
            long durationMs = System.currentTimeMillis() - start;
            try {
                recordStep(ctx, stepType, toolName, args, result, error, durationMs);
            } catch (Exception e) {
                // 追踪本身出错不影响主流程（异常已 throw 或结果已 return）
                log.debug("Trace recording failed for step={} session={}",
                        stepType, ctx.sessionId, e);
            }
        }
    }

    // === 上下文提取 ===
    // 通过 TraceContextProvider SPI 识别业务类型（ToolWorkspace / StrategyExecutionContext），
    // 避免本包硬依赖业务包（InfrastructureBoundaryTest 架构约束）。
    // provider 由业务模块实现并注入（DIP），业务类重命名不会让本切面静默失效。

    private StepContext extractContext(Object[] args) {
        String sessionId = null;
        Long userId = null;
        String inputQuery = null;
        String mode = null;
        if (args != null) {
            for (Object arg : args) {
                if (arg == null) continue;
                // 优先委托给已注册的 provider 提取 sessionId/userId
                if (sessionId == null || userId == null || mode == null) {
                    for (TraceContextProvider provider : contextProviders) {
                        if (provider.supports(arg)) {
                            if (mode == null) mode = provider.mode();
                            if (sessionId == null) sessionId = provider.extractSessionId(arg);
                            if (userId == null) userId = provider.extractUserId(arg);
                            if (sessionId != null && userId != null) break;
                        }
                    }
                }
                // 首个非空 String 视为输入 query（QueryRewriteTool/VectorSearchTool 等首参都是 query）
                if (arg instanceof String s && inputQuery == null && !s.isBlank()) {
                    inputQuery = s;
                }
            }
        }
        // 兜底：sessionId 未知时从 MDC 取（Chat 路径入口注入）；仍未知用 "unknown"（NOT NULL 约束）
        if (sessionId == null || sessionId.isBlank()) {
            sessionId = org.slf4j.MDC.get(MDC_SESSION_ID);
        }
        if (sessionId == null || sessionId.isBlank()) {
            sessionId = "unknown";
        }
        // mode 兜底：provider 未命中时从 MDC 取（入口注入）
        if (mode == null) {
            String mdcMode = org.slf4j.MDC.get(MDC_MODE);
            mode = (mdcMode != null && !mdcMode.isBlank()) ? mdcMode : TraceContextProvider.MODE_UNKNOWN;
        }
        return new StepContext(sessionId, userId, inputQuery, mode);
    }

    // === 结果记录 ===

    private void recordStep(StepContext ctx, String stepType, String toolName,
                            Object[] args, @Nullable Object result,
                            @Nullable Throwable error, long durationMs) {
        boolean success = error == null;
        String inputSummary = ctx.inputQuery;
        String outputSummary = null;
        Integer docCount = null;
        Double topScore = null;
        List<Map<String, Object>> documents = null;

        if (result instanceof String json) {
            // Agent 路径：ToolResult JSON
            ToolResultInfo info = parseToolResult(json);
            outputSummary = info.summary;
            docCount = info.docCount;
            documents = info.documents;
            topScore = info.topScore;
        } else if (result instanceof List<?> list) {
            // Chat 路径：返回值是 List<Document>
            List<Map<String, Object>> docs = extractFromDocumentList(list);
            documents = docs;
            docCount = docs.size();
            topScore = extractTopScore(docs);
        } else {
            // 未知返回类型（如 ChatRefResult）：尝试从参数找 List<Document>（CONTEXT_ASSEMBLY 场景：collect 的入参就是召回文档）
            List<Map<String, Object>> docs = findDocumentListInArgs(args);
            if (docs != null) {
                documents = docs;
                docCount = docs.size();
                topScore = extractTopScore(docs);
            }
        }

        recorder.record(
                mdcTraceIdOrNull(),
                ctx.sessionId,
                ctx.userId,
                stepType,
                ctx.mode,
                toolName,
                success,
                durationMs,
                inputSummary,
                outputSummary,
                docCount,
                topScore,
                documents
        );
    }

    /** 在方法参数中查找 List<Document>（CONTEXT_ASSEMBLY 场景：collect 的入参就是召回文档） */
    private @Nullable List<Map<String, Object>> findDocumentListInArgs(Object[] args) {
        if (args == null) return null;
        for (Object arg : args) {
            if (arg instanceof List<?> list && !list.isEmpty()) {
                List<Map<String, Object>> docs = extractFromDocumentList(list);
                if (!docs.isEmpty()) return docs;
            }
        }
        return null;
    }

    // === ToolResult JSON 解析（Agent 路径返回值） ===

    private ToolResultInfo parseToolResult(String json) {
        ToolResultInfo info = new ToolResultInfo();
        try {
            JsonNode root = objectMapper.readTree(json);
            info.summary = textOrNull(root.get("summary"));
            JsonNode docCountNode = root.get("documentCount");
            if (docCountNode != null && !docCountNode.isNull()) {
                info.docCount = docCountNode.asInt();
            }
            JsonNode docsNode = root.get("documents");
            if (docsNode != null && docsNode.isArray() && docsNode.size() > 0) {
                List<Map<String, Object>> docs = new ArrayList<>(docsNode.size());
                double maxScore = Double.NEGATIVE_INFINITY;
                for (JsonNode doc : docsNode) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    putIfPresent(m, "chunkId", doc.get("chunkId"));
                    putIfPresent(m, "documentId", doc.get("documentId"));
                    putIfPresent(m, "fileName", doc.get("fileName"));
                    putIfPresent(m, "page", doc.get("page"));
                    putIfPresent(m, "source", doc.get("source"));
                    JsonNode scoreNode = doc.get("score");
                    if (scoreNode != null && !scoreNode.isNull()) {
                        double sc = scoreNode.asDouble();
                        m.put("score", sc);
                        if (sc > maxScore) maxScore = sc;
                    }
                    docs.add(m);
                }
                info.documents = docs;
                if (maxScore != Double.NEGATIVE_INFINITY) info.topScore = maxScore;
            }
        } catch (Exception e) {
            log.debug("Failed to parse ToolResult JSON for trace", e);
        }
        return info;
    }

    // === Spring AI Document 列表提取（Chat 路径返回值） ===

    private List<Map<String, Object>> extractFromDocumentList(List<?> list) {
        List<Map<String, Object>> docs = new ArrayList<>(list.size());
        for (Object item : list) {
            if (item == null) continue;
            // Spring AI Document：getId() / getMetadata() / getScore() / getText()
            Map<String, Object> m = new LinkedHashMap<>();
            try {
                var docClass = item.getClass();
                var getId = docClass.getMethod("getId");
                Object id = getId.invoke(item);
                if (id != null) m.put("chunkId", id.toString());
                var getMetadata = docClass.getMethod("getMetadata");
                Object meta = getMetadata.invoke(item);
                if (meta instanceof Map<?, ?> metaMap) {
                    putMeta(m, metaMap, "documentId");
                    putMeta(m, metaMap, "fileName");
                    putMeta(m, metaMap, "page_number", "page");
                    putMeta(m, metaMap, "rrfScore", "score");
                    putMeta(m, metaMap, "rerankScore", "score");
                    putMeta(m, metaMap, "sources");
                    // rrfScore 优先，否则用 rerankScore
                }
                var getScore = docClass.getMethod("getScore");
                Object score = getScore.invoke(item);
                if (score != null && !m.containsKey("score")) {
                    m.put("score", ((Number) score).doubleValue());
                }
            } catch (Exception e) {
                // 非 Document 类型或反射失败，跳过该元素
                continue;
            }
            docs.add(m);
        }
        return docs;
    }

    private void putMeta(Map<String, Object> m, Map<?, ?> meta, String metaKey) {
        putMeta(m, meta, metaKey, metaKey);
    }

    private void putMeta(Map<String, Object> m, Map<?, ?> meta, String metaKey, String outKey) {
        Object v = meta.get(metaKey);
        if (v != null) m.put(outKey, v);
    }

    private Double extractTopScore(List<Map<String, Object>> docs) {
        double max = Double.NEGATIVE_INFINITY;
        for (Map<String, Object> d : docs) {
            Object s = d.get("score");
            if (s instanceof Number n) {
                double dv = n.doubleValue();
                if (dv > max) max = dv;
            }
        }
        return max == Double.NEGATIVE_INFINITY ? null : max;
    }

    private void putIfPresent(Map<String, Object> m, String key, JsonNode node) {
        if (node != null && !node.isNull()) {
            Object v = node.isNumber() ? node.asDouble() : node.asText();
            m.put(key, v);
        }
    }

    private @Nullable String textOrNull(JsonNode node) {
        return node == null || node.isNull() ? null : node.asText();
    }

    private @Nullable String mdcTraceIdOrNull() {
        return org.slf4j.MDC.get(MDC_TRACE_ID);
    }

    // === 内部数据载体 ===

    private record StepContext(String sessionId, Long userId, @Nullable String inputQuery, String mode) {}

    private static class ToolResultInfo {
        @Nullable String summary;
        @Nullable Integer docCount;
        @Nullable List<Map<String, Object>> documents;
        @Nullable Double topScore;
    }
}
