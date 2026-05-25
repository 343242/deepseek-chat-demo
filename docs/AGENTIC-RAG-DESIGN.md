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

### 1.3 学术基础

本方案融合两篇前沿论文的核心思想：

#### DeepRAG (ICLR 2026)

将 retrieval-augmented reasoning 建模为 **Markov Decision Process (MDP)**，核心贡献：

1. **原子决策 (Atomic Decision)**：对每个子问题独立判断 `retrieve`（检索外部知识）还是 `parametric`（依赖模型自身知识），而非对整个问题一刀切
2. **检索代价感知**：奖励函数同时考虑正确性和检索成本，鼓励模型"能不检索就不检索"
3. **中间答案累积**：每处理一个子问题就生成中间答案，后续子问题可引用

#### Self-RAG (ICLR 2024 Oral)

通过 **Reflection Tokens** 实现自省式检索-生成，核心贡献：

1. **自适应检索**：按 segment 级别动态决定是否检索（`Retrieve: yes/no`），非全量或全不
2. **自省式评估**：生成后自我评估——检索结果是否相关 (`IsRel`)、回答是否有据 (`IsSup`)、回答是否有用 (`IsUse`)
3. **可定制推理**：通过调整阈值平衡事实性与创造性

**落地策略**：不训练专用模型，通过 **System Prompt + Structured Output + Workspace** 实现同等效果的工程化方案。

### 1.4 目标

将 RAG 从 **Pipeline 模式** 升级为 **Agent 模式**：
- LLM 通过 `ToolCallAdvisor` 的 ReAct 循环自主编排检索策略
- **原子决策**（DeepRAG）：每个子问题独立判断是否检索，减少不必要的检索调用
- **自省式评估**（Self-RAG）：检索后自评相关性和充分性，驱动下一步决策
- **中间答案累积**（DeepRAG）：逐步累积子问题答案，避免信息丢失
- **检索代价感知**（DeepRAG）：Prompt 引导 LLM 优先使用已有知识，降低检索成本
- 保留现有核心组件，封装为 Tool，与 `ToolRegistry` OCP 体系无缝对接
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
│   输出：AgentIntent + 动态 Tool 子集                               │
│   （查询分解为后续迭代，第一版 Agent 直接处理原始查询）              │
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

### 2.2 完整请求流程（融合 DeepRAG + Self-RAG）

> 以下为**第一版流程**（无查询分解，Agent 直接处理原始查询）。
> 后续迭代启用查询分解后，将扩展为按子问题逐一检索。

```
用户: "对比 RAG 和 Fine-tuning 在知识更新场景的优劣"
                    │
                    ▼
        ┌─ 第一层：意图识别 ──────────┐
        │ IntentClassifier              │
        │                               │
        │ 意图分类: → DEEP_RETRIEVAL     │
        │                               │
        │ （第一版不进行查询分解，         │
        │   Agent 直接处理原始查询）      │
        └─────────────┬────────────────┘
                      │ intent 写入 Workspace
                      ▼
        ┌─ 第二层：Agent ReAct ──────────┐
        │                                  │
        │   对原始查询执行四步增强循环       │
        │   （详见 §2.4）：                 │
        │                                  │
        │   原子决策 → retrieve/parametric   │
        │      → 自省评估 → 改写重搜/精排    │
        │      → 中间答案累积                │
        │                                  │
        │   示例：                          │
        │     原子决策: retrieve            │
        │       → hybridSearchTool() → 8 docs│
        │     自省: is_relevant=true,        │
        │            is_sufficient=false     │
        │       → queryRewriteTool() → 重搜 │
        │     自省: is_relevant=true,        │
        │            is_sufficient=true      │
        │       → 生成中间答案               │
        │                                  │
        │   ④ 最终回答 + 质量自评 + 引用标注  │
        └──────────────────────────────────┘
```

### 2.3 意图识别层详细设计

> **📋 迭代策略**：第一版（Phase 2）**只实现意图分类**，不实现查询分解。`IntentResult.subQueries` 始终为空列表，Agent 直接处理原始查询，不拆解为子问题。查询分解功能根据实际运行数据决定是否在后续迭代引入。
>
> 受此决策影响，以下简化生效：
> - §2.4 四步增强循环作用于**单个查询**而非多个子问题
> - §2.4.3 中间答案累积简化为"单一答案"，后续版本再扩展为多子问题
> - §2.5 Workspace 中的 `subQueries`、`completedSubQueries`、`getPendingSubQueryIndices()` 字段/方法保留但第一版不使用
> - §3.2 包结构中 `IntentClassifier` 只做分类、不做分解
> - §7 Phase 7 端到端验证不包含多子问题场景

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
 * 意图分类结果
 *
 * @param intent     意图分类
 * @param confidence 分类置信度
 * @param subQueries 分解后的子问题列表（第一版始终为空列表，后续迭代启用查询分解）
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

> 实现参考：`AGENTIC-RAG-IMPLEMENTATION-NOTES.md` §1

**职责**：
1. 分析用户查询，判断意图分类（是否需要检索、检索深度）
2. 返回意图分类结果

> **第一版不实现查询分解**（§2.3 顶部迭代策略说明）。后续迭代可视需要扩展：对 RETRIEVAL / DEEP_RETRIEVAL 类型将复杂问题拆解为独立子问题。

**设计决策**：
- 第一版：意图分类为独立的单次 LLM 调用，只返回 `intent` + `confidence`，`subQueries` 始终为空列表
- 后续迭代：可将查询分解合并到同一次 LLM 调用中（减少延迟），`subQueries` 写入 Workspace 供 Agent 按子问题逐一检索
- 简单问题（DIRECT_ANSWER / GENERAL_TOOL）不进行分解
- 独立于主 ChatModel，使用配置的意图识别模型（可用轻量快速模型降本）
- 通过 Spring AI Structured Output 映射到 `IntentResult`

#### AgentToolCallbackFactory — 意图→Tool 子集映射 + 闭包创建

> 详细实现参考：`AGENTIC-RAG-IMPLEMENTATION-NOTES.md` §2

**职责**：
1. 根据意图识别结果，动态决定暴露给 LLM 的 Tool 子集
2. 通过闭包捕获 `ToolWorkspace` 局部变量，创建 `FunctionToolCallback`

**意图→Tool 映射**：

| 意图 | Tools |
|------|-------|
| `DIRECT_ANSWER` | 无 |
| `RETRIEVAL` | hybridSearch, rerank, docDetail, agentEventLookup |
| `DEEP_RETRIEVAL` | vectorSearch, hybridSearch, rerank, queryRewrite, parentDocLookup, docDetail, agentEventLookup |
| `GENERAL_TOOL` | Calculator, DateTime 等通用工具 |

#### 意图识别 Prompt

> 完整 Prompt 模板见实现参考：`AGENTIC-RAG-IMPLEMENTATION-NOTES.md` §8

**第一版 Prompt 要点**（仅意图分类，无查询分解）：
- 将用户查询分类为 DIRECT_ANSWER / RETRIEVAL / DEEP_RETRIEVAL / GENERAL_TOOL
- 输出结构化 JSON：`{ "intent": "...", "confidence": 0.95 }`
- 低 temperature（0.1），分类任务追求确定性

> **后续迭代**：查询分解 Prompt 规则和示例（子问题拆解、1-5 个限制等）将在启用查询分解时补充。

#### 意图识别模型配置

```yaml
app:
  agent:
    intent-model: deepseek/deepseek-chat   # 意图识别用轻量模型
    intent-temperature: 0.1                 # 低温度，分类任务追求确定性
```

### 2.4 ReAct 循环增强设计（DeepRAG + Self-RAG）

在第二层 ReAct 循环中，LLM 对原始查询的处理流程扩展为四步增强循环。

> **第一版简化**：无查询分解，四步循环作用于单个原始查询（而非多个子问题）。
> 后续迭代启用查询分解后，将扩展为按子问题逐一执行此循环。

```
原始查询的处理流程（第一版：单查询，无子问题分解）：

  ┌──────────────────────────────────────────────────────┐
  │  Step 1: 原子决策 (Atomic Decision) — 来自 DeepRAG    │
  │                                                      │
  │  LLM 判断：这个问题需要检索外部知识吗？                │
  │    ├── retrieve    → 调用检索 Tool                    │
  │    └── parametric  → 直接用自身知识回答（零检索成本）   │
  └──────────────────────┬───────────────────────────────┘
                         │
                    (如果 retrieve)
                         │
  ┌──────────────────────▼───────────────────────────────┐
  │  Step 2: 检索 + 自省评估 (Self-Reflection) — 来自 Self-RAG │
  │                                                      │
  │  执行检索 Tool → 获得文档                              │
  │                                                      │
  │  LLM 自省（结构化 JSON 输出）：                        │
  │    is_relevant: 检索结果是否与查询相关？               │
  │    is_sufficient: 信息是否足够回答？                    │
  │    missing_aspects: 如果不够，缺少什么？               │
  │    next_action: rewrite_and_search / rerank / proceed  │
  │                                                      │
  │  如果不够 → 改写查询重搜 或 精排                       │
  └──────────────────────┬───────────────────────────────┘
                         │
  ┌──────────────────────▼───────────────────────────────┐
  │  Step 3: 中间答案 (Intermediate Answer) — DeepRAG      │
  │                                                      │
  │  基于检索结果或自身知识，生成中间答案                    │
  │  写入 Workspace.intermediateAnswers                   │
  └──────────────────────┬───────────────────────────────┘
                         │
  ┌──────────────────────▼───────────────────────────────┐
  │  Step 4: 最终回答 + 质量自评 — 来自 Self-RAG            │
  │                                                      │
  │  基于中间答案生成最终回答                              │
  │  自评回答质量：                                       │
  │    is_supported: 回答是否有文档支撑                    │
  │    is_useful: 是否完整回答了用户问题                    │
  │    citations: 标注引用来源                             │
  └──────────────────────────────────────────────────────┘
```

#### 2.4.1 原子决策 (Atomic Decision)

**来源**：DeepRAG 的核心思想 — 对查询独立判断是否需要检索。

**工程化实现**：通过 System Prompt 引导 LLM 对查询先输出决策 JSON，再执行。

```json
// LLM 在处理查询前的输出（Structured Output）
{
  "sub_query": "RAG 系统的知识更新机制",
  "decision": "retrieve",
  "reason": "涉及具体的 RAG 技术细节，需要知识库文档支撑"
}
// 或
{
  "sub_query": "什么是 Fine-tuning",
  "decision": "parametric",
  "reason": "Fine-tuning 是基础机器学习概念，自身知识足够"
}
```

**效果**：减少不必要的检索调用，降低延迟和 token 成本。DeepRAG 实验表明检索尝试主要集中在 0-2 次，大多数查询可由模型自身知识回答。

#### 2.4.2 自省式评估 (Self-Reflection)

**来源**：Self-RAG 的 Reflection Tokens — 检索后自评相关性和充分性。

**工程化实现**：检索结果返回后，LLM 输出自省 JSON，驱动下一步决策。

```json
// 检索后的自省输出
{
  "is_relevant": true,
  "is_sufficient": false,
  "missing_aspects": ["Fine-tuning 的知识更新成本对比"],
  "next_action": "rewrite_and_search",
  "rewrite_query": "Fine-tuning 模型知识更新成本和时间开销"
}
```

**自省驱动的决策树**：

```
检索结果 → 自省评估
  ├── is_relevant=false → 改写查询重搜
  ├── is_relevant=true, is_sufficient=true → 生成中间答案
  └── is_relevant=true, is_sufficient=false
        ├── missing_aspects 可通过其他 Tool 补充 → 切换 Tool
        └── missing_aspects 需要新检索 → 改写查询重搜
```

#### 2.4.3 中间答案累积 (Intermediate Answer)

**来源**：DeepRAG — 每处理一个子问题生成中间答案，逐步累积。

```java
// Workspace 中的中间答案
public record IntermediateAnswer(
    int subQueryIndex,       // 关联的子问题索引
    String subQuery,         // 子问题原文
    String answer,           // 中间答案
    String source,           // "retrieval" 或 "parametric"
    List<String> citedDocIds // 引用的文档 ID（parametric 时为空）
) {}
```

**关键设计**：后续子问题的 LLM prompt 中自动注入前面所有中间答案，避免重复检索同一信息。

**注入机制**：`AgentSystemPromptAdvisor.before()` 每轮从 Workspace 读取中间答案，注入到 System Prompt 中。实现方式：

1. `AgentSystemPromptAdvisor` 构造时接收 `ToolWorkspace` 引用（与 Tool 闭包共享同一个对象引用）
2. `before()` 钩子在每轮 LLM 调用前执行，调用 `workspace.getIntermediateAnswersSummary()` 获取已有中间答案
3. 将中间答案作为额外段落追加到 System Prompt 末尾（格式：`## 已收集的信息\n{summary}`）
4. 如果 Workspace 中无中间答案，不注入任何额外内容

**为什么选 this 方案**：
- (a) 构造注入最简单直接 — Advisor 和 Tool 闭包共享同一个 workspace 对象引用，零额外机制
- (b) `advisorParams` 传递需要额外序列化/反序列化，且 Spring AI 的 params 传递机制不够直观
- (c) 专用 Tool（如 `getIntermediateAnswers`）依赖 LLM 主动调用，不可靠且增加 Tool 调用开销

构造时机：`buildAgentChain()` 中，`ToolWorkspace` 创建后立即传给 `AgentSystemPromptAdvisor` 构造器。

#### 2.4.4 检索代价感知 (Retrieval Cost Awareness)

**来源**：DeepRAG 的奖励函数 — 正确性 × 检索成本，鼓励减少不必要的检索。

**工程化实现**：System Prompt 注入代价意识 + Workspace 追踪已有知识。

> 完整 Prompt 片段见实现参考：`AGENTIC-RAG-IMPLEMENTATION-NOTES.md` §9

**关键规则**：
1. 每次检索都有成本（延迟 + token 消耗），优先使用已有知识
2. 检查 Workspace.intermediateAnswers — 如果前面的子问题已检索过相关信息，直接引用
3. 只有在确实需要外部知识时才调用检索工具
4. 能用 rerank 精排解决的，不要重新检索
5. 能用自身知识回答的，不要调用任何工具

#### 2.4.5 回答质量自评 (Answer Quality Self-Evaluation)

**来源**：Self-RAG 的 IsSup / IsUse — 生成后自我评估回答质量。

**工程化实现**：最终回答生成时，LLM 同时输出自评和引用标注。

```json
// 最终回答附带的自评
{
  "answer": "RAG 和 Fine-tuning 在知识更新场景各有优劣...",
  "self_evaluation": {
    "is_supported": true,
    "is_useful": true,
    "confidence": 0.85,
    "citations": [
      { "claim": "RAG 支持实时知识更新", "sourceDoc": "doc1", "sourceFile": "rag-intro.pdf", "page": 3 },
      { "claim": "Fine-tuning 需要重训模型", "source": "parametric" }
    ]
  }
}
```

### 2.5 Tool Workspace — JSON 中间状态

Tool 之间通过结构化 JSON Workspace 传递中间结果。每个 Tool 的闭包捕获同一个 `workspace` 局部变量，无需任何全局状态传递机制。

> 完整 Workspace 类实现参考：`AGENTIC-RAG-IMPLEMENTATION-NOTES.md`（关联 §2.5 的操作分类）

**设计原则**：
- 所有 Tool 的输入输出都是 JSON 字符串（可序列化、可调试）
- Workspace 维护一个 JSON 文档，记录检索中间状态
- Tool 从 Workspace 读取前置结果，执行后更新 Workspace
- 生命周期：`ToolWorkspaceFactory.create()` 创建 → 闭包捕获 → 请求结束 GC 回收

**Workpace 操作分类**：

| 分类 | 操作 | 说明 |
|------|------|------|
| 查询分解 | `setIntent()`, `getSubQueries()`, `markSubQueryCompleted()`, `getPendingSubQueryIndices()` | 子问题生命周期管理 |
| 检索结果 | `getRetrievedDocs()`, `addRetrievedDocs()`, `replaceRetrievedDocs()`, `getDocsForSubQuery()` | 检索结果 CRUD |
| 查询改写 | `getRewrittenQueries()`, `addRewrittenQueries()` | 改写查询管理 |
| 自省评估（Self-RAG）| `addSelfReflection()`, `getSelfReflections()` | 检索后自我评估记录 |
| 中间答案（DeepRAG）| `addIntermediateAnswer()`, `getIntermediateAnswers()`, `getIntermediateAnswersSummary()` | 子问题答案累积 |
| 状态追踪 | `getRetrievalRound()`, `incrementRound()`, `exportState()` | 检索轮次和调试 |

#### SelfReflection — 自省 DTO

```java
/**
 * 自省结果 — 检索后 LLM 的自我评估（Self-RAG 启发）
 *
 * @param subQueryIndex  关联的子问题索引
 * @param isRelevant     检索结果是否相关
 * @param isSufficient   信息是否足够回答
 * @param missingAspects 缺少的方面
 * @param nextAction     下一步：proceed / rewrite_and_search / rerank / switch_tool
 */
public record SelfReflection(
    int subQueryIndex,
    boolean isRelevant,
    boolean isSufficient,
    List<String> missingAspects,
    String nextAction
) {}
```

> `IntermediateAnswer` 定义见 §2.4.3。

#### Workspace JSON 示例

```json
{
  "userId": 42,
  "teamId": null,
  "intent": "DEEP_RETRIEVAL",
  "subQueries": ["RAG 如何实现知识更新", "Fine-tuning 如何更新知识", "两者对比"],
  "completedSubQueries": [0, 1],
  "round": 3,
  "retrievedDocs": [
    { "docId": "abc123", "content": "...", "score": 0.89, "source": "hybridSearch", "subQueryIndex": 0 }
    // ... 更多文档省略
  ],
  "selfReflections": [
    { "subQueryIndex": 0, "isRelevant": true, "isSufficient": true, "missingAspects": [], "nextAction": "proceed" }
    // ... 更多自省结果省略
  ],
  "intermediateAnswers": [
    { "subQueryIndex": 0, "subQuery": "...", "answer": "...", "source": "retrieval", "citedDocIds": ["abc123"] }
    // ... 更多中间答案省略
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

### 2.6 Agent 期望行为模式（概念流程图）

> **⚠️ 重要：这是期望行为模式，不是代码可控的状态机。**
>
> 以下流程图描述 LLM 在 System Prompt 引导下**应该**遵循的行为轨迹，所有状态转换由 LLM 的 ReAct 循环隐式驱动，不由代码显式控制。System Prompt 可以引导但无法强制 LLM 严格按此顺序执行（LLM 可能跳步、可能不走自省直接生成答案）。
>
> **不引入显式 `AgentStateMachine` 类。** 护栏和约束通过 Advisor 拦截 + Prompt 约束实现（如最大迭代轮次、同一 Tool 连续调用检测），而非状态机转换守卫。
>
> 如果后续迭代需要精确控制状态转换，需要完全不同的架构（如 LangGraph4j 的做法），与当前 Spring AI 的 `ToolCallAdvisor` 机制冲突。

```
                              ┌──────────────┐
                              │   RECEIVED    │ ← 用户请求到达
                              └──────┬───────┘
                                     │
                                     ▼
                              ┌──────────────┐
                              │  CLASSIFYING  │ ← 意图识别 + 查询分解
                              └──────┬───────┘
                                     │
                        ┌────────────┼────────────┐
                        │            │            │
                        ▼            ▼            ▼
                 ┌───────────┐ ┌──────────┐ ┌────────────┐
                 │DIRECT_ANS │ │GENERAL_  │ │ RETRIEVAL  │
                 │  WER(终态)│ │TOOL(终态)│ │ DEEP_RETR  │
                 └───────────┘ └──────────┘ └─────┬──────┘
                        │            │            │
                        ▼            ▼            ▼
                   直接回答     调用通用Tool   ┌───────────────┐
                                               │ SUBQUERY_LOOP │
                                               │ (子问题循环)   │
                                               └───────┬───────┘
                                                       │
                                    ┌──────────────────┼──────────────────┐
                                    │                  │                  │
                                    ▼                  ▼                  ▼
                             ┌─────────────┐  ┌──────────────┐  ┌──────────────┐
                             │  ATOMIC_DEC  │  │  ATOMIC_DEC  │  │  ATOMIC_DEC  │
                             │  → retrieve  │  │  → parametric│  │  → retrieve  │
                             │ (子问题[i])  │  │ (子问题[i])  │  │ (子问题[i])  │
                             └──────┬──────┘  └──────┬───────┘  └──────┬──────┘
                                    │                  │                  │
                                    ▼                  ▼                  ▼
                             ┌─────────────┐  ┌──────────────┐  ┌──────────────┐
                             │  RETRIEVING │  │PARAMETRIC_ANS│  │  RETRIEVING  │
                             │ (调用Tool)   │  │ (自身知识回答) │  │ (调用Tool)   │
                             └──────┬──────┘  └──────┬───────┘  └──────┬──────┘
                                    │                  │                  │
                                    ▼                  │                  │
                             ┌─────────────┐           │                  │
                             │ REFLECTING  │           │                  │
                             │ (自省评估)   │           │                  │
                             └──────┬──────┘           │                  │
                          ┌─────────┼─────────┐        │                  │
                          │         │         │        │                  │
                          ▼         ▼         ▼        │                  │
                    relevant+   relevant+   not         │                  │
                    sufficient  insufficient relevant   │                  │
                          │         │         │        │                  │
                          ▼         ▼         ▼        │                  │
                    ┌─────────┐ ┌─────────┐ ┌─────────┐│                  │
                    │INTER_ANS│ │REWRITE   │ │SWITCH   ││                  │
                    │(中间答案)│ │(改写重搜)│ │(换Tool) ││                  │
                    └────┬────┘ └────┬────┘ └────┬────┘│                  │
                         │           │           │     │                  │
                         ▼           ▼           ▼     ▼                  ▼
                    ┌──────────────────────────────────────────────┐
                    │              所有子问题处理完毕？               │
                    └──────────────────────┬───────────────────────┘
                                           │
                              ┌────────────┼────────────┐
                              │ 否                      │ 是
                              ▼                        ▼
                        ┌──────────┐           ┌──────────────┐
                        │下一子问题 │           │  GENERATING  │
                        │ ATOMIC_DEC│           │ (生成最终回答)│
                        └──────────┘           └──────┬───────┘
                                                      │
                                                      ▼
                                               ┌──────────────┐
                                               │SELF_EVALUATE │
                                               │(质量自评)     │
                                               └──────┬───────┘
                                                      │
                                                      ▼
                                               ┌──────────────┐
                                               │  COMPLETED   │ (终态)
                                               └──────────────┘
```

#### 状态定义

| 状态 | 说明 | 进入条件 | 可能的转换 |
|------|------|----------|-----------|
| `RECEIVED` | 请求到达，尚未处理 | 用户发送消息 | → CLASSIFYING |
| `CLASSIFYING` | 意图识别 + 查询分解 | 进入 Agent 分支 | → DIRECT_ANSWER / GENERAL_TOOL / SUBQUERY_LOOP |
| `DIRECT_ANSWER` | LLM 直接回答，无 Tool（终态） | 意图=DIRECT_ANSWER | → COMPLETED |
| `GENERAL_TOOL` | 调用通用 Tool（终态） | 意图=GENERAL_TOOL | → COMPLETED |
| `SUBQUERY_LOOP` | 子问题循环入口 | 意图=RETRIEVAL/DEEP_RETRIEVAL | → ATOMIC_DECIDE |
| `ATOMIC_DECIDE` | 原子决策：retrieve or parametric | 取出下一个未处理子问题 | → RETRIEVING / PARAMETRIC_ANS |
| `RETRIEVING` | 调用检索类 Tool | 决策=retrieve | → REFLECTING |
| `PARAMETRIC_ANS` | 用自身知识生成中间答案 | 决策=parametric | → INTERMEDIATE_ANS |
| `REFLECTING` | 检索后自省评估 | Tool 返回结果 | → INTERMEDIATE_ANS / REWRITE / SWITCH_TOOL |
| `INTERMEDIATE_ANS` | 生成中间答案 | 自省通过 或 parametric | → SUBQUERY_LOOP(下一子问题) / GENERATING |
| `REWRITE` | 改写查询重搜 | 自省=is_sufficient=false | → RETRIEVING |
| `SWITCH_TOOL` | 切换 Tool 重试 | 自省=not_relevant | → RETRIEVING |
| `GENERATING` | 综合所有中间答案生成最终回答 | 所有子问题处理完毕 | → SELF_EVALUATE |
| `SELF_EVALUATE` | 回答质量自评 + 引用标注 | 最终回答生成 | → COMPLETED |
| `COMPLETED` | 正常完成（终态） | 自评完成 | — |
| `GUARDRAIL_STOPPED` | 护栏触发强制停止（终态） | 迭代/token 超限 | — |
| `DEGRADED` | 降级到 MULTI_TURN（终态） | Agent 完全不可用 | — |

#### 护栏在状态机中的切入点

```
每次状态转换前检查护栏：

  SUBQUERY_LOOP → ATOMIC_DECIDE 之前
    ├── GuardrailCheck.ok()       → 正常继续
    ├── GuardrailCheck.warn()     → 注入提醒，继续循环
    └── GuardrailCheck.stop()     → → GUARDRAIL_STOPPED (终态)

  GUARDRAIL_STOPPED 处理：
    ├── Workspace 有中间答案 → 用已有答案 + 强制 GENERATING
    └── Workspace 无中间答案 → 返回提示信息给用户
```

#### 容错在状态机中的切入点

```
  CLASSIFYING 失败 → 降级为 DEEP_RETRIEVAL（安全默认），继续 SUBQUERY_LOOP
  RETRIEVING 失败 → ToolResult.failure()，进入 REFLECTING（自省决定换策略）
  REFLECTING 所有策略都失败 → INTERMEDIATE_ANS(标明信息不足)，继续下一子问题
  GENERATING 失败 → 复用 FallbackChainProvider（现有兜底策略）
  全局降级 → DEGRADED（回退到 MULTI_TURN + RAG Pipeline）
```

### 2.7 Advisor 链差异

- AGENT 模式使用**独立的 ToolCallAdvisor**，只挂载意图识别后的 Tool 子集，不复用全局单例
- RAG 不再通过 `RetrievalAugmentationAdvisor` 固定 Pipeline 执行，改为 Tool 自主编排
- 先阻塞式响应，流式支持在后续迭代

详细 Advisor 链组成见 §4.2。

---

## 3. Tool 设计

### 3.1 Tool 清单

| Tool | 描述 | 封装组件 | 输入来源 | 输出更新 | 优化项 |
|------|------|----------|----------|----------|--------|
| `vectorSearchTool` | 向量语义检索 | `VectorStore.similaritySearch()` | Workspace.userId/teamId | Workspace.retrievedDocs | P0 摘要输出 |
| `bm25SearchTool` | BM25 全文检索 | `VectorStoreMapper.bm25Search()` | Workspace.userId/teamId | Workspace.retrievedDocs | P0 摘要输出 |
| `hybridSearchTool` | 混合检索 + RRF | `HybridSearchService`（新增，提取 HybridDocumentRetriever 核心逻辑） | Workspace | Workspace.retrievedDocs | P0 摘要输出 + P1 去重 |
| `rerankTool` | 语义精排 | `BailianRerankPostProcessor` 核心逻辑 | Workspace.retrievedDocs | Workspace.retrievedDocs (替换) | P1 统一输出 |
| `queryRewriteTool` | 查询改写 | `RewriteQueryTransformer` prompt | LLM 调用 | Workspace.rewrittenQueries | P1 统一输出 |
| `parentDocLookupTool` | 子块→父文档 | `ParentDocumentPostProcessor` | Workspace.retrievedDocs | Workspace.retrievedDocs (替换) | P1 统一输出 |
| `knowledgeBaseInfoTool` | 知识库元信息 | `VectorStoreMapper` 统计查询 | Workspace.userId/teamId | 无（直接返回） | P1 统一输出 |
| `docDetailTool` | 文档详情获取（P0） | `VectorStoreMapper` + `ts_headline` | docIds + query | 无（直接返回摘要片段） | P0 按需获取 |
| `agentEventLookupTool` | 历史事件回溯（P2） | `AgentEventStore.searchEvents()` | query + sessionId + userId | 无（直接返回匹配事件） | P2 会话连续性 |

> **优化说明**：
> - **P0 检索结果两层分离**：检索类 Tool（vectorSearch/bm25Search/hybridSearch）返回摘要，完整文档存储在 PostgreSQL
> - **P1 Tool 输出沙盒化**：所有 Tool 返回统一 JSON 格式，控制在 200 token 以内
> - **P1 自省重检去重**：hybridSearchTool 集成去重逻辑，避免重复文档
> - **P2 会话连续性**：agentEventLookupTool 支持按需检索历史事件
> - **P2 上下文预算管理**：所有 Tool 输出根据上下文预算动态调整详细程度
> - **P3 智能缓存**：检索类 Tool 集成缓存，避免重复检索

### 3.2 包结构

```
com.smart.rag.rag.agent/
├── intent/
│   ├── AgentIntent.java              // 意图枚举
│   ├── IntentClassifier.java         // 意图分类器
│   ├── IntentResult.java             // 分类结果 record
│   └── (IntentToolSetRegistry 职责已合并到 AgentToolCallbackFactory)
├── workspace/
│   ├── ToolWorkspace.java            // JSON 中间状态
│   ├── ToolWorkspaceFactory.java     // 按请求创建
│   └── RetrievedDocument.java        // 检索结果 DTO record
├── tool/
│   ├── RagTool.java                  // RAG Tool 标记接口
│   ├── VectorSearchTool.java         // 向量检索（接收 workspace 参数）
│   ├── HybridSearchTool.java         // 混合检索 + RRF（接收 workspace 参数）
│   ├── RerankTool.java               // 语义精排（接收 workspace 参数）
│   ├── QueryRewriteTool.java         // 查询改写（接收 workspace 参数）
│   ├── ParentDocLookupTool.java      // 子块→父文档（接收 workspace 参数）
│   ├── KnowledgeBaseInfoTool.java    // 知识库元信息（接收 workspace 参数）
│   ├── DocDetailTool.java            // 按需获取文档片段（P0 优化，ts_headline）
│   ├── AgentEventLookupTool.java     // 历史事件回溯（P2 优化）
│   └── callback/
│       └── AgentToolCallbackFactory.java  // 闭包创建 FunctionToolCallback
├── service/
│   └── HybridSearchService.java     // 提取 HybridDocumentRetriever 核心逻辑，供 Tool 和 Retriever 共用
├── event/
│   ├── AgentEventStore.java         // Agent 事件存储（PG-only V1）
│   └── AgentEventMapper.java        // MyBatis-Plus Mapper
└── advisor/
    └── AgentSystemPromptAdvisor.java    // 动态 System Prompt 注入
```

### 3.3 RagTool 标记接口 + RetrievedDocument

#### RagTool 标记接口

```java
/**
 * RAG Tool 标记接口
 *
 * 用于 AgentToolCallbackFactory 区分 RAG Tool 和通用 Tool。
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

> 详细实现参考：`AGENTIC-RAG-IMPLEMENTATION-NOTES.md` §3

> **注意**：Tool 类**不使用 `@Tool` 注解**，而是由 `AgentToolCallbackFactory` 通过闭包包装为 `FunctionToolCallback` 注册给框架。Tool 清单及封装组件见 §3.1。

**设计说明**：`HybridDocumentRetriever` 构造函数绑定了 userId/teamId，不适合 Tool 场景（Tool 需从 workspace 获取用户信息），因此提取 `HybridSearchService` 封装检索核心逻辑，两者都委托给它。

#### 3.4.1 Tool 前置条件校验

每个 Tool 的 `execute()` 入口在执行业务逻辑前，先校验 Workspace 前置状态。不满足时返回 `ToolResult.failure()`，引导 LLM 切换策略。

| Tool | 前置条件 | 失败 errorCategory | 失败提示 |
|------|----------|-------------------|----------|
| `vectorSearch` | 无 | — | — |
| `bm25Search` | 无 | — | — |
| `hybridSearch` | 无 | — | — |
| `rerank` | `workspace.retrievedDocs` 非空 | `PRECONDITION_FAILED` | "没有可精排的文档。请先调用检索工具（hybridSearch 或 vectorSearch）获取文档。" |
| `parentDocLookup` | `workspace.retrievedDocs` 含子块（metadata 含 parentId） | `PRECONDITION_FAILED` | "当前文档无父子关系，无需父文档查找。" |
| `queryRewrite` | 无 | — | — |
| `knowledgeBaseInfo` | 无 | — | — |

### 3.5 AgentToolCallbackFactory — 闭包创建 FunctionToolCallback

**核心设计**：每次请求动态创建 `FunctionToolCallback`，闭包捕获 `ToolWorkspace` 局部变量。
消除全局状态（ThreadLocal），Tool 方法签名零侵入，请求结束 GC 自动回收 workspace。

> **✅ Phase 2 前置验证已通过**：Spring AI 1.1.6 的 `FunctionToolCallback.builder(name, biFunction)`
> 泛型签名已通过 PoC 验证（Poc1_FunctionToolCallbackSignatureTest），
> 确认签名为 `FunctionToolCallback.<I, O>builder(String name, BiFunction<I, ToolContext, O> fn)`。
>
> **⚠️ PoC 1 补充发现**：`FunctionToolCallback.call()` 返回值经 `ToolCallResultConverter` 自动 JSON 序列化。Tool 方法返回 String 时无需手动序列化。返回 Java 对象（如 ToolResult record）时，框架会自动调用 ObjectMapper 序列化。

#### 传递路径

```
buildAgentChain() 创建 workspace（局部变量）
  → AgentToolCallbackFactory.createToolCallbacks(intent, workspace)
    → 闭包捕获 workspace，创建 FunctionToolCallback
      → StaticToolCallbackResolver 包装闭包 callbacks
        → DefaultToolCallingManager 持有 resolver
          → ToolCallAdvisor 调用 agentToolManager
            → LLM 请求 tool call → resolver 解析 → callback.call(jsonInput)
              → 闭包内 tool.execute(request, workspace) → 更新 workspace → 返回 JSON
```

#### AgentToolCallbackFactory

> 实现参考：`AGENTIC-RAG-IMPLEMENTATION-NOTES.md` §2
> 意图→Tool 映射关系见 §2.3。

**闭包方案**：每个 `FunctionToolCallback` 的 lambda 闭包捕获 request 级 `ToolWorkspace` 局部变量，Tool 类本身不感知传递机制。

> ThreadLocal 方案因异常安全风险已否决（详见 §8 Q2），当前采用闭包捕获方案。

#### returnDirect 优化

**`returnDirect` 优化**：Spring AI `ToolCallAdvisor` 支持 `ToolMetadata.returnDirect=true`，Tool 结果直接返回给客户端，不再回传 LLM。适用于不需要 LLM 加工的简单查询（如 `knowledgeBaseInfo`）。检索类 Tool 不应设置 `returnDirect`。

---

## 4. 核心改动

### 4.1 改动清单

| # | 文件 | 改动类型 | 说明 |
|---|------|----------|------|
| **意图识别层** | | | |
| 1 | `AgentIntent.java` | 新增 | 意图枚举 |
| 2 | `IntentResult.java` | 新增 | 分类结果 record |
| 3 | `IntentClassifier.java` | 新增 | 意图分类器（独立 LLM 调用） |
| **Workspace 层** | | | |
| 5 | `ToolWorkspace.java` | 新增 | JSON 中间状态管理 |
| 6 | `ToolWorkspaceFactory.java` | 新增 | 按请求创建 Workspace |
| 7 | `RetrievedDocument.java` | 新增 | 检索结果 DTO record（含 subQueryIndex 关联子问题） |
| **RAG Tool 层** | | | |
| 8 | `RagTool.java` | 新增 | RAG Tool 标记接口 |
| 9 | `VectorSearchTool.java` | 新增 | 向量检索 Tool |
| 10 | `Bm25SearchTool.java` | 新增 | BM25 全文检索 Tool |
| 11 | `HybridSearchTool.java` | 新增 | 混合检索 Tool |
| 12 | `RerankTool.java` | 新增 | Rerank Tool |
| 13 | `QueryRewriteTool.java` | 新增 | 查询改写 Tool |
| 14 | `ParentDocLookupTool.java` | 新增 | 父文档查找 Tool |
| 15 | `KnowledgeBaseInfoTool.java` | 新增 | 知识库元信息 Tool |
| 16 | `AgentToolCallbackFactory.java` | 新增 | 闭包创建 FunctionToolCallback（按意图动态生成 Tool 子集，合并原 IntentToolSetRegistry 职责） |
| 17 | `HybridSearchService.java` | 新增 | 提取 HybridDocumentRetriever 核心逻辑，供 Tool 和 Retriever 共用 |
| 17a | `DocDetailTool.java` | 新增 | 按需获取文档片段（P0 优化，ts_headline） |
| 17b | `AgentEventLookupTool.java` | 新增 | 历史事件回溯（P2 优化） |
| **Advisor 层** | | | |
| 18 | `AgentSystemPromptAdvisor.java` | 新增 | 根据意图动态注入 System Prompt（实现 BaseAdvisor，非旧版 CallAroundAdvisor） |
| **编排层改动** | | | |
| 19 | `ChatMode.java` | 修改 | 新增 `AGENT` 枚举值 |
| 20 | `AgentModeStrategy.java` | 新增 | AGENT 模式策略 |
| 21 | `ChatModeStrategy.java` | 修改 | 新增 `default isAgentMode()` |
| 22 | `ChatRequest.java` | 修改 | mode 正则扩展加入 AGENT（P1） |
| 23 | `ChatAdvisorChainFactory.java` | 修改 | AGENT 分支：闭包 ToolCallback + 自建 ToolCallAdvisor（P2） |
| 24 | `ChatRequestSpecFactory.java` | 修改 | AGENT 分支：跳过 tools() + 跳过 DB Prompt + 跳过 DB ModelParams（P3/P4） |
| 25 | `ChatServiceImpl.java` | 修改 | AGENT 模式支持（阻塞式 + 元数据注入） |
| 26 | `ChatController.java` | 修改 | 响应序列化分支 |
| 27 | `AgentChatResponse.java` | 新增 | Agent 响应 DTO（P6） |
| **配置** | | | |
| 28 | `AgentRagProperties.java` | 新增 | Agent 模式配置 |
| 29 | `application.yml` | 修改 | 新增 app.agent.* 配置 |
| **DTO（论文增强）** | | | |
| 30 | `SelfReflection.java` | 新增 | 自省结果 record（Self-RAG: isRelevant/isSufficient/nextAction） |
| 31 | `IntermediateAnswer.java` | 新增 | 中间答案 record（DeepRAG: subQuery/answer/source/citedDocIds） |
| **事件存储层（P2 优化）** | | | |
| 17c | `AgentEventStore.java` | 新增 | Agent 事件存储（PG-only V1，与 AgentTrace 合并设计） |
| 17d | `AgentEventMapper.java` | 新增 | MyBatis-Plus Mapper |
| 17e | `V15__agent_session_event.sql` | 新增 | Flyway 迁移，建表 + 索引 |
| **容错与安全** | | | |
| 32 | `ToolResult.java` | 新增 | Tool 调用统一结果 record（success/failure + errorCategory） |
| 33 | `AgentGuardrails.java` | 新增 | ReAct 循环护栏（迭代总数/token 消耗/连续 Tool 三指标） |
| 34 | `AgentDegradationStrategy.java` | 新增 | Agent 全局降级策略（降级到 MULTI_TURN） |
| 35 | `AgentTrace.java` | 新增 | Agent 执行追踪记录（与 AgentSessionEvent 合并设计，详见 §6.4） |
| 36 | `ToolCallRecord.java` | 新增 | 单次 Tool 调用记录（映射到 AgentSessionEvent TOOL_CALLED 事件） |
| **优化相关（Context Mode 启发）** | | | |
| 37 | `ContextBudgetManager.java` | 新增 | 上下文预算管理器（P2 优化） |
| 38 | `AgentCacheManager.java` | 新增 | 智能缓存管理器（P3 优化） |

### 4.2 ChatAdvisorChainFactory 改动

> 实现参考：`AGENTIC-RAG-IMPLEMENTATION-NOTES.md` §5

**`buildAgentChain()` 步骤**：

| 步骤 | 操作 | 说明 |
|------|------|------|
| 1 | `ConversationContextAdvisor` | 上下文注入（与 SIMPLE/MULTI_TURN 一致） |
| 2 | `getGlobalAdvisors()` | RateLimit、ContentFilter 等全局 Advisor |
| 3 | `intentClassifier.classify()` | 意图识别 + 查询分解（阻塞式 LLM 调用） |
| 4 | `workspaceFactory.create()` | 创建请求级 Workspace（局部变量） |
| 5 | `agentToolCallbackFactory.createToolCallbacks()` | 闭包创建 FunctionToolCallback（捕获 workspace） |
| 6 | `StaticToolCallbackResolver` → `DefaultToolCallingManager` → `ToolCallAdvisor` | 自建独立 ToolCallAdvisor，不复用全局单例 |
| 7 | `AgentSystemPromptAdvisor(intent, mergedPrompt, workspace)` | 动态 System Prompt + 每轮中间答案注入（构造时注入 workspace 引用） |
| 8 | `MessageChatMemoryAdvisor` | 对话记忆 |

> **关键变更**：
> - ❌ 删除 `ToolWorkspaceHolder.set(workspace)` — 不再使用 ThreadLocal
> - ❌ 删除 `AgentContextCleanupAdvisor` — 无全局状态需要清理
> - ✅ `agentToolCallbackFactory.createToolCallbacks(intent, workspace)` — 闭包捕获
> - ✅ `StaticToolCallbackResolver` 包装闭包 callbacks，注入到独立的 `DefaultToolCallingManager`
> - ✅ 每次 Agent 请求自建 `ToolCallAdvisor`，不复用全局单例
> - ✅ CAG 上下文在 buildAgentChain 中合并到 Agent Prompt，`AgentSystemPromptAdvisor` 只负责注入

### 4.3 AgentSystemPromptAdvisor — 动态 System Prompt

> 实现参考：`AGENTIC-RAG-IMPLEMENTATION-NOTES.md` §4

**职责**：根据意图识别结果注入动态 System Prompt，并在每轮 ReAct 循环前从 Workspace 读取中间答案注入。

**构造参数**：
1. `AgentIntent intent` — 意图分类结果
2. `String mergedSystemPrompt` — 已合并的最终 Prompt 字符串（Agent Prompt + CAG 上下文）
3. `ToolWorkspace workspace` — 与 Tool 闭包共享同一个引用，用于读取中间答案

**接口**：实现 Spring AI 1.1.6 的 `BaseAdvisor` 接口（`before()`/`after()` 模式）：
- `getOrder()`: 返回 1（在 `ToolCallAdvisor`(order=2) 之前执行）
- `before()`: 将 `mergedSystemPrompt` 作为 `SystemMessage` 注入到 prompt instructions 首位；如果 `workspace.getIntermediateAnswers()` 非空，追加中间答案段落（`## 已收集的信息\n{summary}`）
- `after()`: 不修改响应

> **上下文优化**：原 §4.3.1 三层渐进压缩方案已被优化文档的 P0（检索结果两层分离）取代 —
> 检索结果返回摘要而非全量内容，从源头消除上下文爆炸风险，不再需要运行时压缩。
> 详见 `AGENTIC-RAG-OPTIMIZATIONS.md` 优化一。

### 4.4 配置设计

> 完整配置类（含所有字段、默认值、校验逻辑）见实现参考：`AGENTIC-RAG-IMPLEMENTATION-NOTES.md` §10

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

---

## 5. 与现有代码的兼容性

### 5.1 零影响原则

| 现有功能 | 影响 | 说明 |
|----------|------|------|
| SIMPLE 模式 | 零影响 | 不走 AGENT 分支 |
| MULTI_TURN 模式 | 零影响 | 仍用 `RetrievalAugmentationAdvisor` |
| `RagAdvisorFactory` | 保留 | MULTI_TURN 模式继续使用 |
| `HybridDocumentRetriever` | 保留 + 复用 | Tool 复用其核心逻辑，不修改原类 |
| `ToolRegistry` | 保留 | AGENT 模式不使用 ToolRegistry，改用 AgentToolCallbackFactory |
| `CalculatorTools` / `DateTimeTools` | 保留 | Agent 模式通过 AgentToolCallbackFactory 按需暴露 |
| ETL Pipeline | 零影响 | 文档入库流程不变 |
| 评估模块 | 零影响 | 评估仍基于 `RetrievalAugmentationAdvisor` |
| **CAG 上下文增强** | **保留 + 合并注入** | AGENT 模式下 CAG 输出合并到 Agent System Prompt，详见 5.2 |

### 5.2 CAG 上下文增强在 Agent 模式下的适用性

现有 CAG（Context-Augmented Generation）模块负责收集三类运行时信号并注入 System Prompt：

| 信号 | 数据来源 | 注入内容 |
|------|----------|----------|
| 用户画像 (`UserContext`) | 用户服务 | 昵称、角色 |
| 会话状态 (`SessionContext`) | 消息数量 | 对话阶段 |
| 策略约束 (`PolicyContext`) | 角色权限 | 回答限制规则 |

**CAG 与 Agentic RAG 的关系是正交的**：

```
CAG 解决的问题：你是谁？有什么约束？（用户级上下文）
Agent 解决的问题：怎么查？查什么？（检索策略编排）

两者职责不重叠，合并注入即可：

System Prompt = Agent Prompt（检索策略指导 + 原子决策引导）
             + CAG Context（用户信息 + 对话阶段 + 回答约束）
```

**三种模式下的 CAG 行为**（Advisor 链层面对比见 §2.7）：

| 模式 | CAG 来源 | 注入方式 |
|------|----------|----------|
| SIMPLE | `ContextPromptInjector` | 直接注入 ChatClient system prompt |
| MULTI_TURN | `ContextPromptInjector` | 直接注入 ChatClient system prompt |
| AGENT | `ContextPromptInjector` → `AgentSystemPromptAdvisor` | 合并到 Agent 动态 System Prompt |

> 合并逻辑见 §4.2 步骤 7。

**CAG 模块零改动**：`CagProperties`、`RequestContext`、`RequestContextManager`、`ContextPromptInjector` 等所有文件不需要任何修改。

### 5.3 组件复用关系

```
HybridSearchService（新增，提取核心逻辑）
    ↑ 复用核心逻辑    ↑ 复用核心逻辑
    |                 |
HybridDocumentRetriever（现有）  HybridSearchTool（新增）

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

### 5.4 集成风险与修复方案

> 实现参考：`AGENTIC-RAG-IMPLEMENTATION-NOTES.md` §14

逐项审查现有代码后，识别出 6 个真实存在的集成冲突点。

#### P1: `ChatRequest.mode` 校验正则硬编码

**问题**：mode 正则只允许 `SIMPLE|MULTI_TURN`，传入 `AGENT` 会被 Bean Validation 直接拒绝。

**修复**：正则扩展为 `^(SIMPLE|MULTI_TURN|AGENT)$`。

**改动文件**：`ChatRequest.java`（1 行）

---

#### P2: `ToolCallAdvisor` 全局单例 — Agent 需按意图动态创建

**问题**：全局 `ToolCallAdvisor` 绑定所有通用 Tool；Agent 需按意图动态过滤 Tool 子集，且每次请求 workspace 不同。这是**最核心的架构冲突**。

**修复**：Agent 分支自建 `ToolCallAdvisor`，通过 `AgentToolCallbackFactory` 闭包创建带 workspace 的 `FunctionToolCallback`，走 `StaticToolCallbackResolver` → `DefaultToolCallingManager` 传递链，不复用全局 Bean。详见 §4.2。

**改动文件**：`ChatAdvisorChainFactory.java`（新增 `buildAgentChain()` 方法，不改现有 `buildChain`）

---

#### P3: `ChatRequestSpecFactory` 全量 Tool 绑定

**问题**：`spec.tools()` 全量挂载所有工具，Agent 模式下会与自建 `ToolCallAdvisor` 重复注册。

**修复**：Agent 分支跳过 `spec.tools()` 调用。

**改动文件**：`ChatRequestSpecFactory.java`（1 行条件判断）

---

#### P4: System Prompt 固定从 DB 查询 — Agent 需动态生成

**问题**：现有 System Prompt 按 modelId 从 DB 查固定模板。Agent 需要**运行时动态生成**（含原子决策引导、自省格式、检索代价规则、可用 Tool 列表、CAG 上下文）。

**修复**：Agent 分支跳过 DB System Prompt + DB ModelParams，由 `AgentSystemPromptAdvisor` 接管动态注入。

**设计决策**：Agent 模式下 `temperature`、`topP` 等参数由 `AgentRagProperties` 统一控制，不从 DB `ModelParams` 读取——Agent ReAct 循环需要稳定的模型行为（低 temperature 减少幻觉）。

**改动文件**：`ChatRequestSpecFactory.java`（if-else 分支）

---

#### P5: `ChatModeStrategy` 接口能力不足

**问题**：现有接口只有四个 boolean 方法，无法表达 Agent 模式的自建 ToolCallAdvisor、跳过固定 System Prompt、独立编排层等差异。

**修复**：接口新增 `default boolean isAgentMode()` 方法，`AgentModeStrategy` 覆写返回 `true`，其他策略不受影响。

**改动文件**：`ChatModeStrategy.java`（新增 default 方法）、新增 `AgentModeStrategy.java`

---

#### P6: 响应结构缺少 Agent 元数据

**问题**：Agent 响应需携带 `AgentTrace`、`SelfReflection`、引用列表、意图信息等额外元数据，现有 `ChatResponse` 不支持。

**修复**：利用 `ChatResponse.metadata` 扩展注入 Agent 元数据，新增 `AgentChatResponse` record 包装。

**改动文件**：`ChatServiceImpl.java`（Agent 分支）、新增 `AgentChatResponse.java`、`ChatController.java`（响应序列化分支）

---

#### 修复方案汇总

| 问题 | 严重度 | 修复方案 | 改动文件 |
|------|--------|----------|----------|
| P1: mode 正则 | 🔴 阻塞 | 扩展正则加入 AGENT | `ChatRequest.java`（1 行） |
| P2: ToolCallAdvisor 单例 | 🔴 核心 | Agent 分支自建 ToolCallAdvisor | `ChatAdvisorChainFactory.java`（新增方法） |
| P3: 全量 Tool 绑定 | 🟡 冲突 | Agent 分支跳过 spec.tools() | `ChatRequestSpecFactory.java`（1 行） |
| P4: System Prompt 固定 | 🟡 冲突 | Agent 分支跳过 DB 查询 | `ChatRequestSpecFactory.java`（if-else） |
| P5: Strategy 接口 | 🟡 扩展 | 加 default isAgentMode() | `ChatModeStrategy.java` + 新增 `AgentModeStrategy` |
| P6: 响应结构 | 🟢 增强 | AgentChatResponse 包装 | `ChatServiceImpl.java` + 新增 DTO |

**关键约束**：所有修复均通过 **if-else 分支隔离**，SIMPLE 和 MULTI_TURN 的代码路径完全不变。

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
  ├─ F6: 循环不收敛（迭代超限 / token 超限 / 同一 Tool 连续调用需干预）
  │
  ├─ F7: Workspace 状态损坏（并发写入 / 序列化失败）
  │
  ├─ F8: Workspace 不可用（闭包方案下不应发生，仅防御性处理）
  │
  ├─ F9: 累计 token 超过模型上下文窗口 80%
  │
  └─ F10: 主 ChatModel 调用失败（生成最终回答时模型不可用）
```

### 6.2 分层容错策略

#### 第一层：意图识别容错

> 实现参考：`AGENTIC-RAG-IMPLEMENTATION-NOTES.md` §1

容错规则：
- 意图识别失败 → 降级为 `DEEP_RETRIEVAL`（暴露全量 Tool，最安全策略）
- 查询分解失败 → 不分解，用原始查询作为唯一子问题
- 意图识别超时 → 2 次重试 + 5s 超时 → 降级

#### 第二层：Tool 调用容错

> 实现参考：`AGENTIC-RAG-IMPLEMENTATION-NOTES.md` §3.7 + §11

**ToolResult 统一返回格式**（核心字段）：

| 字段 | 类型 | 说明 |
|------|------|------|
| `success` | boolean | 调用是否成功 |
| `action` | String | Tool 名称 |
| `summary` | String | 结果摘要（供 LLM 阅读） |
| `errorMessage` | String? | 失败信息（引导 LLM 换策略） |
| `errorCategory` | String? | `API_ERROR`（可重试）/ `DB_ERROR`（不可重试）/ `INVALID_INPUT` / `INTERNAL_ERROR` |
| `documents` | List? | 检索到的文档 |
| `durationMs` | long | 耗时 |

**关键原则**：
- **Tool 永远不抛异常到 ToolCallAdvisor**：所有异常捕获后转为 `ToolResult.failure()`
- **失败信息指导 LLM**：`errorMessage` 明确告知 LLM 失败原因和建议（"尝试 bm25Search"）
- **错误分类**：`errorCategory` 区分可重试（`API_ERROR`）和不可重试（`DB_ERROR`、`INVALID_INPUT`）
- **超时保护**：每个 Tool 设定执行超时，防止数据库慢查询或 API 无响应阻塞整个 ReAct 循环

#### 第三层：循环护栏（三指标）

> 实现参考：`AGENTIC-RAG-IMPLEMENTATION-NOTES.md` §6

**设计决策**：通过三个指标防止 ReAct 循环失控。

| 指标 | 计算方式 | 默认上限 | 防御目标 |
|------|----------|----------|----------|
| 循环迭代总次数 | 整个 ReAct 循环，每轮 = LLM 决策 → Tool 执行 → 结果回传 | 10 | 防止无限循环 |
| 累计 Token 消耗 | 意图识别 + 所有 ReAct 轮次的 LLM token | 模型上下文窗口 × 80% | 防止 token 爆炸 |
| 同一 Tool 连续调用 | 同一 Tool 连续调用次数（切换 Tool 会重置） | 3 | 软干预：告知 LLM 评估信息是否充足，建议切换 Tool 或直接回答 |

**Token 计数方案 — ChatModel 装饰器模式**：

> **📋 PoC 2 验证结果**（Poc2_ReactLoopUsageMetadataTest，10/10 PASS）：
>
> 1. `ChatResponseMetadata` 默认 usage 为 `EmptyUsage`（非 null），其 `getPromptTokens()` 返回 `0` 而非 `null`。检测真实 usage 须用 `usage.getPromptTokens() > 0` 而非 `!= null`。
> 2. Usage 字段名确认为 `getCompletionTokens()`（非 `getGenerationTokens()`）。
> 3. 外层 Advisor 的 `after()` 只在整个 ReAct 循环结束后调用**一次**，无法逐轮获取 usage。因此采用 `TokenCountingChatModel` 装饰器包装 ChatModel（每轮 `chatModel.call()` 自动累加），绕过此限制。

通过 `TokenCountingChatModel` 装饰器包装真实的 `ChatModel`，实现精确的逐请求 Token 累计：

1. **装饰器设计**：`TokenCountingChatModel` 实现 `ChatModel` 接口，内部委托给真实 `ChatModel`
2. **累计机制**：每次 `chatModel.call()` 调用（包括 ReAct 循环中的中间迭代）自动从 `response.getMetadata().getUsage()` 提取并累加 token 用量
3. **护栏读取**：`AgentGuardrails` 在 `before()` 中读取装饰器的累计计数，获得**精确的**逐迭代累计 token 数
4. **精确计数 + 估算兜底**：优先使用 `Usage` 对象的 `getPromptTokens()` / `getCompletionTokens()` 精确累计；装饰器捕获不到 usage 时（如模型未返回），降级为字符估算（`inputChars / 4 + outputChars / 4`）
5. **零内部依赖**：不依赖 `ToolCallAdvisor` 的内部实现细节，仅依赖 `ChatModel` 公共接口

**Token 计算范围**：仅计入 LLM 的输入/输出 token（意图识别 + 每轮 ReAct），Tool 执行本身（DB 查询、Rerank API）不计入。

**护栏触发行为**：
- **指标 1/2 超标**：硬中断，跳出 ReAct 循环，用已有结果生成回答，告知用户
- **指标 3 超标**：软干预，不跳出循环，注入提醒到下一轮 LLM prompt，LLM 自主决策

**响应示例**：

```
⚠️ 检索过程因达到调用上限而提前停止（已执行 10 轮检索，收集到 13 个文档片段）。
基于已收集的部分信息，回答如下：
```

#### 第四层：全局降级

> 实现参考：`AGENTIC-RAG-IMPLEMENTATION-NOTES.md` §7

**策略**：当 Agent 模式完全不可用时（如 Tool Calling 不支持、意图识别模型不可达），降级到 MULTI_TURN + `RetrievalAugmentationAdvisor`（固定 Pipeline RAG）。降级入口由 `AgentDegradationStrategy` 控制，`buildDegradedChain()` 复用现有 MULTI_TURN 链路构建逻辑。

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
| F6: 同一 Tool 死循环 | 连续调用检测 → 软干预（告知 LLM 评估信息是否充足，建议切换 Tool） | LLM 自主决策切换 Tool 或直接回答 | 可能略慢但最终收敛 |
| F7: Workspace 损坏 | JsonProcessingException | 重建空 Workspace 继续或降级 | MULTI_TURN 模式 | 回退到 Pipeline RAG |
| F8: Workspace 不可用 | 闭包方案不会发生 | workspace 作为闭包局部变量，随请求创建和回收 | 无（设计消除） |
| F9: Token 超限 | Guardrails token 计数（模型上下文窗口 × 80%） | 跳出循环 + 用已有结果回答 + 告知用户 | 回答可能不完整 |
| F10: 主模型失败 | ChatServiceImpl 兜底策略 | 复用现有 FallbackChainProvider | 备选模型回答 | 回退到备选模型 |

> **注**：第一版不实现查询分解，F2 故障点不会触发。保留 F2 条目供后续迭代参考。

### 6.4 可观测性设计

```java
/**
 * Agent 执行追踪记录
 * 每次请求一条，记录完整的 Agent 执行过程
 *
 * 与优化文档 P2 的 AgentSessionEvent 合并设计：
 * - AgentSessionEvent（持久化到 PG）：记录每步事件的详细信息，供会话恢复
 * - AgentTrace（请求级内存对象）：汇总整次请求的统计信息，供日志和响应元数据
 * - ToolCallRecord 的字段映射到 AgentSessionEvent(event_type=TOOL_CALLED) 的 data JSONB
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

> 完整配置类（含所有字段、默认值、校验逻辑）见实现参考：`AGENTIC-RAG-IMPLEMENTATION-NOTES.md` §10

新增配置项：

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `max-tool-iterations` | 10 | ReAct 最大迭代轮次 |
| `max-consecutive-same-tool` | 3 | 同一 Tool 连续调用上限 |
| `context-window-ratio` | 0.8 | token 上限 = 模型窗口 × 此比例 |
| `intent-retries` | 2 | 意图识别重试次数 |
| `intent-timeout-ms` | 5000 | 意图识别超时（ms） |
| `tool-timeout-ms` | 10000 | 单次 Tool 超时（ms） |
| `degrade-on-failure` | true | 失败时是否降级 MULTI_TURN |

---

## 7. 实施计划

### Phase 1: 基础设施（3-4h）

1. `ChatMode` 新增 `AGENT` + `ChatRequest.mode` 正则扩展（P1）
2. `ChatModeStrategy` 加 `default isAgentMode()`（P5）
3. `AgentModeStrategy` 实现
4. `AgentRagProperties` 配置类 + `application.yml`
5. `ToolWorkspace` + `ToolWorkspaceFactory`
6. `RetrievedDocument` + `SelfReflection` + `IntermediateAnswer` DTO
7. ~~`AgentContextCleanupAdvisor`~~ — 闭包方案不需要，删除
8. `AgentChatResponse` DTO（P6）

### Phase 2: 意图识别层（3-4h）

1. `AgentIntent` 枚举 + `IntentResult` record
2. `IntentClassifier`（独立 LLM 调用 + Structured Output）
3. `HybridSearchService` — 提取 `HybridDocumentRetriever` 核心逻辑为独立 Service，
   `HybridDocumentRetriever` 和 `HybridSearchTool` 都委托给它（重构，不改行为）
4. `AgentToolCallbackFactory`（意图→Tool 子集映射 + 闭包创建）
5. 意图识别单元测试
6. **前置 PoC**：~~验证 `FunctionToolCallback.builder()` 泛型签名~~ ✅ 已通过（Poc1_FunctionToolCallbackSignatureTest）

### Phase 3: RAG Tool 实现（4-6h）

1. `RagTool` 标记接口
2. `ToolResult` 统一返回格式（success/failure + errorCategory）
3. 9 个 RAG Tool（每个含异常捕获 + ToolResult + Workspace 操作；含 P0 docDetail + P2 agentEventLookup）
4. 每个 Tool 单元测试

### Phase 4: ReAct 循环增强（3-4h）— 论文驱动

1. `AgentSystemPromptAdvisor`：动态 System Prompt + 中间答案注入（实现 `BaseAdvisor` 接口，非旧版 `CallAroundAdvisor`；含原子决策引导+检索代价意识+自省格式；构造时接收 `ToolWorkspace` 引用，`before()` 每轮读取中间答案注入）
2. System Prompt 定义：
   - 原子决策引导（DeepRAG）：对查询先输出 retrieve/parametric 决策
   - 自省评估格式（Self-RAG）：is_relevant/is_sufficient/next_action
   - 中间答案格式（DeepRAG）：生成中间答案
   - 检索代价规则（DeepRAG）：优先使用已有知识、引用中间答案
   - 回答质量自评（Self-RAG）：is_supported/is_useful/citations
3. Workspace 中间答案注入机制：`AgentSystemPromptAdvisor.before()` 每轮从 workspace 读取中间答案，追加到 System Prompt
4. 增强逻辑单元测试

### Phase 5: 护栏 + 容错（2-3h）

1. `AgentGuardrails`（三指标：迭代总数/token 消耗/连续 Tool）
2. `TokenCountingChatModel` 装饰器 — 包装真实 ChatModel，累计每轮 `response.getMetadata().getUsage()` token 用量
3. `AgentDegradationStrategy`（全局降级）
4. `AgentTrace` + `ToolCallRecord`（可观测性）
5. 容错逻辑单元测试

### Phase 5.5: Context Mode 优化（3-4h）

> 基于 context-mode 开源项目的优化思想，详见 `AGENTIC-RAG-OPTIMIZATIONS.md`

1. **P0 检索结果两层分离**（已在 Phase 3 集成）
   - 检索 Tool 返回摘要，完整文档存储在 PostgreSQL
   - 新增 `docDetailTool` 按需获取完整内容
   - 上下文占用降低 ~87%

2. **P1 Tool 输出沙盒化**（已在 Phase 3 集成）
   - 所有 Tool 返回统一 JSON 格式
   - 控制在 200 token 以内
   - 包含摘要、详细信息引用、状态信息

3. **P1 自省重检去重**（已在 Phase 3 集成）
   - `ToolWorkspace` 增加 `seenDocIds` 去重字段
   - 检索 Tool SQL 层排除已见文档
   - 避免重复文档浪费上下文

4. **P2 会话连续性**（已在 Phase 5 集成）
   - `agent_session_event` 表存储事件
   - `agentEventLookupTool` 按需检索历史
   - Compaction 恢复注入

5. **P2 上下文预算管理**
   - `ContextBudgetManager` 动态管理上下文窗口
   - 根据预算调整 Tool 输出详细程度
   - 优先保留高优先级信息

6. **P3 智能缓存**
   - `AgentCacheManager` 查询结果、文档详情、意图分类缓存
   - Caffeine 本地缓存，避免 Redis 网络开销
   - 相同查询直接返回缓存结果

### Phase 6: 编排层集成（2-3h）

1. `ChatAdvisorChainFactory.buildAgentChain()` — 自建 ToolCallAdvisor（P2），签名与 `buildChain()` 一致
2. `ChatRequestSpecFactory` AGENT 分支 — 跳过 spec.tools() + 跳过 DB System Prompt + 跳过 DB ModelParams（P3/P4）
3. `ChatServiceImpl` AGENT 模式支持 — 阻塞式 + AgentChatResponse 元数据注入（P6）
4. `ChatController` 响应序列化分支

### Phase 7: 端到端验证（2-3h）

1. Agent 端到端测试（DIRECT_ANSWER / RETRIEVAL / DEEP_RETRIEVAL / GENERAL_TOOL）
2. 论文增强验证：
   - 原子决策：部分子问题走 parametric 路径（零检索）
   - 自省评估：不够时触发改写重搜
   - 中间答案累积：后续子问题引用前面的答案
   - 检索代价感知：检索次数低于无引导版本
3. 与 MULTI_TURN 模式回归对比
4. 性能基准（延迟、token 消耗、检索次数）

### Phase 8: 文档与收尾（1h）

1. 更新 `ARCHITECTURE.md`
2. 更新 `RAG-DESIGN.md`
3. 更新 `API-DOCS.md`
4. 性能基准（延迟、token 消耗）
5. Workspace 状态正确性验证

---

## 8. 决策记录

| # | 问题 | 决策 | 理由 |
|---|------|------|------|
| Q1 | Agent 的 ToolCallAdvisor 是否独立？ | **分离** — 按意图动态选择 Tool 子集 | 避免 LLM 选择困难和误调用；按需暴露更可控 |
| Q2 | 检索结果跨 Tool 轮次如何传递？ | **JSON Workspace** — 结构化中间状态 | 可序列化、可调试、可追踪；比 ThreadLocal List 更明确 |
| Q3 | 是否支持独立模型？ | **是 — 通过意图识别层** | 意图识别用轻量模型降本；主 Agent 可用更强推理模型 |
| Q4 | 流式响应？ | **先阻塞式，后续迭代流式** | 降低首版复杂度；流式需处理中间过程展示 |
| Q5 | 意图识别是否包含查询分解？ | **第一版否 — 只做意图分类。查询分解后续迭代视实际需要引入** | 第一版降低 Structured Output 复杂度；意图分类和查询分解性质不同（分类任务 vs 生成任务），拆开更稳定 |
| Q6 | 是否融入 DeepRAG 原子决策？ | **是 — 每个子问题独立判断 retrieve/parametric** | 减少不必要的检索调用，降低成本和延迟 |
| Q7 | 是否融入 Self-RAG 自省评估？ | **是 — 检索后自评 is_relevant/is_sufficient** | 驱动改写重搜、Tool 切换等下一步决策 |
| Q8 | 是否融入 DeepRAG 中间答案？ | **是 — 每个子问题生成中间答案，后续可引用** | 避免重复检索同一信息 |
| Q9 | 是否融入 DeepRAG 检索代价感知？ | **是 — System Prompt 注入代价意识** | 鼓励 LLM 优先使用已有知识 |
| Q10 | 是否融入 Self-RAG 回答质量自评？ | **是 — 最终回答自评 IsSup/IsUse + 引用标注** | 提高回答可信度和可追溯性 |

---

## 9. 后续演进方向

| 方向 | 说明 | 优先级 |
|------|------|--------|
| 流式 Agent | 支持 SSE 中间过程展示（"正在检索..." → "正在精排..."） | P1 |
| Agent 可观测性 | 记录每次 Tool 调用的耗时、token、结果摘要 | P1 |
| 多模型协作 | 意图识别用快速模型，深度推理用推理模型 | P2 |
| Tool 结果缓存 | 相同查询的检索结果短期缓存，避免重复检索 | P2 |
| 自定义意图规则 | 允许用户配置意图分类规则（如特定关键词触发 DEEP_RETRIEVAL） | P3 |
| **Context Mode 深度集成** | 进一步借鉴 context-mode 的沙盒思想，实现更极致的上下文节省 | P2 |
| **事件驱动架构** | 将 Agent 执行过程完全事件化，支持回放、调试、审计 | P2 |
| **多租户缓存隔离** | 团队级别的缓存隔离，避免跨团队数据泄露 | P3 |

### Context Mode 思想应用总结

本设计文档深度借鉴了 [context-mode](https://github.com/mksglu/context-mode) 开源项目的核心思想：

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

> 详细优化方案见 `AGENTIC-RAG-OPTIMIZATIONS.md`
