package com.smart.rag.agent.workspace;

import com.smart.rag.agent.dto.IntermediateAnswer;
import com.smart.rag.agent.dto.SelfReflection;
import com.smart.rag.mode.AgentIntent;
import com.smart.rag.rag.retrieval.RetrievedDocument;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * ToolWorkspace — Agent 请求内的共享工作空间。
 * <p>
 * 所有工具通过 {@link com.smart.rag.agent.tool.callback.AgentToolCallbackFactory}
 * 注册为 Spring AI FunctionToolCallback，由 ToolCallAdvisor 串行调度。
 * <p>
 * <b>并发契约</b>：工具调用在单请求内串行执行，本类理论上不需要线程安全。
 * 但使用并发安全集合作为防御性编程，以防 Spring AI 未来改为并行工具调用。
 * <p>
 * 生命周期：ToolWorkspaceFactory.create() -> 闭包捕获 -> 请求结束 GC 回收
 */
public class ToolWorkspace implements com.smart.rag.mode.WorkspaceInfo {

    private static final Logger log = LoggerFactory.getLogger(ToolWorkspace.class);

    // ── 容量上限 ───────────────────────────────────────────

    /** 最大检索文档数 */
    private static final int MAX_RETRIEVED_DOCS = 50;
    /** 最大中间答案数 */
    private static final int MAX_INTERMEDIATE_ANSWERS = 10;
    /** 最大总内容字符数（约 25K tokens） */
    private static final int MAX_TOTAL_CONTENT_CHARS = 100_000;
    /** 单文档内容截断长度 */
    private static final int SINGLE_DOC_TRUNCATE_CHARS = 10_000;

    private final long userId;
    private final @Nullable Long teamId;
    /** 会话标识（复用 conversationId），用于链路追踪关联（trace_event.session_id） */
    private final @Nullable String sessionId;
    private @Nullable AgentIntent intent;
    private List<String> subQueries = new CopyOnWriteArrayList<>();
    private final Set<Integer> completedSubQueries = ConcurrentHashMap.newKeySet();
    private int round = 0;
    private final List<RetrievedDocument> retrievedDocs = new CopyOnWriteArrayList<>();
    private final List<String> rewrittenQueries = new CopyOnWriteArrayList<>();
    private final List<SelfReflection> selfReflections = new CopyOnWriteArrayList<>();
    private final List<IntermediateAnswer> intermediateAnswers = new CopyOnWriteArrayList<>();
    /** 已见 chunk ID 集合（去重依据；字段名沿用历史，内容为 RetrievedDocument.chunkId()） */
    private final Set<String> seenDocIds = ConcurrentHashMap.newKeySet();
    /** 全局稳定引用编号计数器（[n]），独立于 list size 以扛 replace/dedup 不烧号 */
    private final AtomicInteger refCounter = new AtomicInteger(0);

    public ToolWorkspace(long userId, @Nullable Long teamId) {
        this(userId, teamId, null);
    }

    public ToolWorkspace(long userId, @Nullable Long teamId, @Nullable String sessionId) {
        this.userId = userId;
        this.teamId = teamId;
        this.sessionId = sessionId;
    }

    // ── 查询分解 ──────────────────────────────────────────

    /** 设置意图分类结果 */
    public void setIntent(AgentIntent intent) {
        this.intent = intent;
    }

    /** 获取意图 */
    public @Nullable AgentIntent getIntent() {
        return intent;
    }

    /** 设置子问题列表 */
    public void setSubQueries(List<String> subQueries) {
        this.subQueries = new CopyOnWriteArrayList<>(subQueries);
    }

    /** 获取子问题列表 */
    public List<String> getSubQueries() {
        return Collections.unmodifiableList(subQueries);
    }

    /** 标记子问题已完成 */
    public void markSubQueryCompleted(int index) {
        completedSubQueries.add(index);
    }

    /** 获取未完成的子问题索引 */
    public List<Integer> getPendingSubQueryIndices() {
        List<Integer> pending = new ArrayList<>();
        for (int i = 0; i < subQueries.size(); i++) {
            if (!completedSubQueries.contains(i)) {
                pending.add(i);
            }
        }
        return pending;
    }

    // ── 检索结果 ──────────────────────────────────────────

    /** 获取所有已检索文档 */
    public List<RetrievedDocument> getRetrievedDocs() {
        return Collections.unmodifiableList(retrievedDocs);
    }

    /** 追加检索文档（带去重 + 容量上限 + 内容预算控制）；入列的每条分配稳定 [n] 编号 */
    public void addRetrievedDocs(List<RetrievedDocument> docs) {
        for (RetrievedDocument doc : docs) {
            if (retrievedDocs.size() >= MAX_RETRIEVED_DOCS || totalContentChars() >= MAX_TOTAL_CONTENT_CHARS) {
                log.warn("ToolWorkspace capacity reached: docs={}, contentChars={}",
                    retrievedDocs.size(), totalContentChars());
                break;
            }
            if (seenDocIds.contains(doc.chunkId())) {
                continue; // dedup 跳过，不分配编号（不烧号）
            }
            RetrievedDocument truncated = truncateIfNeeded(doc)
                .withRefNumber(refCounter.incrementAndGet());
            retrievedDocs.add(truncated);
            seenDocIds.add(doc.chunkId());
        }
    }

    /**
     * 替换所有检索文档（用于 rerank、parentDocLookup 等替换场景，带容量上限）。
     * <p>
     * 保留稳定编号：旧 chunkId 已有 [n] 则复用，新 chunkId 续号——rerank 重排不改变既有引用编号。
     */
    public void replaceRetrievedDocs(List<RetrievedDocument> docs) {
        Map<String, Integer> oldRefNumbers = new HashMap<>();
        for (RetrievedDocument d : retrievedDocs) {
            oldRefNumbers.put(d.chunkId(), d.refNumber());
        }
        retrievedDocs.clear();
        seenDocIds.clear();
        int count = 0;
        for (RetrievedDocument doc : docs) {
            if (count >= MAX_RETRIEVED_DOCS || totalContentChars() >= MAX_TOTAL_CONTENT_CHARS) {
                log.warn("ToolWorkspace replace capacity reached: docs={}, contentChars={}",
                    count, totalContentChars());
                break;
            }
            // 注意：不能用 getOrDefault(k, refCounter.incrementAndGet())——Java 参数 eager 求值
            // 会导致 refCounter 每次循环都递增（即使 key 存在），续号错误。必须显式判断。
            int n = oldRefNumbers.containsKey(doc.chunkId())
                ? oldRefNumbers.get(doc.chunkId())
                : refCounter.incrementAndGet();
            RetrievedDocument truncated = truncateIfNeeded(doc).withRefNumber(n);
            retrievedDocs.add(truncated);
            seenDocIds.add(doc.chunkId());
            count++;
        }
    }

    /** 获取指定子问题的文档 */
    public List<RetrievedDocument> getDocsForSubQuery(int subQueryIndex) {
        return retrievedDocs.stream()
            .filter(doc -> doc.subQueryIndex() == subQueryIndex)
            .collect(Collectors.toList());
    }

    /** P1 去重追加：排除 seenDocIds 中已有的文档，带容量上限；入列的每条分配稳定 [n] 编号 */
    public void addRetrievedDocsDeduplicated(List<RetrievedDocument> docs) {
        for (RetrievedDocument doc : docs) {
            if (retrievedDocs.size() >= MAX_RETRIEVED_DOCS || totalContentChars() >= MAX_TOTAL_CONTENT_CHARS) {
                log.warn("ToolWorkspace dedup capacity reached: docs={}, contentChars={}",
                    retrievedDocs.size(), totalContentChars());
                break;
            }
            if (!seenDocIds.contains(doc.chunkId())) {
                RetrievedDocument truncated = truncateIfNeeded(doc)
                    .withRefNumber(refCounter.incrementAndGet());
                retrievedDocs.add(truncated);
                seenDocIds.add(doc.chunkId());
            }
        }
    }

    /** 获取已见文档 ID 集合 */
    public Set<String> getSeenDocIds() {
        return Collections.unmodifiableSet(seenDocIds);
    }

    // ── 查询改写 ──────────────────────────────────────────

    /** 获取所有改写查询 */
    public List<String> getRewrittenQueries() {
        return Collections.unmodifiableList(rewrittenQueries);
    }

    /** 添加改写查询 */
    public void addRewrittenQuery(String rewrittenQuery) {
        rewrittenQueries.add(rewrittenQuery);
    }

    // ── 自省评估（Self-RAG）───────────────────────────────

    /** 添加自省结果 */
    public void addSelfReflection(SelfReflection reflection) {
        selfReflections.add(reflection);
    }

    /** 获取所有自省结果 */
    public List<SelfReflection> getSelfReflections() {
        return Collections.unmodifiableList(selfReflections);
    }

    // ── 中间答案（DeepRAG）────────────────────────────────

    /** 添加中间答案（超出上限时移除最早的） */
    public void addIntermediateAnswer(IntermediateAnswer answer) {
        if (intermediateAnswers.size() >= MAX_INTERMEDIATE_ANSWERS) {
            intermediateAnswers.remove(0);
            log.warn("ToolWorkspace intermediate answers reached limit ({}), removed oldest", MAX_INTERMEDIATE_ANSWERS);
        }
        intermediateAnswers.add(answer);
    }

    /** 获取所有中间答案 */
    public List<IntermediateAnswer> getIntermediateAnswers() {
        return Collections.unmodifiableList(intermediateAnswers);
    }

    /** 获取中间答案摘要文本（用于注入 System Prompt） */
    public @Nullable String getIntermediateAnswersSummary() {
        if (intermediateAnswers.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (IntermediateAnswer ans : intermediateAnswers) {
            sb.append("- [").append(ans.source()).append("] ");
            if (ans.subQuery() != null && !ans.subQuery().isBlank()) {
                sb.append(ans.subQuery()).append(": ");
            }
            sb.append(ans.answer());
            if (ans.citedDocIds() != null && !ans.citedDocIds().isEmpty()) {
                sb.append(" (refs: ").append(String.join(", ", ans.citedDocIds())).append(")");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    // ── 内容预算 ──────────────────────────────────────────

    /** 计算所有检索文档的内容总字符数 */
    public int getTotalContentChars() {
        return retrievedDocs.stream()
            .mapToInt(doc -> doc.content() != null ? doc.content().length() : 0)
            .sum();
    }

    /**
     * 获取截断后的中间答案摘要（供 system prompt 注入）。
     * <p>
     * 当总字符数超过 budget 时，优先保留最新的答案。
     *
     * @param maxChars 最大字符数预算
     * @return 截断后的摘要文本，null 表示无数据
     */
    public @Nullable String getIntermediateAnswersSummaryBounded(int maxChars) {
        if (intermediateAnswers.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        boolean hasContent = false;
        // 从最新的开始，确保高优先级内容保留
        for (int i = intermediateAnswers.size() - 1; i >= 0; i--) {
            IntermediateAnswer ans = intermediateAnswers.get(i);
            String line = "- [" + ans.source() + "] ";
            if (ans.subQuery() != null && !ans.subQuery().isBlank()) {
                line += ans.subQuery() + ": ";
            }
            line += ans.answer();
            if (ans.citedDocIds() != null && !ans.citedDocIds().isEmpty()) {
                line += " (refs: " + String.join(", ", ans.citedDocIds()) + ")";
            }
            line += "\n";
            if (sb.length() + line.length() > maxChars) {
                if (!hasContent) {
                    // 即使单条答案超预算，仍截断保留部分内容
                    int budget = maxChars - "... (truncated)\n".length();
                    if (budget > 50) {
                        sb.insert(0, line.substring(0, Math.min(line.length(), budget)) + "... (truncated)\n");
                    } else {
                        sb.insert(0, "... (earlier answers omitted)\n");
                    }
                } else {
                    sb.insert(0, "... (earlier answers omitted)\n");
                }
                break;
            }
            sb.insert(0, line);
            hasContent = true;
        }
        return sb.toString();
    }

    /** 计算当前所有文档的总内容字符数 */
    private long totalContentChars() {
        long total = 0;
        for (RetrievedDocument doc : retrievedDocs) {
            if (doc.content() != null) {
                total += doc.content().length();
            }
        }
        return total;
    }

    /**
     * 对单个文档做内容截断，防止单个文档占用过多上下文。
     */
    private RetrievedDocument truncateIfNeeded(RetrievedDocument doc) {
        if (doc.content() != null && doc.content().length() > SINGLE_DOC_TRUNCATE_CHARS) {
            log.debug("Truncating doc {} content from {} to {} chars",
                doc.chunkId(), doc.content().length(), SINGLE_DOC_TRUNCATE_CHARS);
            return doc.withContent(doc.content().substring(0, SINGLE_DOC_TRUNCATE_CHARS) + "...");
        }
        return doc;
    }

    // ── 状态追踪 ──────────────────────────────────────────

    /** 获取当前检索轮次 */
    public int getRetrievalRound() {
        return round;
    }

    /** 递增检索轮次 */
    public int incrementRound() {
        return ++round;
    }

    /** 导出 Workspace 状态（用于调试和追踪） */
    public Map<String, Object> exportState() {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("userId", userId);
        state.put("teamId", teamId);
        state.put("intent", intent != null ? intent.name() : null);
        state.put("subQueries", subQueries);
        state.put("completedSubQueries", completedSubQueries);
        state.put("round", round);
        state.put("retrievedDocCount", retrievedDocs.size());
        state.put("seenDocIds", seenDocIds.size());
        state.put("rewrittenQueries", rewrittenQueries);
        state.put("selfReflectionCount", selfReflections.size());
        state.put("intermediateAnswerCount", intermediateAnswers.size());
        return state;
    }

    // ── 基础信息 ──────────────────────────────────────────

    public long getUserId() {
        return userId;
    }

    public @Nullable Long getTeamId() {
        return teamId;
    }

    /** 会话标识（复用 conversationId）；用于 RAG 链路追踪关联 */
    public @Nullable String getSessionId() {
        return sessionId;
    }
}
