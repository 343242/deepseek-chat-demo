# Agentic RAG 设计文档

> **分支**: `agentic-rag-dev`（基于 `eval-rag-dev`）
> **创建日期**: 2026-05-19
> **状态**: Draft

---

## 1. 背景与动机

### 1.1 现有架构

当前 RAG 采用**固定六阶段 Pipeline**，通过 `RetrievalAugmentationAdvisor` 以 Advisor 身份挂入 Advisor 链：

```
用户查询
  ↓ RewriteQueryTransformer（查询改写）
  ↓ HybridDocumentRetriever（pgvector + BM25 + RRF 融合，userId/teamId 隔离）
  ↓ MmrDocumentPostProcessor（MMR 多样性去冗余）
  ↓ BailianRerankPostProcessor（百炼 Rerank 语义精排）
  ↓ ParentDocumentPostProcessor（子块→父文档替换）
  ↓ 注入 LLM prompt → 生成回答
```

### 1.2 问题

| # | 问题 | 影响 |
|---|------|------|
| P1 | **Pipeline 固定顺序**：无论问题是否需要检索，都走完整 Pipeline | 简单事实查询被过度处理，token 浪费 |
| P2 | **LLM 无自主权**：只在最后一步生成回答，不能决定"是否检索"、"用什么策略"、"结果够不够" | 无法处理需要多轮检索、策略切换的复杂问题 |
| P3 | **单次检索**：检索结果不佳时无法自动改写查询重试 | 召回质量完全依赖首次查询 |
| P4 | **所有请求同质化**：不能根据问题类型选择检索策略（精确匹配走 BM25、语义匹配走向量） | 策略选择无弹性 |

### 1.3 目标

将 RAG 从 **Pipeline 模式** 升级为 **Agent 模式**：
- LLM 通过 `ToolCallAdvisor` 的 ReAct 循环自主编排检索策略
- 保留现有核心组件（`HybridDocumentRetriever`、`BailianRerankPostProcessor` 等），封装为 Tool
- **分离式 Tool 注册**：按意图识别结果动态选择暴露给 LLM 的 Tool 子集
- 新增 `AGENT` 对话模式，不影响现有 `SIMPLE` / `MULTI_TURN` 模式

---

## 2. 架构设计

### 2.1 整体架构（三层）

```
┌─────────────────────────────────────────────────────────────────┐
│                     第一层：意图识别 (Intent Router)              │
│                                                                 │
│   用户查询 → IntentClassifier（轻量 LLM 调用）                    │
│                                                                 │
│   ① 意图分类：                                                   │
│     ├── DIRECT_ANSWER  → 直接回答（不暴露任何 RAG Tool）          │
│     ├── RETRIEVAL      → 暴露检索类 Tool（vector/bm25/hybrid）    │
│     ├── DEEP_RETRIEVAL → 暴露全量 RAG Tool（检索+rerank+rewrite） │
│     └── GENERAL_TOOL   → 只暴露通用 Tool（Calculator/DateTime）   │
│                                                                 │
│   ② 查询分解（RETRIEVAL / DEEP_RETRIEVAL 时执行）：              │
│     将复杂问题拆解为多个独立子问题，供 Agent 按子问题逐一检索       │
│     例：「对比 RAG 和 Fine-tuning」→                               │
│       [子问题1: RAG 的知识更新机制]                                │
│       [子问题2: Fine-tuning 的知识更新机制]                        │
│       [子问题3: 两者对比分析]                                      │
│                                                                 │
│   输出：AgentIntent + subQueries[] + 动态 Tool 子集               │
└───────────────────────────────┬─────────────────────────────────┘
                                │
┌───────────────────────────────▼─────────────────────────────────┐
│                   第二层：Agent 编排 (ReAct Loop)                 │
│                                                                 │
│   ChatClient + ToolCallAdvisor                                  │
│     ├── 根据意图动态注入 Tool 子集                                 │
│     ├── LLM 自主决定调用顺序和次数                                 │
│     └── 中间状态通过 JSON Workspace 传递                           │
│                                                                 │
│   Workspace（JSON 中间状态）：                                     │
│     { "retrieved_docs": [...], "rewritten_queries": [...],       │
│       "reranked_docs": [...], "round": 2 }                      │
└───────────────────────────────┬─────────────────────────────────┘
                                │
┌───────────────────────────────▼─────────────────────────────────┐
│                   第三层：Tool 执行                               │
│                                                                 │
│   检索类 Tool            精炼类 Tool            通用 Tool         │
│   ├── vectorSearch       ├── rerank             ├── Calculator   │
│   ├── bm25Search         ├── queryRewrite       ├── DateTime     │
│   ├── hybridSearch       └── parentDocLookup    └── CodeExec     │
│   └── knowledgeBaseInfo                                          │
│                                                                 │
│   每个 Tool：                                                    │
│     1. 从 Workspace 读取输入                                     │
│     2. 执行操作                                                  │
│     3. 返回 JSON 格式结果 + 更新 Workspace                       │
└─────────────────────────────────────────────────────────────────┘
```

### 2.2 完整请求流程

```
用户: "对比 RAG 和 Fine-tuning 在知识更新场景的优劣"
                    │
                    ▼
        ┌─ 第一层：意图识别 + 查询分解 ─┐
        │ IntentClassifier                │
        │                                 │
        │ ① 意图分类: → DEEP_RETRIEVAL     │
        │                                 │
        │ ② 查询分解:                      │
        │   原始问题 → 拆解为子问题:        │
        │   [1] RAG 系统的知识更新机制       │
        │   [2] Fine-tuning 的知识更新机制   │
        │   [3] 两者在知识更新场景的对比      │
        └─────────────┬──────────────────┘
                      │ subQueries 写入 Workspace
                      │ 动态选择 Tool 子集
                      ▼
        ┌─ 第二层：Agent ReAct ──────────────┐
        │                                     │
        │ LLM: "按子问题逐一检索"              │
        │                                     │
        │ Round 1: 子问题[1]                   │
        │ → hybridSearchTool("RAG 知识更新")    │
        │ ← JSON: 8 docs                      │
        │                                     │
        │ Round 2: 子问题[2]                   │
        │ → hybridSearchTool("Fine-tuning 更新")│
        │ ← JSON: 5 docs                      │
        │                                     │
        │ LLM: "文档较多，需要精排"             │
        │ → rerankTool()                      │
        │ ← JSON: Top 5 docs                  │
        │                                     │
        │ Round 3: 子问题[3] (综合对比)         │
        │ LLM: 基于已检索文档综合分析           │
        │ → 无需额外检索，直接回答              │
        │                                     │
        │ LLM: 综合所有子问题结果生成最终回答     │
        └─────────────────────────────────────┘
```

### 2.3 意图识别层详细设计

#### Intent 枚举

```java
public enum AgentIntent {
    /** 直接回答 — 通用知识、闲聊、简单问答 */
    DIRECT_ANSWER,
    /** 检索类 — 需要知识库但不需精排 */
    RETRIEVAL,
    /** 深度检索 — 需要多轮检索+精排+改写 */
    DEEP_RETRIEVAL,
    /** 通用工具 — 数学计算、日期查询、代码执行等 */
    GENERAL_TOOL;
}
```

#### IntentResult — 分类结果

```java
/**
 * 意图分类 + 查询分解结果
 *
 * @param intent     意图分类
 * @param confidence 分类置信度
 * @param subQueries 分解后的子问题列表（DIRECT_ANSWER / GENERAL_TOOL 时为空）
 */
public record IntentResult(
    AgentIntent intent,
    double confidence,
    List<String> subQueries
) {
    /** 是否需要查询分解 */
    public boolean hasSubQueries() {
        return subQueries != null && !subQueries.isEmpty();
    }
}
```

#### IntentClassifier

```java
/**
 * 意图分类器 + 查询分解器 — 在 Agent ReAct 循环之前执行一次 LLM 调用
 *
 * 职责：
 * 1. 分析用户查询，判断意图分类（是否需要检索、检索深度）
 * 2. 对 RETRIEVAL / DEEP_RETRIEVAL 类型，将复杂问题拆解为独立子问题
 * 3. 返回意图 + 子问题列表
 *
 * 设计决策：
 * - 意图分类 + 查询分解合并在一次 LLM 调用中完成（减少延迟）
 * - 子问题写入 Workspace，供 Agent ReAct 循环按子问题逐一检索
 * - 简单问题（DIRECT_ANSWER / GENERAL_TOOL）不进行分解
 * - 独立于主 ChatModel，使用配置的意图识别模型（可用轻量快速模型降本）
 */
@Component
public class IntentClassifier {

    private final ChatClient intentChatClient;  // 独立的轻量模型
    private final IntentToolSetRegistry toolSetRegistry;

    /**
     * 分类意图 + 分解查询
     *
     * @param query 用户查询
     * @return 分类结果（意图 + 子问题列表）
     */
    public IntentResult classify(String query) {
        // 单次 LLM 调用，通过 Spring AI Structured Output 映射到 IntentResult
        // 输出示例：
        // {
        //   "intent": "DEEP_RETRIEVAL",
        //   "confidence": 0.95,
        //   "subQueries": [
        //     "RAG 系统的知识更新机制",
        //     "Fine-tuning 模型的知识更新方式",
        //     "两者在知识更新场景的优劣对比"
        //   ]
        // }
    }
}
```

#### IntentToolSetRegistry — 意图→Tool 子集映射

```java
/**
 * 意图→Tool 子集映射注册表
 *
 * 根据意图识别结果，动态决定暴露给 LLM 的 Tool 子集。
 * 避免向 LLM 暴露所有工具，减少选择困难和误调用。
 */
@Component
public class IntentToolSetRegistry {

    private final Map<AgentIntent, List<ToolCallback>> toolSetMap;

    public IntentToolSetRegistry(
            List<RagTool> ragTools,       // 所有 RAG Tool
            List<Object> generalToolBeans  // CalculatorTools, DateTimeTools 等
    ) {
        // DIRECT_ANSWER → 空集（LLM 直接回答，无工具）
        // RETRIEVAL → vectorSearch, bm25Search, hybridSearch, knowledgeBaseInfo
        // DEEP_RETRIEVAL → 全量 RAG Tool
        // GENERAL_TOOL → Calculator, DateTime, CodeExecution
    }

    public List<ToolCallback> getToolSet(AgentIntent intent) {
        return toolSetMap.getOrDefault(intent, Collections.emptyList());
    }
}
```

#### 意图识别 + 查询分解 Prompt

```
分析用户查询，完成两个任务：

任务 1 — 意图分类：
- DIRECT_ANSWER: 通用知识、闲聊、简单问答，不需要知识库
- RETRIEVAL: 需要知识库检索，单次检索即可满足，问题单一明确
- DEEP_RETRIEVAL: 复杂问题，需要多轮检索、查询改写、语义精排
- GENERAL_TOOL: 需要数学计算、日期查询、代码执行等工具

任务 2 — 查询分解（仅 RETRIEVAL / DEEP_RETRIEVAL 时执行）：
将用户的原始问题拆解为独立、可并行检索的子问题。

分解规则：
- 每个子问题应是一个独立的信息需求，可单独检索
- 对比类问题拆为各方的独立查询 + 对比关系查询
- 多条件问题拆为各条件的独立查询
- 简单单一问题不需要分解，保持原样作为唯一子问题
- 子问题数量控制在 1-5 个
- 子问题应保留原始问题的核心术语和上下文

示例：
  原始：「对比 RAG 和 Fine-tuning 在知识更新场景的优劣」
  分解：[
    "RAG 系统如何实现知识更新",
    "Fine-tuning 模型如何更新知识",
    "RAG 和 Fine-tuning 在知识更新场景的优劣对比"
  ]

  原始：「Spring Boot 的自动装配原理是什么？」
  分解：["Spring Boot 自动装配原理"]（单一问题，不分解）

输出格式（JSON）：
{
  "intent": "DIRECT_ANSWER|RETRIEVAL|DEEP_RETRIEVAL|GENERAL_TOOL",
  "confidence": 0.0-1.0,
  "subQueries": ["子问题1", "子问题2", ...]
}

注意：DIRECT_ANSWER 和 GENERAL_TOOL 类型，subQueries 为空数组 []
```

#### 意图识别模型配置

```yaml
app:
  agent:
    intent-model: deepseek/deepseek-chat   # 意图识别用轻量模型
    intent-temperature: 0.1                 # 低温度，分类任务追求确定性
```

### 2.4 Tool Workspace — JSON 中间状态

Tool 之间通过结构化 JSON Workspace 传递中间结果，而非 ThreadLocal 的 `List<Document>`。

#### Workspace 数据结构

```java
/**
 * Agent Tool Workspace — 请求级别的 JSON 中间状态
 *
 * 设计原则：
 * 1. 所有 Tool 的输入输出都是 JSON 字符串（可序列化、可调试）
 * 2. Workspace 维护一个 JSON 文档，记录检索中间状态
 * 3. Tool 从 Workspace 读取前置结果，执行后更新 Workspace
 * 4. 生命周期：单次 ChatRequest，请求结束清理
 *
 * 线程安全：单次请求内单线程执行，无需同步
 */
public class ToolWorkspace {

    private final Long userId;
    @Nullable
    private final Long teamId;

    /** JSON 格式的中间状态 */
    private final ObjectMapper objectMapper;
    private ObjectNode state;

    // --- Workspace 操作 ---

    // ===== 查询分解相关 =====

    /** 设置意图分类结果 */
    public void setIntent(AgentIntent intent) { ... }

    /** 设置分解后的子问题 */
    public void setSubQueries(List<String> subQueries) { ... }

    /** 获取子问题列表 */
    public List<String> getSubQueries() { ... }

    /** 标记子问题已完成 */
    public void markSubQueryCompleted(int index) { ... }

    /** 获取未完成的子问题索引列表 */
    public List<Integer> getPendingSubQueryIndices() { ... }

    // ===== 检索结果相关 =====

    /** 获取已检索的文档列表 */
    public List<RetrievedDocument> getRetrievedDocs() { ... }

    /** 追加检索结果（关联到指定子问题） */
    public void addRetrievedDocs(List<RetrievedDocument> docs, int subQueryIndex) { ... }

    /** 追加检索结果（不关联子问题） */
    public void addRetrievedDocs(List<RetrievedDocument> docs) { ... }

    /** 替换检索结果（如 rerank 后） */
    public void replaceRetrievedDocs(List<RetrievedDocument> docs) { ... }

    /** 获取指定子问题的检索结果 */
    public List<RetrievedDocument> getDocsForSubQuery(int subQueryIndex) { ... }

    // ===== 查询改写相关 =====

    /** 获取改写后的查询 */
    public List<String> getRewrittenQueries() { ... }

    /** 添加改写查询 */
    public void addRewrittenQueries(List<String> queries) { ... }

    // ===== 状态追踪 =====

    /** 获取当前检索轮次 */
    public int getRetrievalRound() { ... }

    /** 递增检索轮次 */
    public void incrementRound() { ... }

    /** 导出完整状态（调试用） */
    public String exportState() { ... }
}
```

#### Workspace JSON 示例

```json
{
  "userId": 42,
  "teamId": null,
  "intent": "DEEP_RETRIEVAL",
  "subQueries": [
    "RAG 系统如何实现知识更新",
    "Fine-tuning 模型如何更新知识",
    "RAG 和 Fine-tuning 在知识更新场景的优劣对比"
  ],
  "completedSubQueries": [0, 1],
  "round": 2,
  "retrievedDocs": [
    {
      "docId": "abc123",
      "content": "RAG 系统通过外部知识库增强 LLM...",
      "score": 0.89,
      "source": "hybridSearch",
      "subQueryIndex": 0,
      "metadata": { "fileName": "rag-intro.pdf", "pageIndex": 3 }
    }
  ],
  "rewrittenQueries": [],
  "reranked": false,
  "parentDocResolved": false
}
```

#### Tool 返回格式

每个 Tool 统一返回 JSON 字符串给 LLM：

```java
// Tool 返回格式示例
{
  "status": "success",
  "action": "hybridSearch",
  "summary": "检索到 8 个相关文档片段",
  "docCount": 8,
  "topScores": [0.89, 0.85, 0.82],
  "workspaceUpdated": true
}
```

这样 LLM 收到的是结构化的摘要信息，而非大段原始文本。

### 2.5 新增 ChatMode

```java
public enum ChatMode {
    SIMPLE,       // 单轮，无记忆
    MULTI_TURN,   // 多轮，有记忆 + RAG Pipeline
    AGENT;        // Agent 模式：意图识别 → 动态 Tool 子集 → ReAct 循环
}
```

### 2.6 Advisor 链对比

| Advisor | SIMPLE | MULTI_TURN | AGENT |
|---------|--------|------------|-------|
| ConversationContextAdvisor | ❌ | ✅ | ✅ |
| RateLimitAdvisor | ✅ | ✅ | ✅ |
| ContentFilterAdvisor | ✅ | ✅ | ✅ |
| RetrievalAugmentationAdvisor | ❌ | ✅ (ragEnabled) | ❌ (被 Tool 替代) |
| ToolCallAdvisor | ✅ (有 Tool 时) | ✅ (有 Tool 时) | ✅ (核心，独立实例) |
| MessageChatMemoryAdvisor | ❌ | ✅ | ✅ |

**关键区别**：
1. AGENT 模式使用**独立的 ToolCallAdvisor**，只挂载意图识别后的 Tool 子集
2. RAG 不再通过 `RetrievalAugmentationAdvisor` 固定 Pipeline 执行
3. Agent 模式**先阻塞式**响应，流式支持在后续迭代

---

## 3. Tool 设计

### 3.1 Tool 清单

| Tool | 描述 | 封装组件 | 输入来源 | 输出更新 |
|------|------|----------|----------|----------|
| `vectorSearchTool` | 向量语义检索 | `VectorStore.similaritySearch()` | Workspace.userId/teamId | Workspace.retrievedDocs |
| `bm25SearchTool` | BM25 全文检索 | `VectorStoreMapper.bm25Search()` | Workspace.userId/teamId | Workspace.retrievedDocs |
| `hybridSearchTool` | 混合检索 + RRF | `HybridDocumentRetriever` 核心逻辑 | Workspace | Workspace.retrievedDocs |
| `rerankTool` | 语义精排 | `BailianRerankPostProcessor` 核心逻辑 | Workspace.retrievedDocs | Workspace.retrievedDocs (替换) |
| `queryRewriteTool` | 查询改写 | `RewriteQueryTransformer` prompt | LLM 调用 | Workspace.rewrittenQueries |
| `parentDocLookupTool` | 子块→父文档 | `ParentDocumentPostProcessor` | Workspace.retrievedDocs | Workspace.retrievedDocs (替换) |
| `knowledgeBaseInfoTool` | 知识库元信息 | `VectorStoreMapper` 统计查询 | Workspace.userId/teamId | 无（直接返回） |

### 3.2 包结构

```
com.demo.chat.rag.agent/
├── intent/
│   ├── AgentIntent.java              // 意图枚举
│   ├── IntentClassifier.java         // 意图分类器
│   ├── IntentResult.java             // 分类结果 record
│   └── IntentToolSetRegistry.java    // 意图→Tool 子集映射
├── workspace/
│   ├── ToolWorkspace.java            // JSON 中间状态
│   ├── ToolWorkspaceHolder.java      // ThreadLocal 传递
│   ├── ToolWorkspaceFactory.java     // 按请求创建
│   └── RetrievedDocument.java        // 检索结果 DTO record
├── tool/
│   ├── RagTool.java                  // RAG Tool 标记接口
│   ├── VectorSearchTool.java         // 向量检索
│   ├── Bm25SearchTool.java           // BM25 全文检索
│   ├── HybridSearchTool.java         // 混合检索 + RRF
│   ├── RerankTool.java               // 语义精排
│   ├── QueryRewriteTool.java         // 查询改写
│   ├── ParentDocLookupTool.java      // 子块→父文档
│   └── KnowledgeBaseInfoTool.java    // 知识库元信息
└── advisor/
    ├── AgentContextCleanupAdvisor.java  // ThreadLocal 清理
    └── AgentSystemPromptAdvisor.java    // 动态 System Prompt 注入
```

### 3.3 RagTool 标记接口 + RetrievedDocument

#### RagTool 标记接口

```java
/**
 * RAG Tool 标记接口
 *
 * 用于 IntentToolSetRegistry 区分 RAG Tool 和通用 Tool。
 * 实现 RagTool 的 @Component Bean 会被自动归类为 RAG Tool。
 */
public interface RagTool {}
```

#### RetrievedDocument — 检索结果 DTO

```java
/**
 * 检索结果 DTO — Tool Workspace 中的文档表示
 *
 * @param docId         文档 ID
 * @param content       文档内容
 * @param score         相关性分数
 * @param source        来源 Tool 名称（如 hybridSearch）
 * @param subQueryIndex 关联的子问题索引（-1 表示未关联）
 * @param metadata      文档元信息（文件名、页码等）
 */
public record RetrievedDocument(
    String docId,
    String content,
    double score,
    String source,
    int subQueryIndex,
    Map<String, Object> metadata
) {}
```

### 3.4 Tool 详细设计

#### VectorSearchTool

```java
@Component
public class VectorSearchTool implements RagTool {

    private final VectorStore vectorStore;
    private final RagRetrievalProperties properties;

    @Tool(description = "在知识库中进行向量语义检索。适用于概念性、语义性的问题。" +
                        "返回与查询语义最相似的文档片段。")
    public String vectorSearch(
            @ToolParam(description = "检索查询文本") String query,
            @ToolParam(description = "返回结果数量，默认10") @Nullable Integer topK) {
        ToolWorkspace ws = ToolWorkspaceHolder.get();
        // 从 ws 获取 userId/teamId 构建过滤条件
        // 调用 vectorStore.similaritySearch()
        // 追加到 ws.retrievedDocs
        // 返回 JSON 摘要
    }
}
```

#### HybridSearchTool

```java
@Component
public class HybridSearchTool implements RagTool {

    private final VectorStore vectorStore;
    private final VectorStoreMapper vectorStoreMapper;
    private final QueryNormalizer queryNormalizer;
    private final RagRetrievalProperties properties;

    @Tool(description = "混合检索：同时使用向量语义检索和 BM25 全文检索，" +
                        "通过 RRF (Reciprocal Rank Fusion) 融合结果。" +
                        "适用于大多数检索场景，兼顾语义匹配和精确关键词匹配。")
    public String hybridSearch(
            @ToolParam(description = "检索查询文本") String query,
            @ToolParam(description = "返回结果数量，默认20") @Nullable Integer topK) {
        ToolWorkspace ws = ToolWorkspaceHolder.get();
        // 复用 HybridDocumentRetriever 的核心逻辑
        // 追加到 ws.retrievedDocs
        // 返回 JSON 摘要
    }
}
```

#### RerankTool

```java
@Component
public class RerankTool implements RagTool {

    private final BailianRerankPostProcessor rerankProcessor;

    @Tool(description = "对已检索的文档进行语义精排。当检索结果较多且需要精选最相关文档时使用。" +
                        "基于百炼 Rerank API，返回重新排序后的 Top-N 文档。")
    public String rerank(
            @ToolParam(description = "原始查询文本，用于相关性判断") String query,
            @ToolParam(description = "精排后返回的文档数量，默认5") @Nullable Integer topN) {
        ToolWorkspace ws = ToolWorkspaceHolder.get();
        // 从 ws.retrievedDocs 获取待排序文档
        // 调用 BailianRerankPostProcessor 核心逻辑
        // ws.replaceRetrievedDocs(reranked)
        // 返回 JSON 摘要
    }
}
```

#### QueryRewriteTool

```java
@Component
public class QueryRewriteTool implements RagTool {

    private final ChatClient.Builder chatClientBuilder;

    @Tool(description = "改写查询以提升检索效果。当原始查询检索结果不理想时，" +
                        "使用此工具生成更精确、更适合检索的查询。" +
                        "支持多角度改写，可一次生成多个变体查询。")
    public String rewriteQuery(
            @ToolParam(description = "需要改写的原始查询") String query,
            @ToolParam(description = "改写角度，如：更具体、同义替换、拆分子问题",
                       required = false) @Nullable String perspective) {
        ToolWorkspace ws = ToolWorkspaceHolder.get();
        // 调用 LLM 改写查询（复用 RewriteQueryTransformer 的 prompt）
        // ws.addRewrittenQueries(rewritten)
        // 返回 JSON 摘要
    }
}
```

#### ParentDocLookupTool

```java
@Component
public class ParentDocLookupTool implements RagTool {

    private final VectorStoreMapper vectorStoreMapper;

    @Tool(description = "将检索到的文档片段替换为其所属的完整父文档。" +
                        "当需要更完整的上下文信息时使用。")
    public String parentDocLookup() {
        ToolWorkspace ws = ToolWorkspaceHolder.get();
        // 从 ws.retrievedDocs 获取子块文档
        // 复用 ParentDocumentPostProcessor 核心逻辑
        // ws.replaceRetrievedDocs(parentDocs)
        // 返回 JSON 摘要
    }
}
```

#### KnowledgeBaseInfoTool

```java
@Component
public class KnowledgeBaseInfoTool implements RagTool {

    private final VectorStoreMapper vectorStoreMapper;

    @Tool(description = "查询当前知识库的元信息，包括文档数量、分块数量等。" +
                        "帮助判断知识库中是否有足够的相关内容来回答问题。")
    public String getKnowledgeBaseInfo() {
        ToolWorkspace ws = ToolWorkspaceHolder.get();
        // 从 ws 获取 userId/teamId
        // 查询文档数量、分块数量、最近更新时间
        // 返回 JSON 格式的知识库统计
    }
}
```

### 3.5 ToolWorkspaceHolder — ThreadLocal 传递

```java
/**
 * ThreadLocal 传递 ToolWorkspace
 *
 * 生命周期：
 * - set: ChatAdvisorChainFactory.buildChain() AGENT 分支
 * - get: 各 RAG Tool 执行时
 * - clear: AgentContextCleanupAdvisor（finally 保证）
 */
public final class ToolWorkspaceHolder {

    private static final ThreadLocal<ToolWorkspace> WORKSPACE = new ThreadLocal<>();

    public static void set(ToolWorkspace workspace) { WORKSPACE.set(workspace); }
    public static ToolWorkspace get() { return WORKSPACE.get(); }
    public static void clear() { WORKSPACE.remove(); }

    private ToolWorkspaceHolder() {}
}
```

---

## 4. 核心改动

### 4.1 改动清单

| # | 文件 | 改动类型 | 说明 |
|---|------|----------|------|
| **意图识别层** | | | |
| 1 | `AgentIntent.java` | 新增 | 意图枚举 |
| 2 | `IntentResult.java` | 新增 | 分类结果 record |
| 3 | `IntentClassifier.java` | 新增 | 意图分类器（独立 LLM 调用） |
| 4 | `IntentToolSetRegistry.java` | 新增 | 意图→Tool 子集映射 |
| **Workspace 层** | | | |
| 5 | `ToolWorkspace.java` | 新增 | JSON 中间状态管理 |
| 6 | `ToolWorkspaceHolder.java` | 新增 | ThreadLocal 传递 |
| 7 | `ToolWorkspaceFactory.java` | 新增 | 按请求创建 Workspace |
| 8 | `RetrievedDocument.java` | 新增 | 检索结果 DTO record（含 subQueryIndex 关联子问题） |
| **RAG Tool 层** | | | |
| 9 | `RagTool.java` | 新增 | RAG Tool 标记接口 |
| 10 | `VectorSearchTool.java` | 新增 | 向量检索 Tool |
| 11 | `Bm25SearchTool.java` | 新增 | BM25 全文检索 Tool |
| 12 | `HybridSearchTool.java` | 新增 | 混合检索 Tool |
| 13 | `RerankTool.java` | 新增 | Rerank Tool |
| 14 | `QueryRewriteTool.java` | 新增 | 查询改写 Tool |
| 15 | `ParentDocLookupTool.java` | 新增 | 父文档查找 Tool |
| 16 | `KnowledgeBaseInfoTool.java` | 新增 | 知识库元信息 Tool |
| **Advisor 层** | | | |
| 17 | `AgentContextCleanupAdvisor.java` | 新增 | ThreadLocal 清理 |
| 18 | `AgentSystemPromptAdvisor.java` | 新增 | 根据意图动态注入 System Prompt |
| **编排层改动** | | | |
| 19 | `ChatMode.java` | 修改 | 新增 `AGENT` 枚举值 |
| 20 | `AgentModeStrategy.java` | 新增 | AGENT 模式策略 |
| 21 | `ChatAdvisorChainFactory.java` | 修改 | AGENT 分支：意图识别→独立 ToolCallAdvisor |
| 22 | `ChatRequestSpecFactory.java` | 修改 | AGENT 模式适配 |
| 23 | `ChatServiceImpl.java` | 修改 | AGENT 模式支持（阻塞式） |
| **配置** | | | |
| 24 | `AgentRagProperties.java` | 新增 | Agent 模式配置 |
| 25 | `application.yml` | 修改 | 新增 app.agent.* 配置 |
| **容错与安全** | | | |
| 26 | `ToolResult.java` | 新增 | Tool 调用统一结果 record（success/failure + errorCategory） |
| 27 | `AgentGuardrails.java` | 新增 | ReAct 循环护栏（迭代总数/token 消耗/连续 Tool 三指标） |
| 28 | `AgentDegradationStrategy.java` | 新增 | Agent 全局降级策略（降级到 MULTI_TURN） |
| 29 | `AgentTrace.java` | 新增 | Agent 执行追踪记录 |
| 30 | `ToolCallRecord.java` | 新增 | 单次 Tool 调用记录 |

### 4.2 ChatAdvisorChainFactory 改动

```java
// buildChain() 新增 AGENT 分支
if (modeStrategy.getMode() == ChatMode.AGENT && agentProperties.enabled()) {
    // 1. 上下文 Advisor
    if (modeStrategy.isContextEnabled()) {
        chain.add(new ConversationContextAdvisor(conversationId));
    }

    // 2. 全局 Advisor（限流、内容过滤）
    chain.addAll(getGlobalAdvisors());

    // 3. 意图识别 + 查询分解（单次 LLM 调用，完成分类和分解）
    IntentResult intent = intentClassifier.classify(request.message());

    // 4. 创建 ToolWorkspace + 写入子问题 + 设置 ThreadLocal
    ToolWorkspace workspace = workspaceFactory.create(userId, teamId);
    workspace.setIntent(intent.intent());
    if (intent.hasSubQueries()) {
        workspace.setSubQueries(intent.subQueries());
    }
    ToolWorkspaceHolder.set(workspace);

    // 5. 根据意图选择 Tool 子集
    List<ToolCallback> toolSet = intentToolSetRegistry.getToolSet(intent.intent());

    // 6. 创建独立的 ToolCallAdvisor（只挂载选中的 Tool）
    ToolCallAdvisor agentToolCallAdvisor = ToolCallAdvisor.builder()
        .toolCallingManager(toolCallingManager)
        .toolCallbacks(toolSet)
        .advisorOrder(BaseAdvisor.HIGHEST_PRECEDENCE + 300)
        .build();
    chain.add(agentToolCallAdvisor);

    // 7. 动态 System Prompt（根据意图注入不同指令）
    chain.add(new AgentSystemPromptAdvisor(intent));

    // 8. Memory
    if (modeStrategy.isMemoryEnabled()) {
        chain.add(MessageChatMemoryAdvisor.builder(chatMemory).build());
    }

    // 9. 清理 Advisor
    chain.add(new AgentContextCleanupAdvisor());

    return chain;
}
```

### 4.3 AgentSystemPromptAdvisor — 动态 System Prompt

根据意图识别结果注入不同的 System Prompt：

```java
/**
 * 根据意图动态注入 System Prompt
 *
 * 不同意图类型需要不同的行为指导：
 * - DIRECT_ANSWER: 无工具可用，引导 LLM 直接回答
 * - RETRIEVAL: 提供检索工具使用指南
 * - DEEP_RETRIEVAL: 提供完整的深度检索策略指南
 * - GENERAL_TOOL: 提供通用工具使用指南
 */
public class AgentSystemPromptAdvisor implements CallAroundAdvisor {

    private final IntentResult intent;
    private final AgentRagProperties properties;

    @Override
    public AdvisedResponse aroundCall(AdvisedRequest request, CallAroundAdvisorChain chain) {
        String systemPrompt = resolvePrompt(intent.intent());
        // 注入 systemPrompt 到 request
        return chain.nextAroundCall(request.withSystemText(systemPrompt));
    }

    private String resolvePrompt(AgentIntent intent) {
        return switch (intent) {
            case DIRECT_ANSWER -> properties.directAnswerPrompt();
            case RETRIEVAL -> properties.retrievalPrompt();
            case DEEP_RETRIEVAL -> properties.deepRetrievalPrompt();
            case GENERAL_TOOL -> properties.generalToolPrompt();
        };
    }
}
```

### 4.4 配置设计

```yaml
app:
  agent:
    enabled: true
    # 意图识别
    intent-model: deepseek/deepseek-chat    # 独立轻量模型
    intent-temperature: 0.1                  # 低温度，分类任务
    # ReAct 循环
    max-tool-iterations: 10                  # 防止无限循环
    # 动态 System Prompt
    direct-answer-prompt: "..."
    retrieval-prompt: "..."
    deep-retrieval-prompt: "..."
    general-tool-prompt: "..."
```

```java
@ConfigurationProperties(prefix = "app.agent")
public record AgentRagProperties(
    boolean enabled,
    String intentModel,
    Double intentTemperature,
    int maxToolIterations,
    String directAnswerPrompt,
    String retrievalPrompt,
    String deepRetrievalPrompt,
    String generalToolPrompt
) {
    public AgentRagProperties {
        if (maxToolIterations <= 0) maxToolIterations = 10;
        if (intentTemperature == null) intentTemperature = 0.1;
    }
}
```

---

## 5. 与现有代码的兼容性

### 5.1 零影响原则

| 现有功能 | 影响 | 说明 |
|----------|------|------|
| SIMPLE 模式 | 零影响 | 不走 AGENT 分支 |
| MULTI_TURN 模式 | 零影响 | 仍用 `RetrievalAugmentationAdvisor` |
| `RagAdvisorFactory` | 保留 | MULTI_TURN 模式继续使用 |
| `HybridDocumentRetriever` | 保留 + 复用 | Tool 复用其核心逻辑，不修改原类 |
| `ToolRegistry` | 保留 | AGENT 模式不使用 ToolRegistry，改用 IntentToolSetRegistry |
| `CalculatorTools` / `DateTimeTools` | 保留 | Agent 模式通过 IntentToolSetRegistry 按需暴露 |
| ETL Pipeline | 零影响 | 文档入库流程不变 |
| 评估模块 | 零影响 | 评估仍基于 `RetrievalAugmentationAdvisor` |

### 5.2 组件复用关系

```
HybridDocumentRetriever（现有）
    ↑ 复用核心逻辑
HybridSearchTool（新增）

BailianRerankPostProcessor（现有）
    ↑ 复用 rerank 调用逻辑
RerankTool（新增）

ParentDocumentPostProcessor（现有）
    ↑ 复用父子查找逻辑
ParentDocLookupTool（新增）

RewriteQueryTransformer（现有）
    ↑ 复用改写 prompt
QueryRewriteTool（新增）

ModelRouter + ProviderRegistry（现有）
    ↑ 复用模型路由
IntentClassifier（新增）
```

---

## 6. 工程化容错设计

### 6.1 故障模式全景

```
Agent 请求的完整故障点：

用户查询
  │
  ├─ F1: 意图识别 LLM 调用失败（网络超时 / 模型不可用 / 响应解析失败）
  │
  ├─ F2: 查询分解失败（LLM 返回空 subQueries / 格式不合法）
  │
  ├─ F3: 意图识别错误（误分类 → 工具集不匹配）
  │
  ├─ F4: ToolCallAdvisor 初始化失败（无可用 ToolCallback）
  │
  ├─ F5: 单次 Tool 调用失败
  │     ├─ F5a: 向量检索失败（VectorStore 不可用 / 超时）
  │     ├─ F5b: BM25 检索失败（数据库连接异常 / SQL 错误）
  │     ├─ F5c: Rerank 失败（百炼 API 不可用 / 超时 / 额度耗尽）
  │     ├─ F5d: 查询改写失败（LLM 调用失败）
  │     └─ F5e: 父文档查找失败（数据库异常）
  │
  ├─ F6: 循环不收敛（迭代超限 / token 超限 / 同一 Tool 连续调用超限）
  │
  ├─ F7: Workspace 状态损坏（并发写入 / 序列化失败）
  │
  ├─ F8: ThreadLocal 泄漏（异常路径未清理）
  │
  ├─ F9: 累计 token 超过模型上下文窗口 80%
  │
  └─ F10: 主 ChatModel 调用失败（生成最终回答时模型不可用）
```

### 6.2 分层容错策略

#### 第一层：意图识别容错

```java
@Component
public class IntentClassifier {

    private static final Logger log = LoggerFactory.getLogger(IntentClassifier.class);

    /** 最大重试次数 */
    private static final int MAX_RETRIES = 2;
    /** 单次调用超时 */
    private static final Duration INTENT_TIMEOUT = Duration.ofSeconds(5);

    /** 安全默认值：降级到 DEEP_RETRIEVAL（暴露全量 Tool，宁可多检索不漏检） */
    private static final IntentResult SAFE_FALLBACK = new IntentResult(
        AgentIntent.DEEP_RETRIEVAL, 0.0, Collections.emptyList()
    );

    public IntentResult classify(String query) {
        // 1. 空查询保护
        if (query == null || query.isBlank()) {
            return SAFE_FALLBACK;
        }

        // 2. 带重试的 LLM 调用
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            try {
                IntentResult result = doClassify(query);
                // 3. 结构校验：subQueries 为 null 时补空列表
                return validate(result);
            } catch (JsonProcessingException e) {
                // LLM 返回了无法解析的 JSON
                log.warn("Intent classification parse failed (attempt {}): {}",
                    attempt, e.getMessage());
            } catch (ApiException e) {
                // 模型 API 不可用
                log.warn("Intent classification API error (attempt {}): status={}",
                    attempt, e.getStatusCode());
            } catch (Exception e) {
                log.error("Intent classification unexpected error (attempt {})", attempt, e);
            }
        }

        // 3. 全部重试失败 → 降级到安全默认值
        log.warn("Intent classification failed after {} retries, falling back to {}",
            MAX_RETRIES, SAFE_FALLBACK.intent());
        return SAFE_FALLBACK;
    }

    private IntentResult validate(IntentResult result) {
        // intent 不能为 null
        if (result.intent() == null) {
            return SAFE_FALLBACK;
        }
        // subQueries 为 null 时补空列表
        List<String> queries = result.subQueries() != null
            ? result.subQueries() : Collections.emptyList();
        // subQueries 数量限制
        if (queries.size() > 5) {
            queries = queries.subList(0, 5);
        }
        return new IntentResult(result.intent(), result.confidence(), queries);
    }
}
```

**容错规则**：
- 意图识别失败 → 降级为 `DEEP_RETRIEVAL`（暴露全量 Tool，最安全策略）
- 查询分解失败 → 不分解，用原始查询作为唯一子问题
- 意图识别超时 → 2 次重试 + 5s 超时 → 降级

#### 第二层：Tool 调用容错

```java
/**
 * Tool 调用结果 — 统一的返回格式
 */
public record ToolResult(
    boolean success,
    String action,
    String summary,
    @Nullable String errorMessage,
    @Nullable String errorCategory,
    @Nullable List<RetrievedDocument> documents,
    long durationMs
) {
    /** 成功结果 */
    public static ToolResult success(String action, String summary,
                                     List<RetrievedDocument> docs, long durationMs) {
        return new ToolResult(true, action, summary, null, null, docs, durationMs);
    }

    /** 失败结果 — 供 LLM 决策是否重试或换策略 */
    public static ToolResult failure(String action, String errorMessage,
                                     String errorCategory, long durationMs) {
        return new ToolResult(false, action, null, errorMessage,
            errorCategory, null, durationMs);
    }

    /** 序列化为 JSON 返回给 LLM */
    public String toJson() {
        // 返回结构化 JSON，让 LLM 知道失败原因并决策
        // success=false 时包含 errorMessage，引导 LLM 换策略
    }
}
```

**每个 Tool 的容错模板**：

```java
@Component
public class HybridSearchTool implements RagTool {

    private static final Duration TOOL_TIMEOUT = Duration.ofSeconds(10);

    @Tool(description = "...")
    public String hybridSearch(String query, @Nullable Integer topK) {
        long start = System.currentTimeMillis();
        try {
            // 参数校验
            if (query == null || query.isBlank()) {
                return ToolResult.failure("hybridSearch",
                    "查询文本不能为空", "INVALID_INPUT", 0).toJson();
            }

            ToolWorkspace ws = ToolWorkspaceHolder.get();
            if (ws == null) {
                return ToolResult.failure("hybridSearch",
                    "Workspace 未初始化", "INTERNAL_ERROR", 0).toJson();
            }

            // 执行检索（带超时保护）
            List<Document> docs = doHybridSearch(query, topK, ws);

            long duration = System.currentTimeMillis() - start;
            List<RetrievedDocument> retrieved = toRetrievedDocs(docs, ws.getCurrentSubQueryIndex());
            ws.addRetrievedDocs(retrieved);

            return ToolResult.success("hybridSearch",
                formatSummary(query, retrieved.size()),
                retrieved, duration).toJson();

        } catch (DataAccessException e) {
            // 数据库异常 — 不可重试，建议 LLM 换策略
            long duration = System.currentTimeMillis() - start;
            log.error("Hybrid search DB error", e);
            return ToolResult.failure("hybridSearch",
                "数据库暂时不可用，请尝试其他检索方式",
                "DB_ERROR", duration).toJson();

        } catch (ApiException e) {
            // API 异常（如 embedding 服务不可用）— 可重试
            long duration = System.currentTimeMillis() - start;
            log.error("Hybrid search API error: status={}", e.getStatusCode(), e);
            return ToolResult.failure("hybridSearch",
                "检索服务暂时不可用，可以稍后重试或尝试 bm25Search",
                "API_ERROR", duration).toJson();

        } catch (Exception e) {
            long duration = System.currentTimeMillis() - start;
            log.error("Hybrid search unexpected error", e);
            return ToolResult.failure("hybridSearch",
                "检索发生未知错误",
                "UNKNOWN_ERROR", duration).toJson();
        }
    }
}
```

**关键原则**：
- **Tool 永远不抛异常到 ToolCallAdvisor**：所有异常捕获后转为 `ToolResult.failure()`
- **失败信息指导 LLM**：`errorMessage` 明确告知 LLM 失败原因和建议（"尝试 bm25Search"）
- **错误分类**：`errorCategory` 区分可重试（`API_ERROR`）和不可重试（`DB_ERROR`、`INVALID_INPUT`）
- **超时保护**：每个 Tool 设定执行超时，防止数据库慢查询或 API 无响应阻塞整个 ReAct 循环

#### 第三层：循环护栏（三指标）

```java
/**
 * Agent 循环护栏 — 跟踪三个指标，任一超标立即跳出 ReAct 循环
 *
 * 指标 1：循环迭代总次数
 *   - 整个 ReAct 循环的总轮次（LLM 决策 → Tool 执行 → 结果回传 = 1 轮）
 *   - 默认上限：10 次
 *   - 防御：无意义的无限循环
 *
 * 指标 2：累计 Token 消耗
 *   - 默认上限 = 当前模型最大上下文窗口 × 0.8
 *   - 涵盖：意图识别 LLM + ReAct 每轮（LLM 推理 + Tool 结果回传）
 *   - 防御：token 爆炸导致 API 报错或成本失控
 *
 * 指标 3：同一 Tool 连续调用次数
 *   - 跟踪每个 Tool 的连续调用次数，同一 Tool 连续调用超过阈值视为死循环
 *   - 默认阈值：3 次
 *   - 防御：LLM 卡在某个 Tool 上反复调用（如反复改写查询但结果无改善）
 *   - 注意：不同 Tool 交替调用会重置计数器
 */
@Component
public class AgentGuardrails {

    private final AgentRagProperties properties;
    private final ProviderRegistry providerRegistry;

    /** 上一次调用的 Tool 名称，用于连续调用检测 */
    private String lastToolName;
    private int consecutiveCount;

    /**
     * 计算当前模型的 token 上限
     * 默认 = 模型上下文窗口 × 0.8，为 System Prompt + 输出预留 20%
     */
    public int resolveTokenLimit(String compositeModelId) {
        int contextWindow = providerRegistry.getContextWindowSize(compositeModelId);
        double ratio = properties.contextWindowRatio(); // 默认 0.8
        return (int) (contextWindow * ratio);
    }

    /**
     * 检查是否允许继续 ReAct 循环
     *
     * @param iteration     当前迭代轮次（从 1 开始，整个 ReAct 循环的总轮次）
     * @param tokensUsed    累计 token 消耗
     * @param tokenLimit    token 上限
     * @param currentTool   当前即将调用的 Tool 名称
     * @return 检查结果
     */
    public GuardrailCheck check(int iteration, int tokensUsed,
                                 int tokenLimit, String currentTool) {
        // 指标 1：循环迭代总次数
        if (iteration > properties.maxToolIterations()) {
            return GuardrailCheck.stop(
                "ITERATION_LIMIT",
                "已达到最大调用轮次 (%d/%d)，停止检索。"
                    .formatted(iteration, properties.maxToolIterations()));
        }

        // 指标 2：累计 token 消耗
        if (tokensUsed >= tokenLimit) {
            return GuardrailCheck.stop(
                "TOKEN_LIMIT",
                "累计 token 消耗已达 %d（上限 %d，模型上下文窗口 %d × %.0f%%），停止检索。"
                    .formatted(tokensUsed, tokenLimit,
                        tokenLimit / properties.contextWindowRatio(),
                        properties.contextWindowRatio() * 100));
        }

        // 指标 3：同一 Tool 连续调用检测
        if (currentTool != null && currentTool.equals(lastToolName)) {
            consecutiveCount++;
        } else {
            lastToolName = currentTool;
            consecutiveCount = 1;
        }
        if (consecutiveCount > properties.maxConsecutiveSameTool()) {
            return GuardrailCheck.stop(
                "CONSECUTIVE_TOOL",
                "工具 [%s] 已连续调用 %d 次，疑似死循环，停止检索。"
                    .formatted(currentTool, consecutiveCount));
        }

        return GuardrailCheck.ok();
    }

    public int getConsecutiveCount() { return consecutiveCount; }

    public record GuardrailCheck(
        boolean allowed,
        @Nullable String stopReason,   // ITERATION_LIMIT | TOKEN_LIMIT | CONSECUTIVE_TOOL
        @Nullable String message        // 可读的停止原因，返回给用户
    ) {
        static GuardrailCheck ok() {
            return new GuardrailCheck(true, null, null);
        }
        static GuardrailCheck stop(String reason, String message) {
            return new GuardrailCheck(false, reason, message);
        }
    }
}
```

**三指标说明**：

| 指标 | 计算方式 | 默认上限 | 防御目标 |
|------|----------|----------|----------|
| 循环迭代总次数 | 整个 ReAct 循环，每轮 = LLM 决策 → Tool 执行 → 结果回传 | 10 | 防止无限循环 |
| 累计 Token 消耗 | 意图识别 + 所有 ReAct 轮次的 LLM token | 模型上下文窗口 × 80% | 防止 token 爆炸 |
| 同一 Tool 连续调用 | 同一 Tool 连续调用次数（切换 Tool 会重置） | 3 | 防止 LLM 卡在某个 Tool 上 |

**Token 计算范围**：

```
累计 token 消耗 = 意图识别 LLM 调用
                + ReAct 第 1 轮（LLM 推理 + Tool 结果回传）
                + ReAct 第 2 轮（...）
                + ...
                + ReAct 第 N 轮

注意：Tool 执行本身（数据库查询、Rerank API）不消耗 LLM token，
只有 LLM 的输入/输出才计入。每轮 ChatResponse.metadata().usage() 提取。
```

**护栏触发后的行为**：

```java
// 在 ToolCallAdvisor 每轮回调中检查
GuardrailCheck check = guardrails.check(iteration, tokensUsed, tokenLimit, currentTool);
if (!check.allowed()) {
    log.warn("Agent guardrail triggered: reason={}, iteration={}, tokens={}/{}, tool={}, consecutive={}, workspace docs={}",
        check.stopReason(), iteration, tokensUsed, tokenLimit,
        currentTool, guardrails.getConsecutiveCount(),
        workspace.getRetrievedDocs().size());

    // 直接跳出 ReAct 循环，不再调用 LLM
    // 用已有的 Workspace 检索结果 + check.message() 构造最终响应
    return buildGuardrailResponse(workspace, check);
}
```

**用户看到的响应示例**：

```
⚠️ 检索过程因达到调用上限而提前停止（已执行 10 轮检索，收集到 13 个文档片段）。
基于已收集的部分信息，回答如下：

[LLM 基于已有结果生成的回答]
```

#### 第四层：全局降级

```java
/**
 * Agent 全局降级策略
 *
 * 当 Agent 模式完全不可用时，降级到 MULTI_TURN + RetrievalAugmentationAdvisor
 */
@Component
public class AgentDegradationStrategy {

    private static final Logger log = LoggerFactory.getLogger(AgentDegradationStrategy.class);

    /**
     * Agent 模式是否可用
     * 前置检查：模型是否支持 Tool Calling、意图识别模型是否可达
     */
    public boolean isAgentAvailable() {
        // 可扩展为健康检查
        return true;
    }

    /**
     * 构建降级后的 Advisor 链（回退到 MULTI_TURN + RAG Pipeline）
     */
    public List<Advisor> buildDegradedChain(String conversationId,
                                            ChatRequest request,
                                            ChatModeStrategy modeStrategy) {
        log.warn("Agent mode degraded to MULTI_TURN with RAG Pipeline");
        // 复用 MULTI_TURN 的链路构建逻辑
        // ...
    }
}
```

### 6.3 完整容错矩阵

| 故障点 | 检测方式 | 容错策略 | 降级目标 | 用户感知 |
|--------|----------|----------|----------|----------|
| F1: 意图识别 LLM 失败 | 异常捕获 + 2 次重试 | 降级为 `DEEP_RETRIEVAL` | Agent 模式（全量 Tool） | 无感知（略慢） |
| F2: 查询分解失败 | 返回 null / 空列表 | 用原始查询作为唯一子问题 | Agent 模式（单查询） | 无感知 |
| F3: 意图误分类 | 无（静默） | DEEP_RETRIEVAL 为安全兜底 | Agent 模式 | 可能多检索（token 增） |
| F4: ToolCallAdvisor 无 Tool | `toolSet.isEmpty()` 检查 | 降级为 MULTI_TURN + RAG Pipeline | MULTI_TURN 模式 | 回退到 Pipeline RAG |
| F5a: 向量检索失败 | DataAccessException | ToolResult.failure → LLM 决策换 bm25 | LLM 自动切换 | 可能略慢 |
| F5b: BM25 失败 | DataAccessException | ToolResult.failure → LLM 决策换 vectorSearch | LLM 自动切换 | 可能略慢 |
| F5c: Rerank 失败 | ApiException | ToolResult.failure → LLM 跳过精排直接回答 | 无精排的回答 | 精度可能降低 |
| F5d: 查询改写失败 | ApiException | ToolResult.failure → LLM 用原查询检索 | 原查询检索 | 可能召回不够好 |
| F5e: 父文档查找失败 | DataAccessException | ToolResult.failure → LLM 用子块回答 | 子块粒度回答 | 上下文可能不完整 |
| F6: 死循环 | Guardrails 三指标检测（迭代总数 / token 消耗 / 连续 Tool） | 跳出循环 + 用已有结果回答 + 告知用户 | 回答可能不完整 |
| F7: Workspace 损坏 | JsonProcessingException | 重建空 Workspace 继续或降级 | MULTI_TURN 模式 | 回退到 Pipeline RAG |
| F8: ThreadLocal 泄漏 | try-finally in CleanupAdvisor | finally 块强制清理 | 无（已清理） | 无感知 |
| F9: Token 超限 | Guardrails token 计数（模型上下文窗口 × 80%） | 跳出循环 + 用已有结果回答 + 告知用户 | 回答可能不完整 |
| F10: 主模型失败 | ChatServiceImpl 兜底策略 | 复用现有 FallbackChainProvider | 备选模型回答 | 回退到备选模型 |

### 6.4 可观测性设计

```java
/**
 * Agent 执行追踪记录
 * 每次请求一条，记录完整的 Agent 执行过程
 */
public record AgentTrace(
    String traceId,                   // 追踪 ID（UUIDv7）
    long userId,
    String query,                     // 原始查询
    AgentIntent intent,               // 意图分类结果
    List<String> subQueries,          // 子问题
    List<ToolCallRecord> toolCalls,   // Tool 调用记录
    int totalIterations,              // 总迭代轮次
    int totalTokensUsed,              // 总 token 消耗
    long totalDurationMs,             // 总耗时
    String finalStatus,               // COMPLETED / DEGRADED / FAILED
    @Nullable String stopReason       // 停止原因（正常完成 / 护栏触发 / 异常）
) {}

public record ToolCallRecord(
    int iteration,                    // 第几轮
    String toolName,                  // Tool 名称
    Map<String, Object> inputParams,  // 输入参数
    boolean success,                  // 是否成功
    @Nullable String errorCategory,   // 错误分类
    int resultDocCount,               // 结果文档数
    long durationMs                   // 耗时
) {}
```

**日志策略**：

```yaml
# Agent 执行追踪日志格式
# INFO 级别：摘要（意图、轮次、总耗时、状态）
# DEBUG 级别：每轮 Tool 调用详情（参数、结果数、耗时）
# TRACE 级别：Workspace 完整状态快照

2026-05-19 14:30:00 INFO  [AgentTrace] traceId=xxx intent=DEEP_RETRIEVAL subQueries=3
  iterations=5 tokens=8200 duration=3200ms status=COMPLETED
2026-05-19 14:30:01 DEBUG [AgentTrace] iter=1 tool=hybridSearch success=true docs=8 duration=420ms
2026-05-19 14:30:01 DEBUG [AgentTrace] iter=2 tool=hybridSearch success=true docs=5 duration=380ms
2026-05-19 14:30:02 DEBUG [AgentTrace] iter=3 tool=rerank success=true docs=5 duration=680ms
2026-05-19 14:30:02 DEBUG [AgentTrace] iter=4 tool=parentDocLookup success=true docs=3 duration=120ms
2026-05-19 14:30:02 WARN  [AgentGuardrail] traceId=xxx consecutiveSameTool=hybridSearch count=2 (threshold=3)
```

### 6.5 配置补充

```yaml
app:
  agent:
    # ... 已有配置 ...

    # 循环护栏
    max-tool-iterations: 10           # ReAct 最大迭代总轮次（整个循环）
    max-consecutive-same-tool: 3       # 同一 Tool 连续调用上限（切换 Tool 重置）
    context-window-ratio: 0.8         # token 上限 = 模型上下文窗口 × 此比例

    # 容错
    intent-retries: 2                  # 意图识别重试次数
    intent-timeout-ms: 5000            # 意图识别超时（5s）
    tool-timeout-ms: 10000             # 单次 Tool 超时（10s）
    degrade-on-failure: true           # Agent 全部失败时是否降级到 MULTI_TURN
```

```java
@ConfigurationProperties(prefix = "app.agent")
public record AgentRagProperties(
    boolean enabled,
    // 意图识别
    String intentModel,
    Double intentTemperature,
    int intentRetries,
    int intentTimeoutMs,
    // 循环护栏
    int maxToolIterations,
    int maxConsecutiveSameTool,
    double contextWindowRatio,
    // 容错
    int toolTimeoutMs,
    boolean degradeOnFailure,
    // System Prompt
    String directAnswerPrompt,
    String retrievalPrompt,
    String deepRetrievalPrompt,
    String generalToolPrompt
) {
    public AgentRagProperties {
        if (maxToolIterations <= 0) maxToolIterations = 10;
        if (maxConsecutiveSameTool <= 0) maxConsecutiveSameTool = 3;
        if (contextWindowRatio <= 0 || contextWindowRatio > 1) contextWindowRatio = 0.8;
        if (intentRetries < 0) intentRetries = 2;
        if (intentTimeoutMs <= 0) intentTimeoutMs = 5000;
        if (toolTimeoutMs <= 0) toolTimeoutMs = 10000;
        if (intentTemperature == null) intentTemperature = 0.1;
    }
}
```

---

## 7. 实施计划

### Phase 1: 基础设施（3-4h）

1. `ChatMode` 新增 `AGENT`
2. `AgentModeStrategy` 实现
3. `AgentRagProperties` 配置类 + `application.yml`
4. `ToolWorkspace` + `ToolWorkspaceHolder` + `ToolWorkspaceFactory` + `RetrievedDocument`
5. `AgentContextCleanupAdvisor`
6. `AgentSystemPromptAdvisor`

### Phase 2: 意图识别层（3-4h）

1. `AgentIntent` 枚举 + `IntentResult` record
2. `IntentClassifier`（独立 LLM 调用 + Structured Output）
3. `IntentToolSetRegistry`（意图→Tool 子集映射）
4. 意图识别单元测试

### Phase 3: RAG Tool 实现（4-6h）

1. `RagTool` 标记接口
2. `VectorSearchTool`（复用 `VectorStore`）
3. `Bm25SearchTool`（复用 `VectorStoreMapper`）
4. `HybridSearchTool`（复用 `HybridDocumentRetriever` 核心逻辑）
5. `RerankTool`（复用 `BailianRerankPostProcessor`）
6. `QueryRewriteTool`（复用 `RewriteQueryTransformer` prompt）
7. `ParentDocLookupTool`（复用 `ParentDocumentPostProcessor`）
8. `KnowledgeBaseInfoTool`（复用 `VectorStoreMapper`）
9. 每个 Tool 单元测试

### Phase 4: 编排层集成（2-3h）

1. `ChatAdvisorChainFactory` AGENT 分支
2. `ChatRequestSpecFactory` AGENT 模式适配
3. `ChatServiceImpl` AGENT 模式支持（阻塞式）

### Phase 5: 端到端验证（2-3h）

1. Agent 端到端测试（DIRECT_ANSWER / RETRIEVAL / DEEP_RETRIEVAL / GENERAL_TOOL）
2. 与 MULTI_TURN 模式回归对比
3. 性能基准（延迟、token 消耗）
4. Workspace 状态正确性验证

### Phase 6: 文档与收尾（1h）

1. 更新 `ARCHITECTURE.md`
2. 更新 `RAG-DESIGN.md`
3. 更新 `API-DOCS.md`

---

## 8. 决策记录

| # | 问题 | 决策 | 理由 |
|---|------|------|------|
| Q1 | Agent 的 ToolCallAdvisor 是否独立？ | **分离** — 按意图动态选择 Tool 子集 | 避免 LLM 选择困难和误调用；按需暴露更可控 |
| Q2 | 检索结果跨 Tool 轮次如何传递？ | **JSON Workspace** — 结构化中间状态 | 可序列化、可调试、可追踪；比 ThreadLocal List 更明确 |
| Q3 | 是否支持独立模型？ | **是 — 通过意图识别层** | 意图识别用轻量模型降本；主 Agent 可用更强推理模型 |
| Q4 | 流式响应？ | **先阻塞式，后续迭代流式** | 降低首版复杂度；流式需处理中间过程展示 |
| Q5 | 意图识别是否包含查询分解？ | **是 — 意图分类+查询分解合并为单次 LLM 调用** | 减少延迟；子问题写入 Workspace 供 Agent 按子问题逐一检索 |

---

## 9. 后续演进方向

| 方向 | 说明 | 优先级 |
|------|------|--------|
| 流式 Agent | 支持 SSE 中间过程展示（"正在检索..." → "正在精排..."） | P1 |
| Agent 可观测性 | 记录每次 Tool 调用的耗时、token、结果摘要 | P1 |
| 多模型协作 | 意图识别用快速模型，深度推理用推理模型 | P2 |
| Tool 结果缓存 | 相同查询的检索结果短期缓存，避免重复检索 | P2 |
| 自定义意图规则 | 允许用户配置意图分类规则（如特定关键词触发 DEEP_RETRIEVAL） | P3 |
