package com.smart.rag.rag.agent.workspace;

import com.smart.rag.rag.agent.dto.IntermediateAnswer;
import com.smart.rag.rag.agent.dto.SelfReflection;
import com.smart.rag.rag.agent.intent.AgentIntent;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Tool Workspace — JSON 中间状态管理
 * <p>
 * Tool 之间通过此结构化对象传递中间结果。每个 Tool 的闭包捕获同一个 workspace 局部变量，
 * 无需任何全局状态传递机制。
 * <p>
 * 生命周期：ToolWorkspaceFactory.create() -> 闭包捕获 -> 请求结束 GC 回收
 */
public class ToolWorkspace {

    private final long userId;
    private final @Nullable Long teamId;
    private @Nullable AgentIntent intent;
    private List<String> subQueries = new ArrayList<>();
    private final Set<Integer> completedSubQueries = new HashSet<>();
    private int round = 0;
    private final List<RetrievedDocument> retrievedDocs = new ArrayList<>();
    private final List<String> rewrittenQueries = new ArrayList<>();
    private final List<SelfReflection> selfReflections = new ArrayList<>();
    private final List<IntermediateAnswer> intermediateAnswers = new ArrayList<>();
    private final Set<String> seenDocIds = new HashSet<>();

    public ToolWorkspace(long userId, @Nullable Long teamId) {
        this.userId = userId;
        this.teamId = teamId;
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
        this.subQueries = new ArrayList<>(subQueries);
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
        return subQueries.stream()
            .filter(i -> !completedSubQueries.contains(subQueries.indexOf(i)))
            .map(subQueries::indexOf)
            .collect(Collectors.toList());
    }

    // ── 检索结果 ──────────────────────────────────────────

    /** 获取所有已检索文档 */
    public List<RetrievedDocument> getRetrievedDocs() {
        return Collections.unmodifiableList(retrievedDocs);
    }

    /** 追加检索文档 */
    public void addRetrievedDocs(List<RetrievedDocument> docs) {
        for (RetrievedDocument doc : docs) {
            retrievedDocs.add(doc);
            seenDocIds.add(doc.docId());
        }
    }

    /** 替换所有检索文档（用于 rerank、parentDocLookup 等替换场景） */
    public void replaceRetrievedDocs(List<RetrievedDocument> docs) {
        retrievedDocs.clear();
        seenDocIds.clear();
        for (RetrievedDocument doc : docs) {
            retrievedDocs.add(doc);
            seenDocIds.add(doc.docId());
        }
    }

    /** 获取指定子问题的文档 */
    public List<RetrievedDocument> getDocsForSubQuery(int subQueryIndex) {
        return retrievedDocs.stream()
            .filter(doc -> doc.subQueryIndex() == subQueryIndex)
            .collect(Collectors.toList());
    }

    /** P1 去重追加：排除 seenDocIds 中已有的文档 */
    public void addRetrievedDocsDeduplicated(List<RetrievedDocument> docs) {
        for (RetrievedDocument doc : docs) {
            if (!seenDocIds.contains(doc.docId())) {
                retrievedDocs.add(doc);
                seenDocIds.add(doc.docId());
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

    /** 添加中间答案 */
    public void addIntermediateAnswer(IntermediateAnswer answer) {
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
}
