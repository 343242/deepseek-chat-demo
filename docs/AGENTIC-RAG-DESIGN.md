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
- 与现有 `ToolRegistry` OCP 体系无缝对接
- 新增 `AGENT` 对话模式，不影响现有 `SIMPLE` / `MULTI_TURN` 模式

---

## 2. 架构设计

### 2.1 整体架构

```
                          ChatServiceImpl
                               │
                    ChatRequestSpecFactory
                               │
                 ┌─────────────┴──────────────┐
                 │  ChatAdvisorChainFactory    │
                 │  (mode=AGENT 时走新链路)     │
                 └─────────────┬──────────────┘
                               │
          ┌────────────────────┼────────────────────┐
          │                    │                     │
   SIMPLE 模式          MULTI_TURN 模式          AGENT 模式
   (原有链路)            (原有链路)           (新增 Agentic 链路)
                                                  │
                                    ┌─────────────┴──────────────┐
                                    │   Advisor Chain (Agent)     │
                                    │                             │
                                    │ ① ConversationContextAdvisor│
                                    │ ② MessageChatMemoryAdvisor  │
                                    │ ③ ToolCallAdvisor (ReAct)   │
                                    │    ├── vectorSearchTool     │
                                    │    ├── bm25SearchTool       │
                                    │    ├── hybridSearchTool     │
                                    │    ├── rerankTool           │
                                    │    ├── queryRewriteTool     │
                                    │    ├── parentDocLookupTool  │
                                    │    ├── knowledgeBaseInfoTool│
                                    │    └── (已有 Calculator/    │
                                    │         DateTime/CodeExec)  │
                                    │ ④ ChatModel (最终回答)      │
                                    └─────────────────────────────┘
```

### 2.2 ReAct 循环

```
用户: "对比 RAG 和 Fine-tuning 在知识更新场景的优劣"
                    │
                    ▼
            ┌─ LLM 推理 ─┐
            │ "这个问题涉及  │
            │  知识库文档，  │
            │  需要检索"    │
            └──────┬──────┘
                   │ 调用 hybridSearchTool("RAG vs Fine-tuning 知识更新")
                   ▼
           ┌─ Tool 执行 ─┐
           │ pgvector+BM25│
           │ RRF 融合      │
           │ 返回 8 个文档 │
           └──────┬──────┘
                  │ 结果回传 LLM
                  ▼
           ┌─ LLM 推理 ─┐
           │ "文档侧重    │
           │  RAG 部分，  │
           │  Fine-tuning │
           │  内容不够，   │
           │  改写再搜"   │
           └──────┬──────┘
                  │ 调用 queryRewriteTool → hybridSearchTool("Fine-tuning 模型更新策略 知识时效性")
                  ▼
           ┌─ Tool 执行 ─┐
           │ 返回 6 个文档 │
           └──────┬──────┘
                  │ 结果回传 LLM
                  ▼
           ┌─ LLM 推理 ─┐
           │ "需要精排"   │
           └──────┬──────┘
                  │ 调用 rerankTool(14 个文档)
                  ▼
           ┌─ Tool 执行 ─┐
           │ Rerank 返回  │
           │ Top 5 精排   │
           └──────┬──────┘
                  │
                  ▼
           ┌─ LLM 生成 ─┐
           │ 综合分析回答  │
           └─────────────┘
```

### 2.3 新增 ChatMode

```java
public enum ChatMode {
    SIMPLE,       // 单轮，无记忆
    MULTI_TURN,   // 多轮，有记忆 + RAG Pipeline
    AGENT;        // Agent 模式，LLM 自主编排 Tool（含 RAG Tool）
}
```

### 2.4 Advisor 链对比

| Advisor | SIMPLE | MULTI_TURN | AGENT |
|---------|--------|------------|-------|
| ConversationContextAdvisor | ❌ | ✅ | ✅ |
| RateLimitAdvisor | ✅ | ✅ | ✅ |
| ContentFilterAdvisor | ✅ | ✅ | ✅ |
| RetrievalAugmentationAdvisor | ❌ | ✅ (ragEnabled) | ❌ (被 Tool 替代) |
| ToolCallAdvisor | ✅ (有 Tool 时) | ✅ (有 Tool 时) | ✅ (核心) |
| MessageChatMemoryAdvisor | ❌ | ✅ | ✅ |

**关键区别**：AGENT 模式下，RAG 不再通过 `RetrievalAugmentationAdvisor` 固定 Pipeline 执行，而是由 LLM 通过 `ToolCallAdvisor` 自主决定何时、如何检索。

---

## 3. Tool 设计

### 3.1 Tool 清单

| Tool | 描述 | 封装组件 | returnDirect |
|------|------|----------|-------------|
| `vectorSearchTool` | 向量语义检索 | `VectorStore.similaritySearch()` | false |
| `bm25SearchTool` | BM25 全文检索 | `VectorStoreMapper.bm25Search()` | false |
| `hybridSearchTool` | 混合检索 + RRF 融合 | `HybridDocumentRetriever` 核心逻辑 | false |
| `rerankTool` | 语义精排 | `BailianRerankPostProcessor` 核心逻辑 | false |
| `queryRewriteTool` | 查询改写 | `RewriteQueryTransformer` 核心逻辑 | false |
| `parentDocLookupTool` | 子块→父文档替换 | `ParentDocumentPostProcessor` 核心逻辑 | false |
| `knowledgeBaseInfoTool` | 知识库元信息查询 | `VectorStoreMapper` 统计查询 | false |

### 3.2 包结构

```
com.demo.chat.rag.tool/
├── RagToolContext.java           // Tool 共享上下文（userId, teamId, 检索结果暂存）
├── RagToolContextFactory.java    // 按请求创建 ToolContext（类似 RagAdvisorFactory）
├── VectorSearchTool.java         // 向量检索
├── Bm25SearchTool.java           // BM25 全文检索
├── HybridSearchTool.java         // 混合检索 + RRF
├── RerankTool.java               // 语义精排
├── QueryRewriteTool.java         // 查询改写
├── ParentDocLookupTool.java      // 子块→父文档
└── KnowledgeBaseInfoTool.java    // 知识库元信息
```

### 3.3 RagToolContext — 请求级共享上下文

Agent 可能多轮调用 Tool，Tool 之间需要共享检索结果（如先 hybridSearch → 再 rerank）。

```java
/**
 * Agent 请求级别的 RAG Tool 共享上下文
 *
 * 生命周期：单次 ChatRequest → 一次 buildChain() → 一个 Agent 完整 ReAct 循环
 * 作用：
 *   1. 携带 userId/teamId 隔离信息（所有 Tool 共用）
 *   2. 暂存中间检索结果（如 hybridSearch 结果供后续 rerank 使用）
 *
 * 线程安全：单次请求内单线程执行，无需同步
 */
public class RagToolContext {

    private final Long userId;
    @Nullable
    private final Long teamId;

    /** 暂存的检索结果（Tool 可写入，后续 Tool 可读取） */
    private final List<Document> accumulatedDocuments = new ArrayList<>();

    // --- getters, addDocuments, getAccumulatedDocuments, clear ---
}
```

### 3.4 RagToolContextFactory

类似现有 `RagAdvisorFactory` 的按请求创建模式：

```java
@Component
public class RagToolContextFactory {

    /** 为每次请求创建独立的 ToolContext */
    public RagToolContext create(Long userId, @Nullable Long teamId) {
        return new RagToolContext(userId, teamId);
    }
}
```

### 3.5 Tool 详细设计

#### VectorSearchTool

```java
@Component
public class VectorSearchTool {

    private final VectorStore vectorStore;
    private final RagRetrievalProperties properties;

    @Tool(description = "在知识库中进行向量语义检索。适用于概念性、语义性的问题。" +
                        "返回与查询语义最相似的文档片段。")
    public String vectorSearch(
            @ToolParam(description = "检索查询文本") String query,
            @ToolParam(description = "返回结果数量，默认10") @Nullable Integer topK) {
        // 从 RagToolContext 获取 userId/teamId 构建过滤条件
        // 调用 vectorStore.similaritySearch()
        // 返回格式化的文档内容
    }
}
```

#### HybridSearchTool

```java
@Component
public class HybridSearchTool {

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
        // 复用 HybridDocumentRetriever 的核心逻辑
        // 结果写入 RagToolContext.accumulatedDocuments
    }
}
```

#### RerankTool

```java
@Component
public class RerankTool {

    private final BailianRerankPostProcessor rerankProcessor;

    @Tool(description = "对已检索的文档进行语义精排。当检索结果较多且需要精选最相关文档时使用。" +
                        "基于百炼 Rerank API，返回重新排序后的 Top-N 文档。")
    public String rerank(
            @ToolParam(description = "原始查询文本，用于相关性判断") String query,
            @ToolParam(description = "精排后返回的文档数量，默认5") @Nullable Integer topN) {
        // 从 RagToolContext.accumulatedDocuments 获取待排序文档
        // 调用 BailianRerankPostProcessor 核心逻辑
        // 替换 RagToolContext 中的文档列表
    }
}
```

#### QueryRewriteTool

```java
@Component
public class QueryRewriteTool {

    private final ChatClient.Builder chatClientBuilder;

    @Tool(description = "改写查询以提升检索效果。当原始查询检索结果不理想时，" +
                        "使用此工具生成更精确、更适合检索的查询。" +
                        "支持多角度改写，可一次生成多个变体查询。")
    public String rewriteQuery(
            @ToolParam(description = "需要改写的原始查询") String query,
            @ToolParam(description = "改写角度，如：更具体、同义替换、拆分子问题",
                       required = false) @Nullable String perspective) {
        // 调用 LLM 改写查询（复用 RewriteQueryTransformer 的 prompt）
    }
}
```

#### KnowledgeBaseInfoTool

```java
@Component
public class KnowledgeBaseInfoTool {

    private final VectorStoreMapper vectorStoreMapper;

    @Tool(description = "查询当前知识库的元信息，包括文档数量、分块数量等。" +
                        "帮助判断知识库中是否有足够的相关内容来回答问题。")
    public String getKnowledgeBaseInfo() {
        // 从 RagToolContext 获取 userId/teamId
        // 查询文档数量、分块数量、最近更新时间
    }
}
```

### 3.6 ToolContext 传递方案

**方案选择：ThreadLocal**

Agent 模式下 `ToolCallAdvisor` 在单线程中执行 ReAct 循环，Tool 通过 ThreadLocal 获取当前请求的 `RagToolContext`。

```java
public final class RagToolContextHolder {

    private static final ThreadLocal<RagToolContext> CONTEXT = new ThreadLocal<>();

    public static void set(RagToolContext context) { CONTEXT.set(context); }
    public static RagToolContext get() { return CONTEXT.get(); }
    public static void clear() { CONTEXT.remove(); }

    private RagToolContextHolder() {}
}
```

**设置时机**：`ChatAdvisorChainFactory.buildChain()` 创建 Agent 链时设置。
**清理时机**：在 Agent Advisor 链末尾添加清理 Advisor，或通过 try-finally 在 ChatServiceImpl 中清理。

---

## 4. 核心改动

### 4.1 改动清单

| # | 文件 | 改动类型 | 说明 |
|---|------|----------|------|
| 1 | `ChatMode.java` | 修改 | 新增 `AGENT` 枚举值 |
| 2 | `AgentModeStrategy.java` | 新增 | AGENT 模式策略：memory=true, context=true, thinking=false |
| 3 | `ChatAdvisorChainFactory.java` | 修改 | `buildChain()` 新增 AGENT 分支：挂载 RAG Tool + 设置 ThreadLocal |
| 4 | `ChatRequestSpecFactory.java` | 修改 | AGENT 模式下动态注入 RAG Tool（带 RagToolContext） |
| 5 | `RagToolContext.java` | 新增 | 请求级共享上下文 |
| 6 | `RagToolContextHolder.java` | 新增 | ThreadLocal 传递 |
| 7 | `RagToolContextFactory.java` | 新增 | 按请求创建 ToolContext |
| 8 | `VectorSearchTool.java` | 新增 | 向量检索 Tool |
| 9 | `Bm25SearchTool.java` | 新增 | BM25 全文检索 Tool |
| 10 | `HybridSearchTool.java` | 新增 | 混合检索 Tool |
| 11 | `RerankTool.java` | 新增 | Rerank Tool |
| 12 | `QueryRewriteTool.java` | 新增 | 查询改写 Tool |
| 13 | `ParentDocLookupTool.java` | 新增 | 父文档查找 Tool |
| 14 | `KnowledgeBaseInfoTool.java` | 新增 | 知识库元信息 Tool |
| 15 | `AgentContextCleanupAdvisor.java` | 新增 | Agent 链末尾清理 ThreadLocal |
| 16 | `AgentRagProperties.java` | 新增 | Agent 模式配置（System Prompt、最大 Tool 调用轮次等） |

### 4.2 ChatAdvisorChainFactory 改动

```java
// buildChain() 新增 AGENT 分支
case AGENT -> {
    // 1. 上下文 Advisor
    if (modeStrategy.isContextEnabled()) {
        chain.add(new ConversationContextAdvisor(conversationId));
    }

    // 2. 全局 Advisor（限流、内容过滤）
    chain.addAll(getGlobalAdvisors());

    // 3. 设置 RAG ToolContext（ThreadLocal）
    if (request.isRagEnabled()) {
        Long userId = SecurityUtils.getCurrentUserId();
        Long teamId = request.teamId();
        RagToolContext context = ragToolContextFactory.create(userId, teamId);
        RagToolContextHolder.set(context);
    }

    // 4. ToolCallAdvisor（核心 ReAct 循环）
    // RAG Tool 通过 ToolRegistry 自动发现，无需额外注册
    chain.add(toolCallAdvisorProvider.getObject());

    // 5. Memory
    if (modeStrategy.isMemoryEnabled()) {
        chain.add(MessageChatMemoryAdvisor.builder(chatMemory).build());
    }

    // 6. 清理 Advisor（ThreadLocal 清理）
    chain.add(new AgentContextCleanupAdvisor());
}
```

### 4.3 System Prompt 设计

Agent 模式需要明确的 System Prompt 指导 LLM 何时使用什么 Tool：

```yaml
app:
  agent:
    system-prompt: |
      你是一个智能助手，可以自主决定如何回答用户的问题。

      你有以下检索工具可以使用：
      - vectorSearch: 向量语义检索，适合概念性、语义性问题
      - bm25Search: BM25 全文检索，适合精确关键词匹配
      - hybridSearch: 混合检索（向量+BM25+RRF），适合大多数场景
      - rerank: 对检索结果进行语义精排
      - rewriteQuery: 改写查询以提升检索效果
      - parentDocLookup: 查找子块对应的完整父文档
      - knowledgeBaseInfo: 查询知识库元信息

      决策指南：
      1. 如果问题不需要知识库（如通用知识、闲聊），直接回答
      2. 如果需要知识库，先用 hybridSearch 检索
      3. 如果检索结果不够好，用 rewriteQuery 改写后再检索
      4. 如果检索结果很多但不够精准，用 rerank 精排
      5. 如果需要更完整的上下文，用 parentDocLookup 获取父文档
      6. 综合所有信息后给出准确、完整的回答

      回答时标注引用来源，区分知识库内容和自身知识。
    max-tool-iterations: 10
```

---

## 5. 与现有代码的兼容性

### 5.1 零影响原则

| 现有功能 | 影响 | 说明 |
|----------|------|------|
| SIMPLE 模式 | 零影响 | 不走 AGENT 分支 |
| MULTI_TURN 模式 | 零影响 | 仍用 `RetrievalAugmentationAdvisor` |
| `RagAdvisorFactory` | 保留 | MULTI_TURN 模式继续使用 |
| `HybridDocumentRetriever` | 保留 + 复用 | AGENT 模式的 Tool 复用其核心逻辑 |
| `ToolRegistry` | 保留 | RAG Tool 是普通 `@Component` + `@Tool`，自动发现 |
| `CalculatorTools` / `DateTimeTools` | 零影响 | Agent 模式下也可使用这些通用工具 |
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
```

---

## 6. 配置设计

### 6.1 Agent 配置

```yaml
app:
  agent:
    enabled: true                              # 是否启用 AGENT 模式
    system-prompt: "..."                        # Agent System Prompt
    max-tool-iterations: 10                     # 最大 Tool 调用轮次（防止无限循环）
    rag-tools-enabled: true                     # 是否注册 RAG Tool
```

```java
@ConfigurationProperties(prefix = "app.agent")
public record AgentRagProperties(
    boolean enabled,
    String systemPrompt,
    int maxToolIterations,
    boolean ragToolsEnabled
) {
    public AgentRagProperties {
        if (maxToolIterations <= 0) maxToolIterations = 10;
    }
}
```

---

## 7. 风险与缓解

| # | 风险 | 级别 | 缓解措施 |
|---|------|------|----------|
| R1 | LLM 无限循环调用 Tool | 高 | `maxToolIterations` 硬限制 + ToolCallAdvisor 内置循环上限 |
| R2 | Agent 模式 token 消耗远高于 Pipeline | 高 | System Prompt 明确指导"简单问题直接回答"；监控用量 |
| R3 | Tool 调用延迟累加 | 中 | 每次 Tool 调用记录耗时，超阈值告警；异步 Tool 执行 |
| R4 | ThreadLocal 泄漏 | 中 | `AgentContextCleanupAdvisor` 保证 finally 清理 |
| R5 | 模型不支持 Tool Calling | 低 | `ToolCallAdvisor` 已有降级机制；Agent 模式要求模型支持 Tool |
| R6 | 检索结果质量不稳定 | 中 | 保留 `HybridDocumentRetriever` 成熟的 RRF 融合逻辑 |

---

## 8. 实施计划

### Phase 1: 基础设施（2-3h）

1. `ChatMode` 新增 `AGENT`
2. `AgentModeStrategy` 实现
3. `AgentRagProperties` 配置类
4. `RagToolContext` + `RagToolContextHolder` + `RagToolContextFactory`
5. `AgentContextCleanupAdvisor`

### Phase 2: RAG Tool 实现（4-6h）

1. `VectorSearchTool`（复用 `VectorStore`）
2. `Bm25SearchTool`（复用 `VectorStoreMapper`）
3. `HybridSearchTool`（复用 `HybridDocumentRetriever` 核心逻辑）
4. `RerankTool`（复用 `BailianRerankPostProcessor`）
5. `QueryRewriteTool`（复用 `RewriteQueryTransformer` prompt）
6. `ParentDocLookupTool`（复用 `ParentDocumentPostProcessor`）
7. `KnowledgeBaseInfoTool`（复用 `VectorStoreMapper`）

### Phase 3: 编排层集成（2-3h）

1. `ChatAdvisorChainFactory` AGENT 分支
2. `ChatRequestSpecFactory` AGENT 模式适配
3. `ChatServiceImpl` AGENT 模式支持（System Prompt 注入）
4. `application.yml` 配置

### Phase 4: 测试与验证（2-3h）

1. 每个 Tool 单元测试
2. Agent 端到端测试
3. 与 MULTI_TURN 模式回归对比
4. 性能基准（延迟、token 消耗）

### Phase 5: 文档与收尾（1h）

1. 更新 `ARCHITECTURE.md`
2. 更新 `RAG-DESIGN.md`
3. 更新 `API-DOCS.md`

---

## 9. 开放问题

| # | 问题 | 待决策 |
|---|------|--------|
| Q1 | Agent 模式是否需要独立的 ToolCallAdvisor（不与通用 Tool 混用）？ | 暂定共用，观察是否冲突 |
| Q2 | 检索结果跨 Tool 轮次如何传递？ | 暂定 `RagToolContext.accumulatedDocuments` |
| Q3 | 是否需要为 Agent 模式配置独立的 LLM 模型（如用更强推理模型）？ | 后续评估 |
| Q4 | 流式响应下 Agent 如何工作？ | Phase 4 评估，可能先支持阻塞式 |
