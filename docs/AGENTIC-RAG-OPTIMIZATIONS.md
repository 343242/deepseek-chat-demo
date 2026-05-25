# Agentic RAG 设计优化方案

> 基于 context-mode MCP 架构的上下文节省与会话连续性优化
> 目标文档：AGENTIC-RAG-DESIGN.md
> 状态：Draft · Phase 2 补充
> 前置依赖：PostgreSQL 18 + PGvector 0.8.2 + pg_jieba + Redis 8.2 + Spring AI 1.1.6

---

## 目录

- [优化总览](#优化总览)
- [优化一：检索结果两层分离（P0）](#优化一检索结果两层分离p0)
- [优化二：自省重检去重（P1）](#优化二自省重检去重p1)
- [优化三：会话连续性（P2）](#优化三会话连续性p2)
- [对原设计的影响矩阵](#对原设计的影响矩阵)
- [实施计划](#实施计划)

---

## 优化总览

| # | 优化项 | 优先级 | 核心收益 | 新增文件 | 修改文件 |
|---|--------|--------|----------|----------|----------|
| 1 | 检索结果两层分离 | P0 | 上下文占用降低 ~87% | 1 | 4 |
| 2 | 自省重检去重 | P1 | 避免重复文档浪费上下文 | 0 | 3 |
| 3 | 会话连续性 | P2 | 长对话 compaction 后状态恢复 | 4 | 3 |
| 4 | Tool 输出沙盒化 | P1 | 统一输出格式，降低上下文占用 | 0 | 9 |
| 5 | 上下文预算管理 | P2 | 动态管理上下文窗口使用 | 1 | 2 |
| 6 | 智能缓存 | P3 | 避免重复检索，降低延迟 | 1 | 3 |

### 技术栈约束

| 组件 | 版本 | 对优化的影响 |
|------|------|-------------|
| PostgreSQL + PGvector | 18 + 0.8.2 | 摘要提取在 SQL 层完成（`ts_headline` + pg_jieba），无需应用层处理 |
| Redis | 8.2 | V1 不使用 — 会话事件仅存 PostgreSQL，避免双写复杂度 |
| Spring AI ChatMemory | 1.1.6 | JDBC + Redis 双层，compaction 时截断早期消息，需在截断前保存 Agent 状态 |
| 多 Provider 路由 | DeepSeek / 智谱 / MiniMax | IntentClassifier 复用 `ModelRouter`，用最便宜的模型分类 |
| 现有 Tool Calling | Docker 沙箱 + Calculator + DateTime | 新 Tool 不需要新的沙箱基础设施 |

---

## 优化一：检索结果两层分离（P0）

### 问题

原设计 §4.3.1 在上下文窗口占用 70%/85%/95% 时做渐进式压缩（200 字截断 → 摘要 → 关键词）。这是**反应式止损** — 先把数据灌进去，再裁剪。

根本矛盾：

```
hybridSearch 返回 8 个文档片段，每片段 ~1.5KB
→ 8 × 1.5KB = 12KB 进入 LLM context
→ rerank 后 5 个文档 = 7.5KB
→ 总计 ~20KB 检索原始数据

但 LLM 自省时真正需要的只是：
  "8 个文档：doc1(0.89, RAG知识更新), doc2(0.85, FT成本), ..."
  → ~200 字节足够驱动决策
```

### 方案

检索 Tool 返回**摘要**，新增 `docDetailTool` 按需获取完整内容。

```
当前流程：
  hybridSearchTool() → ToolResult{docs: 8, 全量 content}
  → LLM 看到 8 个完整文档片段（~12KB）

优化后流程：
  hybridSearchTool() → ToolResult{docs: 8, 仅摘要}
  → LLM 看到结构化摘要（~0.3KB）
  → LLM 判断需要 doc1/doc3 详情 → 调用 docDetailTool(docIds, query)
  → 返回 doc1/doc3 的查询相关片段（~1KB）
```

### 1.1 检索 Tool 返回格式变更

所有检索类 Tool（`vectorSearch` / `bm25Search` / `hybridSearch`）的返回值改为：

```json
{
  "status": "success",
  "action": "hybridSearch",
  "docCount": 8,
  "docs": [
    {"id": "d1", "score": 0.89, "snippet": "RAG通过向量检索实现知识更新，支持实时..."},
    {"id": "d2", "score": 0.85, "snippet": "Fine-tuning需要重新训练模型，成本和时间..."}
  ],
  "workspaceUpdated": true,
  "tip": "如需某文档完整内容，调用 docDetail(docIds, query) 获取关键段落"
}
```

摘要提取直接在 SQL 层完成（利用已有的 pg_jieba 配置）：

```sql
-- hybridSearch Tool 内部，检索时顺便取 ts_headline 做摘要
SELECT
  id,
  cosine_score,
  ts_headline(
    'jieba_config',
    content,
    websearch_to_tsquery('jieba_config', :query),
    'MaxWords=30, MinWords=15, ShortWord=3'
  ) AS snippet
FROM rag_document_chunk
WHERE ...
ORDER BY rrf_score DESC
LIMIT 8;
```

零应用层代码变更 — PostgreSQL 的 `ts_headline` 直接产出智能摘要。

> **前置依赖**：确保 `jieba_config`（pg_jieba 文本搜索配置）已在 Flyway migration 中创建，且 `rag_document_chunk` 表的 `content` 列已建立 `tsvector` 索引。若未配置 pg_jieba，需先执行对应的 Flyway 迁移。

### 1.2 新增 `docDetailTool`

| Tool | 输入 | 输出 | 意图映射 |
|------|------|------|----------|
| `docDetail` | `docIds: String[], query: String` | 指定文档的查询相关片段（每文档 ≤300 字窗口） | RETRIEVAL + DEEP_RETRIEVAL |

SQL 层实现（复用 `VectorStoreMapper` 模式）：

```sql
-- docDetailTool 内部
SELECT
  id,
  ts_headline(
    'jieba_config',
    content,
    websearch_to_tsquery('jieba_config', :query),
    'MaxWords=80, MinWords=40, ShortWord=3, MaxFragments=2'
  ) AS relevant_snippet
FROM rag_document_chunk
WHERE id = ANY(:docIds);
```

### 1.3 改动清单

| 类型 | 文件 | 说明 |
|------|------|------|
| 修改 | `VectorSearchTool.java` | 返回摘要而非全量内容 |
| 修改 | `Bm25SearchTool.java` | 同上 |
| 修改 | `HybridSearchTool.java` | 同上 + SQL 加 `ts_headline` |
| 修改 | `VectorStoreMapper.xml` | 新增带 `ts_headline` 的摘要查询 |
| 新增 | `DocDetailTool.java` | 按需获取文档片段 |
| 新增 | `DocDetailTool` 注册 | `AgentToolCallbackFactory` 意图映射加 docDetail |
| 修改 | `AgentSystemPromptAdvisor.before()` | 注入 `docDetail` 可用提示 |

### 1.4 删除原设计 §4.3.1

三层渐进压缩不再必要 — 全量文档内容不再进入 LLM context。

`AgentSystemPromptAdvisor.before()` 简化为：

```java
// 优化后的 before() — 无需压缩逻辑
public AdvisedResponse before(AdvisedRequest request) {
    // 1. 注入动态 System Prompt（含意图引导 + 自省格式）
    // 2. 注入中间答案（如有）
    // 3. 不需要任何压缩逻辑 — 检索结果已经是摘要级别
}
```

### 1.5 上下文节省估算

```
场景：DEEP_RETRIEVAL，2 轮检索 + 1 次 rerank + 1 次 docDetail

当前设计：
  2 × hybridSearch(8 docs × ~1.5KB) = 24KB
  + rerank 结果 5 docs × 1.5KB = 7.5KB
  + §4.3.1 在 70% 时触发压缩（200字截断）
  总计 ≈ 20KB（压缩后）

优化后：
  2 × hybridSearch(8 摘要 × ~80B) = 1.3KB
  + rerank(5 摘要) = 0.4KB
  + docDetail(2 docs × ~0.5KB) = 1KB
  总计 ≈ 2.7KB（无需压缩，87% reduction vs 当前压缩后）
```

---

## 优化二：自省重检去重（P1）

### 问题

Self-RAG 自省 `is_sufficient=false` → 改写查询 → 重新检索，但：
- 改写后的查询可能检索到大量相同文档
- 重复文档在 Workspace `retrievedDocs` 中累积
- 每次都走 PGvector + BM25 → RRF 融合的完整流程（延迟 ~500ms）

### 方案

### 2.1 `ToolWorkspace` 增加文档去重

```java
public class ToolWorkspace {
    // 现有字段保留...

    /** 已检索文档 ID 集合（用于去重） */
    private final Set<String> seenDocIds = new HashSet<>();

    /**
     * 添加检索结果时自动去重
     * @return 新增的文档数量（排除已有）
     */
    public int addRetrievedDocsDeduplicated(List<RetrievedDocument> docs) {
        List<RetrievedDocument> newDocs = docs.stream()
            .filter(d -> seenDocIds.add(d.docId()))  // Set.add 返回 false 表示已存在
            .toList();
        if (!newDocs.isEmpty()) {
            this.retrievedDocs.addAll(newDocs);
        }
        return newDocs.size();
    }

    /** 获取已检索过的文档 ID 集合 — 传给检索 Tool 做过滤 */
    public Set<String> getSeenDocIds() {
        return Collections.unmodifiableSet(seenDocIds);
    }
}
```

### 2.2 检索 Tool SQL 层排除已见文档

```sql
-- hybridSearch 第二轮调用时，排除 Workspace 中已有的 docId
SELECT ...
FROM rag_document_chunk
WHERE id NOT IN (:seenDocIds)  -- 新增过滤
  AND (user_id = :userId ...)
ORDER BY rrf_score DESC
LIMIT 8;
```

保证每轮检索都返回**新文档**，LLM 不会看到重复内容。

### 2.3 Tool 返回值增加去重信息

```json
{
  "status": "success",
  "action": "hybridSearch",
  "docCount": 8,
  "newDocs": 5,
  "duplicateSkipped": 3,
  "docs": [...]
}
```

LLM 看到 `"newDocs": 5` 时知道只有 5 个新结果，可以更好地判断是否需要继续检索。

### 2.4 改动清单

| 类型 | 文件 | 说明 |
|------|------|------|
| 修改 | `ToolWorkspace.java` | 新增 `seenDocIds` + 去重逻辑 |
| 修改 | `VectorStoreMapper.xml` | SQL 加 `WHERE id NOT IN (:seenDocIds)` |
| 修改 | `HybridSearchTool.java` | 传入 `seenDocIds`，返回 `newDocs` 计数 |
| 修改 | `VectorSearchTool.java` | 同上 |
| 修改 | `Bm25SearchTool.java` | 同上 |

---

## 优化三：会话连续性（P2）

### 问题

长 Agent 对话时 Spring AI 的 ChatMemory 会 compaction 截断早期消息：

```
用户: "对比 RAG 和 FT 在医疗、金融、教育三个领域的知识更新"
  → 意图分类 + 3 个子问题 × (检索 + 自省 + 可能重检)
  → 总计 6-12 轮 Tool 调用
  → 中间答案 + 检索摘要 ≈ 15-25KB
  → ChatMemory compaction 截断早期消息
  → 意图分类结果、前几个子问题的中间答案丢失
```

### 方案：PostgreSQL 事件表 + 优先级分层恢复

### 3.1 新增 `agent_session_event` 表

```sql
-- 利用现有 PostgreSQL，不引入新存储引擎
CREATE TABLE agent_session_event (
    id              BIGSERIAL PRIMARY KEY,
    session_id      VARCHAR(36) NOT NULL,        -- UUIDv7，复用现有会话 ID
    user_id         BIGINT NOT NULL,             -- 用户 ID，多租户隔离
    event_type      VARCHAR(32) NOT NULL,        -- 见下方 EventType 枚举
    priority        SMALLINT NOT NULL DEFAULT 3, -- 1=Critical, 2=High, 3=Normal
    data            JSONB NOT NULL,              -- 结构化事件数据
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- 与原设计 §6.4 AgentTrace 合并 — 事件即是追踪
    duration_ms     BIGINT,                      -- Tool 耗时
    tool_name       VARCHAR(64),                 -- Tool 名称
    success         BOOLEAN                      -- 是否成功
);

CREATE INDEX idx_agent_event_session ON agent_session_event(session_id, created_at);
CREATE INDEX idx_agent_event_type ON agent_session_event(session_id, event_type);
CREATE INDEX idx_agent_event_user ON agent_session_event(user_id, session_id);

-- TTL 清理：14 天前的会话事件自动删除
-- 用 pg_cron 或应用层启动时清理
```

### 3.2 事件类型与优先级

| event_type | priority | 说明 | 数据示例 |
|------------|----------|------|----------|
| `INTENT_CLASSIFIED` | 1 | 意图分类结果 | `{"intent": "DEEP_RETRIEVAL", "confidence": 0.92, "subQuestions": [...]}` |
| `INTERMEDIATE_ANSWER` | 1 | 子问题中间答案 | `{"question": "...", "answer": "...", "sourceDocIds": [...]}` |
| `SELF_REFLECTION` | 2 | 自省结果 | `{"sufficient": false, "reason": "缺少教育领域", "rewriteQuery": "..."}` |
| `RETRIEVAL_STRATEGY` | 2 | 检索策略变更 | `{"from": "vectorSearch", "to": "hybridSearch", "reason": "..."}` |
| `TOOL_CALLED` | 3 | Tool 调用记录 | `{"tool": "hybridSearch", "args": {...}, "result": "success"}` |
| `GUARDRAIL_TRIGGERED` | 1 | 护栏触发 | `{"rule": "MAX_TOOL_CALLS", "action": "forceSummarize"}` |

### 3.3 事件记录实现

```java
/**
 * AgentEventStore — PostgreSQL 事件存储（V1: PG-only）
 *
 * 设计原则：
 * - V1 仅使用 PostgreSQL，避免 Redis 双写复杂度
 * - 14 天 TTL 自动清理（应用层启动时或 pg_cron）
 * - 与原设计 §6.4 AgentTrace 合并 — 一份数据，两种用途
 * - 后续迭代如需降低读取延迟，可引入 Redis 缓存层
 */
@Component
public class AgentEventStore {

    private final AgentEventMapper mapper;

    /**
     * 记录事件
     */
    public void record(String sessionId, Long userId, EventType type, int priority,
                       String data, String toolName, boolean success, Long durationMs) {
        mapper.insert(new AgentSessionEvent(
            sessionId, userId, type.name(), priority, data,
            toolName, success, durationMs, Instant.now()
        ));
    }

    /**
     * 构建恢复快照 — 优先级分层恢复
     */
    public String buildResumeSnapshot(String sessionId, int maxBytes) {
        List<AgentSessionEvent> events = mapper.selectBySessionIdOrderByPriority(sessionId);

        StringBuilder sb = new StringBuilder("## 前序会话恢复\n\n");
        int budget = maxBytes;

        // P1: 意图分类 + 中间答案 — 永远保留
        for (var e : filterByPriority(events, 1)) {
            if (budget < 50) break;
            String line = formatEvent(e);
            sb.append(line).append("\n");
            budget -= line.length();
        }

        // P2: 自省结果 + 检索策略历史
        for (var e : filterByPriority(events, 2)) {
            if (budget < 50) break;
            String line = formatEvent(e);
            sb.append(line).append("\n");
            budget -= line.length();
        }

        // P3: Tool 调用统计（一行摘要）
        if (budget > 50) {
            long toolCount = events.stream()
                .filter(e -> e.getEventType().equals("TOOL_CALLED"))
                .count();
            sb.append("Tools used: ").append(toolCount).append(" calls total\n");
        }

        // 末尾附加按需查询指令
        sb.append("\nFor full event history → agentEventLookup(sessionId: \"")
          .append(sessionId).append("\", query: \"your question\")\n");

        return sb.toString();
    }

    /**
     * 搜索历史事件（含 userId 隔离）
     */
    public List<AgentSessionEvent> searchEvents(String sessionId, Long userId,
                                                 String query, int limit) {
        return mapper.searchBySessionAndUserAndQuery(sessionId, userId, query, limit);
    }

    /**
     * 获取当前已知消息数（用于 compaction 检测）
     * 返回最近一次 recordMessageCount 记录的消息数快照
     */
    public int getLastKnownMessageCount(String sessionId) {
        return mapper.selectLastMessageCount(sessionId);
    }

    /**
     * 记录当前消息数，供下次 compaction 检测对比
     */
    public void recordMessageCount(String sessionId, int messageCount) {
        mapper.upsertMessageCount(sessionId, messageCount);
    }
}
```

### 3.4 Compaction 恢复注入

在 `AgentSystemPromptAdvisor` 中检测 compaction 并恢复：

```java
/**
 * AgentSystemPromptAdvisor.before() — 增加 compaction 恢复逻辑
 */
public AdvisedResponse before(AdvisedRequest request) {
    // ... 现有逻辑 ...

    // 新增：检测上下文是否被压缩
    // Spring AI ChatMemory compaction 时，消息数量会骤降
    if (isCompactionDetected(request, workspace)) {
        String snapshot = eventStore.buildResumeSnapshot(
            workspace.getSessionId(),
            2000  // 2KB 预算
        );
        systemPrompt += "\n\n" + snapshot;
    }

    // 记录当前消息数，供下次 compaction 检测对比
    eventStore.recordMessageCount(workspace.getSessionId(), request.messages().size());

    // 现有：中间答案注入
    if (!workspace.getIntermediateAnswers().isEmpty()) {
        systemPrompt += "\n\n## 已收集的信息\n" + workspace.getIntermediateAnswersSummary();
    }
}

/**
 * 检测 ChatMemory 是否发生了 compaction
 *
 * 检测策略：对比上次记录的消息数 vs 当前实际消息数。
 * ChatMemory compaction 时会截断早期消息，导致消息数骤降。
 * 每次调用后通过 recordMessageCount() 记录当前消息数，下次调用时比较。
 */
private boolean isCompactionDetected(AdvisedRequest request, ToolWorkspace workspace) {
    int currentMessageCount = request.messages().size();
    int lastKnownMessageCount = eventStore.getLastKnownMessageCount(workspace.getSessionId());
    // 首次调用或无历史记录时不检测
    if (lastKnownMessageCount <= 0) return false;
    // 消息数大幅减少（超过 30%）说明 compaction 已发生
    return currentMessageCount < lastKnownMessageCount * 0.7;
}
```

### 3.5 新增 `agentEventLookupTool`

| Tool | 输入 | 输出 | 意图映射 |
|------|------|------|----------|
| `agentEventLookup` | `query: String` | 匹配的历史事件摘要（最多 5 条） | RETRIEVAL（回溯自身历史） |

```java
/**
 * 从 PostgreSQL 事件表按需检索历史事件
 * 参考 context-mode 的 ctx_search — "不灌回全量数据，按需查询"
 */
public class AgentEventLookupTool implements RagTool {

    private final AgentEventStore eventStore;

    public ToolResult execute(ToolRequest request, ToolWorkspace workspace) {
        String query = request.getRequiredString("query");
        String sessionId = workspace.getSessionId();
        Long userId = workspace.getUserId();

        List<AgentSessionEvent> matches = eventStore.searchEvents(sessionId, userId, query, 5);

        String result = matches.stream()
            .map(e -> formatEventSnippet(e, query, 300))
            .collect(joining("\n\n"));

        return ToolResult.success("agentEventLookup", result, matches.size());
    }
}
```

事件搜索利用 PostgreSQL JSONB：

```sql
-- 事件搜索 — 利用 JSONB 的全文能力，含 userId 多租户隔离
SELECT * FROM agent_session_event
WHERE session_id = :sessionId
  AND user_id = :userId
  AND (
    data @@ to_tsquery('jieba_config', :query)
    OR data::text ILIKE '%' || :query || '%'
  )
ORDER BY created_at DESC
LIMIT 5;
```

### 3.6 改动清单

| 类型 | 文件 | 说明 |
|------|------|------|
| 新增 | `V15__agent_session_event.sql` | Flyway 迁移，建表 + 索引 |
| 新增 | `AgentEventStore.java` | 事件记录 + 搜索 + 快照构建 |
| 新增 | `AgentEventMapper.java` | MyBatis-Plus Mapper |
| 新增 | `AgentEventLookupTool.java` | 按需检索历史事件 |
| 修改 | `AgentSystemPromptAdvisor.java` | 增加 compaction 检测 + 恢复注入 |
| 修改 | `AgentToolCallbackFactory.java` | 意图映射增加 `agentEventLookup` |
| 修改 | `HybridSearchTool.java` 等 | 事件记录调用 |
| 修改 | `AgentGuardrails.java` | 护栏触发时持久化 Workspace 状态 |
| 合并 | `AgentTrace.java` + `ToolCallRecord.java` | 与 `AgentSessionEvent` 合并，一份数据两种用途 |

---

## 对原设计的影响矩阵

| 原设计章节 | 优化后变更 | 影响类型 |
|------------|-----------|----------|
| §2.5 Tool Workspace | 新增 `seenDocIds` 去重字段 + `addRetrievedDocsDeduplicated()` | 小改 |
| §3.1 Tool 清单 | 新增 `docDetailTool` + `agentEventLookupTool`（7→9 个 Tool） | 扩展 |
| §3.2 包结构 | 新增 `DocDetailTool.java`, `AgentEventStore.java`, `AgentEventMapper.java` | 扩展 |
| §3.3 RetrievedDocument | 保持不变（全量内容仍存 Workspace） | 无变化 |
| §3.5 AgentToolCallbackFactory | 意图→Tool 映射增加 docDetail + agentEventLookup | 小改 |
| §4.1 改动清单 | 新增 4 个文件 + 1 个 Flyway migration | 扩展 |
| **§4.3.1 三层渐进压缩** | **删除** — 检索结果已是摘要级别，不需要压缩 | **删除（简化）** |
| §4.3 AgentSystemPromptAdvisor | 简化 before() + 增加 compaction 恢复逻辑 | 修改 |
| §6.3 容错矩阵 F9 | Token 超限时先持久化到 agent_session_event | 增强 |
| **§6.4 AgentTrace** | **与 AgentSessionEvent 合并** — 一份存储两种用途 | **合并（简化）** |

---

## 实施计划

### 与原设计 Phase 的对应关系

| 原设计 Phase | 可集成的优化项 | 说明 |
|-------------|---------------|------|
| Phase 3: RAG Tool 实现 | **P0** 检索两层分离 + **P1** 去重 | 顺带完成，不增加 Phase |
| Phase 5: 护栏 + 容错 | **P2** 会话连续性 | 在容错逻辑中集成 compaction 恢复 |

### 工时估算

| 优先级 | 优化项 | 新增文件 | 修改文件 | 估时 |
|--------|--------|----------|----------|------|
| **P0** | 检索结果两层分离 + docDetailTool | 1 | 4 | 2-3h |
| **P1** | 检索去重 | 0 | 3 | 1-2h |
| **P2** | 会话连续性 | 4 | 3 | 4-6h |
| — | 删除 §4.3.1 | 0 | 1 | 0.5h |

**总计：~8-12h，无需新增 Phase。**

### 关键依赖

- P0 依赖：pg_jieba 已配置（`jieba_config`），`ts_headline` 可用
- P1 依赖：P0 的摘要格式变更完成
- P2 依赖：Flyway migration 可在任何 Phase 前执行
- P1（Tool 输出沙盒化）依赖：无
- P2（上下文预算管理）依赖：P0 完成
- P3（智能缓存）依赖：P0 完成

---

## 优化四：Tool 输出沙盒化（P1）

### 问题

当前设计中，每个 Tool 的输出格式不统一，有些返回完整文档内容，有些返回摘要。这导致：
1. LLM 需要处理不同格式的输出，增加认知负担
2. 某些 Tool 输出过大，浪费上下文窗口
3. 缺乏统一的错误处理和状态反馈机制

### 方案

参考 context-mode 的沙盒思想，所有 Tool 的输出都应该是**结构化的 JSON**，包含：
1. **摘要信息**：供 LLM 快速理解结果
2. **详细信息引用**：指向外部存储的完整数据
3. **状态信息**：成功/失败、错误分类、耗时等

### 4.1 统一 Tool 输出格式

所有 Tool 返回统一的 JSON 结构：

```json
{
  "status": "success|failure",
  "action": "toolName",
  "summary": "人类可读的结果摘要",
  "data": {
    "count": 8,
    "items": [
      {"id": "d1", "score": 0.89, "snippet": "..."}
    ],
    "workspaceUpdated": true
  },
  "metadata": {
    "durationMs": 420,
    "tokenEstimate": 150,
    "errorCategory": null,
    "errorMessage": null
  },
  "nextSteps": {
    "suggestion": "如需详细信息，调用 docDetail(docIds, query)",
    "availableTools": ["docDetail", "rerank"]
  }
}
```

### 4.2 Tool 输出裁剪策略

| Tool 类型 | 输出裁剪策略 | 保留字段 |
|-----------|-------------|----------|
| 检索类 (vectorSearch, bm25Search, hybridSearch) | 只返回摘要 + docId + score | snippet ≤ 100 字 |
| 精排类 (rerank) | 返回精排后的摘要列表 | snippet ≤ 100 字 |
| 改写类 (queryRewrite) | 返回改写后的查询列表 | 完整查询 |
| 详情类 (docDetail) | 返回查询相关片段 | ≤ 300 字窗口 |
| 元信息类 (knowledgeBaseInfo) | 返回统计摘要 | 完整统计 |

### 4.3 上下文 Token 预算

每个 Tool 输出应控制在 **200 token** 以内（约 800 字符）。

超出时的裁剪策略：
1. 优先保留 `summary` 和 `data.count`
2. 其次保留 `data.items` 的前 3 项
3. 最后保留 `nextSteps.suggestion`

### 4.4 改动清单

| 类型 | 文件 | 说明 |
|------|------|------|
| 修改 | `VectorSearchTool.java` | 返回统一格式 JSON |
| 修改 | `Bm25SearchTool.java` | 同上 |
| 修改 | `HybridSearchTool.java` | 同上 |
| 修改 | `RerankTool.java` | 同上 |
| 修改 | `QueryRewriteTool.java` | 同上 |
| 修改 | `ParentDocLookupTool.java` | 同上 |
| 修改 | `KnowledgeBaseInfoTool.java` | 同上 |
| 修改 | `DocDetailTool.java` | 同上 |
| 修改 | `AgentEventLookupTool.java` | 同上 |

---

## 优化五：上下文预算管理（P2）

### 问题

当前设计中，上下文窗口的使用是被动的——只有当 token 超限时才触发压缩。这导致：
1. 无法提前预判上下文是否足够
2. 无法优先保留高优先级信息
3. 无法动态调整 Tool 输出的详细程度

### 方案

参考 context-mode 的上下文节省思想，实现**主动的上下文预算管理**。

### 5.1 上下文预算模型

```java
/**
 * 上下文预算管理器
 *
 * 参考 context-mode 的 98% 节省目标，动态管理上下文窗口使用
 */
@Component
public class ContextBudgetManager {

    private final AgentRagProperties properties;

    // 预算分配（占总上下文窗口的比例）
    private static final double SYSTEM_PROMPT_BUDGET = 0.15;  // 15% 给 System Prompt
    private static final double TOOL_OUTPUT_BUDGET = 0.50;    // 50% 给 Tool 输出
    private static final double CONVERSATION_BUDGET = 0.25;   // 25% 给对话历史
    private static final double RESERVE_BUDGET = 0.10;        // 10% 预留

    /**
     * 计算当前上下文使用情况
     */
    public ContextBudget calculateBudget(int modelContextWindow) {
        int totalBudget = (int) (modelContextWindow * properties.contextWindowRatio());
        return new ContextBudget(
            (int) (totalBudget * SYSTEM_PROMPT_BUDGET),
            (int) (totalBudget * TOOL_OUTPUT_BUDGET),
            (int) (totalBudget * CONVERSATION_BUDGET),
            (int) (totalBudget * RESERVE_BUDGET)
        );
    }

    /**
     * 检查是否还有预算添加 Tool 输出
     */
    public boolean canAddToolOutput(ContextBudget budget, int currentToolOutputTokens) {
        return currentToolOutputTokens < budget.toolOutputBudget();
    }

    /**
     * 获取建议的 Tool 输出详细程度
     */
    public OutputVerbosity suggestVerbosity(ContextBudget budget, int currentToolOutputTokens) {
        double usageRatio = (double) currentToolOutputTokens / budget.toolOutputBudget();
        if (usageRatio < 0.5) return OutputVerbosity.DETAILED;
        if (usageRatio < 0.8) return OutputVerbosity.NORMAL;
        return OutputVerbosity.CONCISE;
    }

    public record ContextBudget(
        int systemPromptBudget,
        int toolOutputBudget,
        int conversationBudget,
        int reserveBudget
    ) {}

    public enum OutputVerbosity {
        DETAILED,  // 详细输出，包含更多上下文
        NORMAL,    // 正常输出，标准摘要
        CONCISE    // 简洁输出，最小化上下文占用
    }
}
```

### 5.2 Tool 输出动态调整

根据上下文预算，动态调整 Tool 输出的详细程度：

```java
// 在 Tool 执行时
OutputVerbosity verbosity = budgetManager.suggestVerbosity(budget, currentToolOutputTokens);

return switch (verbosity) {
    case DETAILED -> ToolResult.success("hybridSearch",
        formatDetailedSummary(query, retrieved), retrieved, duration);
    case NORMAL -> ToolResult.success("hybridSearch",
        formatNormalSummary(query, retrieved), retrieved, duration);
    case CONCISE -> ToolResult.success("hybridSearch",
        formatConciseSummary(query, retrieved), retrieved, duration);
};
```

### 5.3 改动清单

| 类型 | 文件 | 说明 |
|------|------|------|
| 新增 | `ContextBudgetManager.java` | 上下文预算管理器 |
| 修改 | `AgentSystemPromptAdvisor.java` | 集成预算检查 |
| 修改 | `AgentGuardrails.java` | 集成预算检查 |

---

## 优化六：智能缓存（P3）

### 问题

当前设计中，相同查询的检索结果不会缓存，导致：
1. 重复检索相同内容，浪费数据库资源
2. 增加不必要的延迟
3. 在 ReAct 循环中，改写后的查询可能检索到相同文档

### 方案

参考 context-mode 的缓存思想，实现**智能缓存**。

### 6.1 缓存策略

| 缓存类型 | 缓存键 | TTL | 失效条件 |
|----------|--------|-----|----------|
| 查询结果缓存 | `query + userId + teamId` | 5 分钟 | 文档更新、用户切换 |
| 文档详情缓存 | `docId + query` | 10 分钟 | 文档内容更新 |
| 意图分类缓存 | `query hash` | 30 分钟 | 意图模型更新 |

### 6.2 缓存实现

```java
/**
 * Agent 缓存管理器
 *
 * 使用 Caffeine 本地缓存，避免 Redis 网络开销
 */
@Component
public class AgentCacheManager {

    private final Cache<String, CachedResult> queryCache;
    private final Cache<String, CachedResult> docDetailCache;
    private final Cache<String, IntentResult> intentCache;

    public AgentCacheManager(AgentRagProperties properties) {
        this.queryCache = Caffeine.newBuilder()
            .maximumSize(1000)
            .expireAfterWrite(Duration.ofMinutes(properties.cacheQueryTtlMinutes()))
            .recordStats()
            .build();

        this.docDetailCache = Caffeine.newBuilder()
            .maximumSize(5000)
            .expireAfterWrite(Duration.ofMinutes(properties.cacheDocDetailTtlMinutes()))
            .recordStats()
            .build();

        this.intentCache = Caffeine.newBuilder()
            .maximumSize(2000)
            .expireAfterWrite(Duration.ofMinutes(properties.cacheIntentTtlMinutes()))
            .recordStats()
            .build();
    }

    /**
     * 获取缓存的查询结果
     */
    public Optional<CachedResult> getCachedQueryResult(String query, Long userId, Long teamId) {
        String key = buildQueryKey(query, userId, teamId);
        return Optional.ofNullable(queryCache.getIfPresent(key));
    }

    /**
     * 缓存查询结果
     */
    public void cacheQueryResult(String query, Long userId, Long teamId,
                                 List<RetrievedDocument> docs, long durationMs) {
        String key = buildQueryKey(query, userId, teamId);
        queryCache.put(key, new CachedResult(docs, durationMs, Instant.now()));
    }

    /**
     * 获取缓存的意图分类
     */
    public Optional<IntentResult> getCachedIntent(String query) {
        String key = buildIntentKey(query);
        return Optional.ofNullable(intentCache.getIfPresent(key));
    }

    /**
     * 缓存意图分类
     */
    public void cacheIntent(String query, IntentResult result) {
        String key = buildIntentKey(query);
        intentCache.put(key, result);
    }

    /**
     * 使缓存失效（文档更新时调用）
     */
    public void invalidateForDocumentUpdate(Long userId, Long teamId) {
        queryCache.asMap().keySet().removeIf(key -> key.startsWith(userId + ":" + teamId + ":"));
    }

    /**
     * 获取缓存统计
     */
    public CacheStats getStats() {
        return CacheStats.combine(
            queryCache.stats(),
            docDetailCache.stats(),
            intentCache.stats()
        );
    }

    private String buildQueryKey(String query, Long userId, Long teamId) {
        return userId + ":" + teamId + ":" + DigestUtils.md5Hex(query);
    }

    private String buildIntentKey(String query) {
        return DigestUtils.md5Hex(query);
    }

    public record CachedResult(
        List<RetrievedDocument> documents,
        long durationMs,
        Instant cachedAt
    ) {}
}
```

### 6.3 缓存集成点

| 集成点 | 缓存操作 | 说明 |
|--------|----------|------|
| `IntentClassifier.classify()` | 读取/写入意图缓存 | 相同查询直接返回缓存结果 |
| `HybridSearchTool.execute()` | 读取/写入查询缓存 | 相同查询直接返回缓存结果 |
| `DocDetailTool.execute()` | 读取/写入文档详情缓存 | 相同 docId + query 直接返回 |
| 文档上传/更新 | 使查询缓存失效 | 确保新文档能被检索到 |

### 6.4 缓存监控

```java
// 在 AgentChatResponse 中添加缓存统计
Map<String, Object> agentMeta = Map.of(
    "agentTrace", workspace.exportTrace(),
    "agentIntent", intent.name(),
    "cacheStats", Map.of(
        "queryCacheHitRate", cacheManager.getStats().queryCacheHitRate(),
        "intentCacheHitRate", cacheManager.getStats().intentCacheHitRate(),
        "totalCacheHits", cacheManager.getStats().totalCacheHits()
    )
);
```

### 6.5 改动清单

| 类型 | 文件 | 说明 |
|------|------|------|
| 新增 | `AgentCacheManager.java` | 缓存管理器 |
| 修改 | `IntentClassifier.java` | 集成意图缓存 |
| 修改 | `HybridSearchTool.java` | 集成查询缓存 |
| 修改 | `DocDetailTool.java` | 集成文档详情缓存 |

---

## 对原设计的影响矩阵（更新）

| 原设计章节 | 优化后变更 | 影响类型 |
|------------|-----------|----------|
| §2.5 Tool Workspace | 新增 `seenDocIds` 去重字段 + `addRetrievedDocsDeduplicated()` | 小改 |
| §3.1 Tool 清单 | 新增 `docDetailTool` + `agentEventLookupTool`（7→9 个 Tool） | 扩展 |
| §3.2 包结构 | 新增 `DocDetailTool.java`, `AgentEventStore.java`, `AgentEventMapper.java` | 扩展 |
| §3.3 RetrievedDocument | 保持不变（全量内容仍存 Workspace） | 无变化 |
| §3.5 AgentToolCallbackFactory | 意图→Tool 映射增加 docDetail + agentEventLookup | 小改 |
| §4.1 改动清单 | 新增 4 个文件 + 1 个 Flyway migration | 扩展 |
| **§4.3.1 三层渐进压缩** | **删除** — 检索结果已是摘要级别，不需要压缩 | **删除（简化）** |
| §4.3 AgentSystemPromptAdvisor | 简化 before() + 增加 compaction 恢复逻辑 | 修改 |
| §6.3 容错矩阵 F9 | Token 超限时先持久化到 agent_session_event | 增强 |
| **§6.4 AgentTrace** | **与 AgentSessionEvent 合并** — 一份存储两种用途 | **合并（简化）** |
| **Tool 输出格式** | **统一 JSON 结构** — 包含摘要、详细信息引用、状态信息 | **标准化** |
| **上下文管理** | **主动预算管理** — 动态调整 Tool 输出详细程度 | **增强** |
| **缓存策略** | **智能缓存** — 查询结果、文档详情、意图分类缓存 | **新增** |

---

## 实施计划（更新）

### 与原设计 Phase 的对应关系

| 原设计 Phase | 可集成的优化项 | 说明 |
|-------------|---------------|------|
| Phase 3: RAG Tool 实现 | **P0** 检索两层分离 + **P1** 去重 + **P1** Tool 输出沙盒化 | 顺带完成，不增加 Phase |
| Phase 4: ReAct 循环增强 | **P2** 上下文预算管理 | 在 System Prompt 注入时集成预算检查 |
| Phase 5: 护栏 + 容错 | **P2** 会话连续性 | 在容错逻辑中集成 compaction 恢复 |
| Phase 6: 编排层集成 | **P3** 智能缓存 | 在编排层集成缓存管理 |

### 工时估算（更新）

| 优先级 | 优化项 | 新增文件 | 修改文件 | 估时 |
|--------|--------|----------|----------|------|
| **P0** | 检索结果两层分离 + docDetailTool | 1 | 4 | 2-3h |
| **P1** | 检索去重 | 0 | 3 | 1-2h |
| **P1** | Tool 输出沙盒化 | 0 | 9 | 2-3h |
| **P2** | 会话连续性 | 4 | 3 | 4-6h |
| **P2** | 上下文预算管理 | 1 | 2 | 1-2h |
| **P3** | 智能缓存 | 1 | 3 | 2-3h |
| — | 删除 §4.3.1 | 0 | 1 | 0.5h |

**总计：~13-20h，无需新增 Phase。**

### 关键依赖（更新）

- P0 依赖：pg_jieba 已配置（`jieba_config`），`ts_headline` 可用
- P1 依赖：P0 的摘要格式变更完成
- P2 依赖：Flyway migration 可在任何 Phase 前执行
- P1（Tool 输出沙盒化）依赖：无
- P2（上下文预算管理）依赖：P0 完成
- P3（智能缓存）依赖：P0 完成

---

## Context Mode 思想应用总结

| Context Mode 核心思想 | Agentic RAG 应用 | 优化项 |
|----------------------|------------------|--------|
| **Context Saving** — 沙盒工具将原始数据移出上下文窗口 | 检索 Tool 返回摘要，完整文档存储在 PostgreSQL | P0 检索结果两层分离 |
| **Session Continuity** — 事件索引到 FTS5，通过 BM25 搜索只检索相关内容 | agent_session_event 表 + agentEventLookupTool | P2 会话连续性 |
| **Sandbox Tools** — 代码在沙盒中执行，只有输出进入上下文 | 所有 Tool 输出统一 JSON 格式，控制在 200 token 以内 | P1 Tool 输出沙盒化 |
| **FTS5 + BM25 搜索** — 按需检索 | PostgreSQL JSONB + 全文搜索，按需获取详细信息 | P2 会话连续性 |
| **98% 节省** — 315 KB → 5.4 KB | 检索结果从 ~20KB 降至 ~2.7KB（87% reduction） | P0 检索结果两层分离 |
| **按需检索** — 不灌回全量数据 | docDetailTool 按需获取文档片段 | P0 检索结果两层分离 |
| **主动管理** — 不是被动压缩 | 上下文预算管理，动态调整 Tool 输出详细程度 | P2 上下文预算管理 |
| **智能缓存** — 避免重复处理 | 查询结果、文档详情、意图分类缓存 | P3 智能缓存 |
