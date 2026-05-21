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

### 2.2 完整请求流程（融合 DeepRAG + Self-RAG）

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
        │   [1] RAG 系统的知识更新机制       │
        │   [2] Fine-tuning 的知识更新机制   │
        │   [3] 两者在知识更新场景的对比      │
        └─────────────┬──────────────────┘
                      │ subQueries 写入 Workspace
                      ▼
        ┌─ 第二层：Agent ReAct（增强循环）──┐
        │                                    │
        │ ┌── 子问题[1]: "RAG 知识更新" ──┐  │
        │ │                                │  │
        │ │ ① 原子决策(DeepRAG):           │  │
        │ │   LLM: "这个需要知识库"         │  │
        │ │   → hybridSearchTool()         │  │
        │ │   ← JSON: 8 docs               │  │
        │ │                                │  │
        │ │ ② 自省评估(Self-RAG):           │  │
        │ │   is_relevant: true             │  │
        │ │   is_sufficient: true           │  │
        │ │   → 不需要重搜                  │  │
        │ │                                │  │
        │ │ ③ 中间答案(DeepRAG):            │  │
        │ │   → "RAG通过外部知识库实时更新..." │  │
        │ └────────────────────────────────┘  │
        │                                    │
        │ ┌── 子问题[2]: "Fine-tuning更新" ─┐ │
        │ │                                │  │
        │ │ ① 原子决策(DeepRAG):           │  │
        │ │   LLM: "自身知识足够"            │  │
        │ │   → 不调用检索Tool(零成本)       │  │
        │ │                                │  │
        │ │ ③ 中间答案(DeepRAG):            │  │
        │ │   → "Fine-tuning需重训模型..."   │  │
        │ └────────────────────────────────┘  │
        │                                    │
        │ ┌── 子问题[3]: "两者对比" ───────┐ │
        │ │                                │  │
        │ │ ① 原子决策: 需要检索+精排        │  │
        │ │   → hybridSearch() + rerank()   │  │
        │ │                                │  │
        │ │ ② 自省评估:                     │  │
        │ │   is_sufficient: true           │  │
        │ │                                │  │
        │ │ ③ 中间答案:                     │  │
        │ │   → 综合对比分析...              │  │
        │ └────────────────────────────────┘  │
        │                                    │
        │ ④ 最终回答 = 综合所有中间答案        │
        │    + 质量自评(IsSup/IsUse)          │
        │    + 引用标注                       │
        └────────────────────────────────────┘
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

    private final ChatClient intentChatClient;  // 通过 ChatClientRegistry.get(properties.intentModel()) 获取
    // 不依赖 AgentToolCallbackFactory — 意图分类与 Tool 选择职责分离
    // intentChatClient 在构造时从 ChatClientRegistry 获取，非运行时动态创建

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

#### AgentToolCallbackFactory — 意图→Tool 子集映射 + 闭包创建

```java
/**
 * 意图→Tool 子集映射 + FunctionToolCallback 闭包创建工厂
 *
 * 职责合并（原 IntentToolSetRegistry + 工厂逻辑）：
 * 1. 根据意图识别结果，动态决定暴露给 LLM 的 Tool 子集
 * 2. 通过闭包捕获 ToolWorkspace 局部变量，创建 FunctionToolCallback
 *
 * 避免向 LLM 暴露所有工具，减少选择困难和误调用。
 * 每个 Tool 的闭包捕获同一个 workspace 实例，
 * 请求结束 GC 回收，无全局状态。
 */
@Component
public class AgentToolCallbackFactory {

    private final List<RagTool> ragTools;        // 所有 RAG Tool 实现
    private final List<Object> generalToolBeans; // CalculatorTools, DateTimeTools 等

    /**
     * 根据意图创建 ToolCallback 数组
     *
     * @param intent    意图分类
     * @param workspace 请求级 Workspace（闭包捕获）
     * @return 该意图下可用的 ToolCallback 数组
     */
    public ToolCallback[] createToolCallbacks(AgentIntent intent, ToolWorkspace workspace) {
        return switch (intent) {
            case DIRECT_ANSWER -> new ToolCallback[]{}; // 无 Tool
            case RETRIEVAL -> buildRetrievalToolSet(workspace);
            case DEEP_RETRIEVAL -> buildDeepRetrievalToolSet(workspace);
            case GENERAL_TOOL -> buildGeneralToolSet();
        };
    }

    // ... 各 Tool 的 buildXxx 方法通过 FunctionToolCallback 闭包创建
}
```
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

### 2.4 ReAct 循环增强设计（DeepRAG + Self-RAG）

在第二层 ReAct 循环中，LLM 的每个子问题处理流程扩展为四步增强循环：

```
每个子问题的处理流程：

  ┌──────────────────────────────────────────────────────┐
  │  Step 1: 原子决策 (Atomic Decision) — 来自 DeepRAG    │
  │                                                      │
  │  LLM 判断：这个子问题需要检索外部知识吗？              │
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
  │    is_relevant: 检索结果是否与子问题相关？              │
  │    is_sufficient: 信息是否足够回答？                    │
  │    missing_aspects: 如果不够，缺少什么？               │
  │    next_action: rewrite_and_search / rerank / proceed  │
  │                                                      │
  │  如果不够 → 改写查询重搜 或 精排                       │
  └──────────────────────┬───────────────────────────────┘
                         │
  ┌──────────────────────▼───────────────────────────────┐
  │  Step 3: 中间答案累积 (Intermediate Answer) — DeepRAG  │
  │                                                      │
  │  基于检索结果或自身知识，生成该子问题的中间答案          │
  │  写入 Workspace.intermediateAnswers                   │
  │  后续子问题可引用前面的中间答案                         │
  └──────────────────────┬───────────────────────────────┘
                         │
           (所有子问题处理完毕)
                         │
  ┌──────────────────────▼───────────────────────────────┐
  │  Step 4: 最终回答 + 质量自评 — 来自 Self-RAG            │
  │                                                      │
  │  综合所有中间答案生成最终回答                           │
  │  自评回答质量：                                       │
  │    is_supported: 回答是否有文档支撑                    │
  │    is_useful: 是否完整回答了用户问题                    │
  │    citations: 标注引用来源                             │
  └──────────────────────────────────────────────────────┘
```

#### 2.4.1 原子决策 (Atomic Decision)

**来源**：DeepRAG 的核心思想 — 对每个子问题独立判断是否需要检索。

**工程化实现**：通过 System Prompt 引导 LLM 对每个子问题先输出决策 JSON，再执行。

```json
// LLM 在处理每个子问题前的输出（Structured Output）
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

**效果**：减少不必要的检索调用，降低延迟和 token 成本。DeepRAG 实验表明检索尝试主要集中在 0-2 次，大多数子问题可由模型自身知识回答。

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

#### 2.4.4 检索代价感知 (Retrieval Cost Awareness)

**来源**：DeepRAG 的奖励函数 — 正确性 × 检索成本，鼓励减少不必要的检索。

**工程化实现**：System Prompt 注入代价意识 + Workspace 追踪已有知识。

System Prompt 中的关键引导：
```
检索代价规则：
1. 每次检索都有成本（延迟 + token 消耗），优先使用已有知识
2. 检查 Workspace.intermediateAnswers — 如果前面的子问题已检索过相关信息，直接引用
3. 只有在确实需要外部知识时才调用检索工具
4. 能用 rerank 精排解决的，不要重新检索
5. 能用自身知识回答的，不要调用任何工具
```

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

#### Workspace 数据结构

```java
/**
 * Agent Tool Workspace — 请求级别的 JSON 中间状态
 *
 * 设计原则：
 * 1. 所有 Tool 的输入输出都是 JSON 字符串（可序列化、可调试）
 * 2. Workspace 维护一个 JSON 文档，记录检索中间状态
 * 3. Tool 从 Workspace 读取前置结果，执行后更新 Workspace
 * 4. 生命周期：ToolWorkspaceFactory.create() 创建 → 闭包捕获 → 请求结束 GC 回收
 *    （无 ThreadLocal、无手动清理、无泄漏风险）
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

    // ===== 自省评估相关（Self-RAG）=====

    /** 添加自省结果 */
    public void addSelfReflection(SelfReflection reflection) { ... }

    /** 获取所有自省结果 */
    public List<SelfReflection> getSelfReflections() { ... }

    // ===== 中间答案相关（DeepRAG）=====

    /** 添加中间答案 */
    public void addIntermediateAnswer(IntermediateAnswer answer) { ... }

    /** 获取所有中间答案 */
    public List<IntermediateAnswer> getIntermediateAnswers() { ... }

    /** 获取所有中间答案的摘要文本（注入后续子问题的 prompt） */
    public String getIntermediateAnswersSummary() { ... }

    // ===== 状态追踪 =====

    /** 获取当前检索轮次 */
    public int getRetrievalRound() { ... }

    /** 递增检索轮次 */
    public void incrementRound() { ... }

    /** 导出完整状态（调试用） */
    public String exportState() { ... }
}
```

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

#### IntermediateAnswer — 中间答案 DTO

```java
/**
 * 中间答案 — 每个子问题处理后的累积答案（DeepRAG 启发）
 *
 * @param subQueryIndex 关联的子问题索引
 * @param subQuery      子问题原文
 * @param answer        中间答案内容
 * @param source        来源："retrieval" 或 "parametric"
 * @param citedDocIds   引用的文档 ID（parametric 时为空）
 */
public record IntermediateAnswer(
    int subQueryIndex,
    String subQuery,
    String answer,
    String source,
    List<String> citedDocIds
) {}
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
  "round": 3,
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
  "selfReflections": [
    {
      "subQueryIndex": 0,
      "isRelevant": true,
      "isSufficient": true,
      "missingAspects": [],
      "nextAction": "proceed"
    },
    {
      "subQueryIndex": 1,
      "isRelevant": false,
      "isSufficient": false,
      "missingAspects": ["Fine-tuning 知识更新的具体成本"],
      "nextAction": "rewrite_and_search"
    }
  ],
  "intermediateAnswers": [
    {
      "subQueryIndex": 0,
      "subQuery": "RAG 系统如何实现知识更新",
      "answer": "RAG 通过外部知识库实时增强 LLM，无需重训模型...",
      "source": "retrieval",
      "citedDocIds": ["abc123"]
    },
    {
      "subQueryIndex": 1,
      "subQuery": "Fine-tuning 模型如何更新知识",
      "answer": "Fine-tuning 需要在新数据上重新训练模型参数...",
      "source": "parametric",
      "citedDocIds": []
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

### 2.6 Agent 概念流程图

> **说明**：以下为 Agent 请求的概念流程图，描述 LLM 在 System Prompt 引导下的隐式行为。
> **实现层面不包含显式状态机类**——所有状态转换由 LLM 的 ReAct 循环隐式驱动。
> 后续迭代可视需要引入显式 `AgentStateMachine`。

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

### 2.7 Advisor 链对比

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
| `hybridSearchTool` | 混合检索 + RRF | `HybridSearchService`（新增，提取 HybridDocumentRetriever 核心逻辑） | Workspace | Workspace.retrievedDocs |
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
│   └── callback/
│       └── AgentToolCallbackFactory.java  // 闭包创建 FunctionToolCallback
├── service/
│   └── HybridSearchService.java     // 提取 HybridDocumentRetriever 核心逻辑，供 Tool 和 Retriever 共用
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

#### VectorSearchTool

```java
@Component
public class VectorSearchTool implements RagTool {

    private final VectorStore vectorStore;
    private final RagRetrievalProperties properties;

    /**
     * 向量语义检索。workspace 由闭包传入，Tool 不感知传递机制。
     */
    public String execute(VectorSearchRequest request, ToolWorkspace workspace) {
        // 从 workspace 获取 userId/teamId 构建过滤条件
        // 调用 vectorStore.similaritySearch()
        // 追加到 workspace.retrievedDocs
        // 返回 JSON 摘要
    }
}
```

> **注意**：Tool 类**不使用 `@Tool` 注解**，而是由 `AgentToolCallbackFactory` 通过闭包包装为
> `FunctionToolCallback` 注册给框架。Tool 方法签名只关注业务逻辑，workspace 由闭包捕获传入。

#### HybridSearchTool

```java
@Component
public class HybridSearchTool implements RagTool {

    private final HybridSearchService hybridSearchService;

    /**
     * 混合检索 + RRF 融合。workspace 由闭包传入。
     *
     * 设计说明：HybridDocumentRetriever 的构造函数绑定了 userId/teamId，
     * 不适合 Tool 场景（Tool 需从 workspace 获取用户信息）。
     * 因此提取 HybridSearchService 封装检索核心逻辑，
     * HybridDocumentRetriever 和本 Tool 都委托给它。
     */
    public String execute(HybridSearchRequest request, ToolWorkspace workspace) {
        // 委托 HybridSearchService.execute(query, topK, userId, teamId)
        // 追加到 workspace.retrievedDocs
        // 返回 JSON 摘要
    }
}
```

#### RerankTool

```java
@Component
public class RerankTool implements RagTool {

    private final BailianRerankPostProcessor rerankProcessor;

    /**
     * 语义精排。workspace 由闭包传入。
     */
    public String execute(RerankRequest request, ToolWorkspace workspace) {
        // 从 workspace.retrievedDocs 获取待排序文档
        // 调用 BailianRerankPostProcessor 核心逻辑
        // workspace.replaceRetrievedDocs(reranked)
        // 返回 JSON 摘要
    }
}
```

#### QueryRewriteTool

```java
@Component
public class QueryRewriteTool implements RagTool {

    private final ChatClient.Builder chatClientBuilder;

    /**
     * 查询改写。workspace 由闭包传入。
     */
    public String execute(QueryRewriteRequest request, ToolWorkspace workspace) {
        // 调用 LLM 改写查询（复用 RewriteQueryTransformer 的 prompt）
        // workspace.addRewrittenQueries(rewritten)
        // 返回 JSON 摘要
    }
}
```

#### ParentDocLookupTool

```java
@Component
public class ParentDocLookupTool implements RagTool {

    private final VectorStoreMapper vectorStoreMapper;

    /**
     * 子块→父文档替换。workspace 由闭包传入。
     */
    public String execute(ToolWorkspace workspace) {
        // 从 workspace.retrievedDocs 获取子块文档
        // 复用 ParentDocumentPostProcessor 核心逻辑
        // workspace.replaceRetrievedDocs(parentDocs)
        // 返回 JSON 摘要
    }
}
```

#### KnowledgeBaseInfoTool

```java
@Component
public class KnowledgeBaseInfoTool implements RagTool {

    private final VectorStoreMapper vectorStoreMapper;

    /**
     * 知识库元信息查询。workspace 由闭包传入。
     */
    public String execute(ToolWorkspace workspace) {
        // 从 workspace 获取 userId/teamId
        // 查询文档数量、分块数量、最近更新时间
        // 返回 JSON 格式的知识库统计
    }
}
```

### 3.5 AgentToolCallbackFactory — 闭包创建 FunctionToolCallback

**核心设计**：每次请求动态创建 `FunctionToolCallback`，闭包捕获 `ToolWorkspace` 局部变量。
消除全局状态（ThreadLocal），Tool 方法签名零侵入，请求结束 GC 自动回收 workspace。

> **⚠️ Phase 2 前置验证**：Spring AI 1.1.6 的 `FunctionToolCallback.builder(name, biFunction)`
> 的泛型签名需写 PoC 验证。设计文档假设签名为
> `FunctionToolCallback.<I, O>builder(String name, BiFunction<I, ToolContext, O> fn)`。
> 若实际签名不同（如接受 `Function<I, O>` 而非 `BiFunction`），以下所有 build 方法
> 需要对应调整。在 Phase 2 开始前必须确认。

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

```java
/**
 * 闭包 ToolCallback 工厂
 *
 * 根据意图按需创建 FunctionToolCallback，闭包捕获 workspace 局部变量。
 * Tool 类本身不感知 workspace 传递机制（无全局状态，闭包捕获局部变量）。
 */
@Component
public class AgentToolCallbackFactory {

    private final HybridSearchTool hybridSearchTool;
    private final VectorSearchTool vectorSearchTool;
    private final RerankTool rerankTool;
    private final QueryRewriteTool queryRewriteTool;
    private final ParentDocLookupTool parentDocLookupTool;
    private final KnowledgeBaseInfoTool knowledgeBaseInfoTool;

    /**
     * 按意图创建对应 Tool 子集的 FunctionToolCallback 数组
     *
     * @param intent    意图分类结果
     * @param workspace 请求级 workspace（局部变量，闭包捕获）
     * @return 该意图需要的 ToolCallback 数组
     */
    public ToolCallback[] createToolCallbacks(AgentIntent intent, ToolWorkspace workspace) {
        return switch (intent) {
            case RETRIEVAL -> new ToolCallback[]{
                buildHybridSearch(workspace),
                buildRerank(workspace)
            };
            case DEEP_RETRIEVAL -> new ToolCallback[]{
                buildVectorSearch(workspace),
                buildHybridSearch(workspace),
                buildRerank(workspace),
                buildQueryRewrite(workspace),
                buildParentDocLookup(workspace)
            };
            case GENERAL_TOOL -> buildGeneralToolSet();
            case DIRECT_ANSWER -> new ToolCallback[]{}; // 无 Tool
        };
    }

    private ToolCallback buildHybridSearch(ToolWorkspace workspace) {
        return FunctionToolCallback.<HybridSearchRequest, String>builder(
                "hybridSearch",
                (request, ctx) -> hybridSearchTool.execute(request, workspace)
            )
            .description("混合检索：结合向量语义搜索和 BM25 关键词搜索，通过 RRF 融合排序")
            .inputType(HybridSearchRequest.class)
            .build();
    }

    private ToolCallback buildVectorSearch(ToolWorkspace workspace) {
        return FunctionToolCallback.<VectorSearchRequest, String>builder(
                "vectorSearch",
                (request, ctx) -> vectorSearchTool.execute(request, workspace)
            )
            .description("纯向量语义检索，适用于概念性查询")
            .inputType(VectorSearchRequest.class)
            .build();
    }

    private ToolCallback buildRerank(ToolWorkspace workspace) {
        return FunctionToolCallback.<RerankRequest, String>builder(
                "rerank",
                (request, ctx) -> rerankTool.execute(request, workspace)
            )
            .description("对已检索文档进行语义精排，基于百炼 Rerank API")
            .inputType(RerankRequest.class)
            .build();
    }

    private ToolCallback buildQueryRewrite(ToolWorkspace workspace) {
        return FunctionToolCallback.<QueryRewriteRequest, String>builder(
                "queryRewrite",
                (request, ctx) -> queryRewriteTool.execute(request, workspace)
            )
            .description("改写查询以提升检索效果，支持多角度改写生成多个变体")
            .inputType(QueryRewriteRequest.class)
            .build();
    }

    private ToolCallback buildParentDocLookup(ToolWorkspace workspace) {
        return FunctionToolCallback.<Void, String>builder(
                "parentDocLookup",
                (request, ctx) -> parentDocLookupTool.execute(workspace)
            )
            .description("将检索到的文档片段替换为其所属的完整父文档")
            .inputType(Void.class)
            .build();
    }

    private ToolCallback buildKnowledgeBaseInfo(ToolWorkspace workspace) {
        return FunctionToolCallback.<Void, String>builder(
                "knowledgeBaseInfo",
                (request, ctx) -> knowledgeBaseInfoTool.execute(workspace)
            )
            .description("查询当前知识库的元信息，包括文档数量、分块数量等")
            .inputType(Void.class)
            .build();
    }
}
```

#### 闭包方案 vs ThreadLocal 方案对比

| 维度 | ThreadLocal（已否决） | 闭包捕获（当前方案） |
|------|----------------------|---------------------|
| 状态管理 | 全局 ThreadLocal，需手动 set/get/clear | 局部变量，闭包引用，GC 回收 |
| 异常安全 | finally 块可能漏清理（异步/异常路径） | 无清理需求，无泄漏风险 |
| Tool 签名 | Tool 需调用 `ToolWorkspaceHolder.get()` | workspace 作为参数显式传入 |
| 可测试性 | 需 mock ThreadLocal | 直接传入 workspace 对象 |
| Spring AI API | 依赖 @Tool 注解 + 内部 resolver | FunctionToolCallback 闭包，完全控制 |

#### returnDirect 优化

Spring AI `ToolCallAdvisor` 支持 `returnDirect` 特性：当 Tool 的 `ToolMetadata` 设置 `returnDirect=true` 时，Tool 结果直接返回给客户端，不再回传 LLM。适用于不需要 LLM 加工的查询类 Tool：

```java
// knowledgeBaseInfo — 统计信息直接返回，无需 LLM 再加工
private ToolCallback buildKnowledgeBaseInfo(ToolWorkspace workspace) {
    return FunctionToolCallback.<Void, String>builder(
            "knowledgeBaseInfo",
            (request, ctx) -> knowledgeBaseInfoTool.execute(workspace)
        )
        .description("查询当前知识库的元信息，包括文档数量、分块数量等")
        .inputType(Void.class)
        .toolMetadata(ToolMetadata.builder().returnDirect(true).build())
        .build();
}
```

> **注意**：`returnDirect` 跳过 LLM，直接将 Tool 结果作为 `ChatResponse` 返回。只适用于 `DIRECT_ANSWER` 意图下的简单查询场景。`RETRIEVAL` / `DEEP_RETRIEVAL` 意图下的检索 Tool 不应设置 `returnDirect`，因为检索结果需要 LLM 综合生成回答。

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
| **容错与安全** | | | |
| 32 | `ToolResult.java` | 新增 | Tool 调用统一结果 record（success/failure + errorCategory） |
| 33 | `AgentGuardrails.java` | 新增 | ReAct 循环护栏（迭代总数/token 消耗/连续 Tool 三指标） |
| 34 | `AgentDegradationStrategy.java` | 新增 | Agent 全局降级策略（降级到 MULTI_TURN） |
| 35 | `AgentTrace.java` | 新增 | Agent 执行追踪记录 |
| 36 | `ToolCallRecord.java` | 新增 | 单次 Tool 调用记录 |

### 4.2 ChatAdvisorChainFactory 改动

```java
// ChatAdvisorChainFactory AGENT 分支
public List<Advisor> buildAgentChain(String conversationId,
                                    ChatRequest request,
                                    ChatModeStrategy modeStrategy) {
    List<Advisor> chain = new ArrayList<>();

    // 1. 上下文注入（与 buildChain 一致）
    if (modeStrategy.isContextEnabled()) {
        chain.add(new ConversationContextAdvisor(conversationId));
    }

    // 2. 全局 Advisor（限流、内容过滤）
    chain.addAll(getGlobalAdvisors());

    // 3. 意图识别（阻塞式 LLM 调用）
    Long userId = SecurityUtils.getCurrentUserId();
    Long teamId = request.teamId();
    IntentResult intent = intentClassifier.classify(request.message());

    // 3. 创建 Workspace（局部变量，不进 ThreadLocal）
    ToolWorkspace workspace = workspaceFactory.create(userId, teamId);
    workspace.setIntent(intent.intent());
    if (intent.hasSubQueries()) {
        workspace.setSubQueries(intent.subQueries());
    }

    // 4. 闭包创建 FunctionToolCallback（捕获 workspace 局部变量）
    ToolCallback[] agentTools = agentToolCallbackFactory
        .createToolCallbacks(intent.intent(), workspace);

    // 5. 创建 ToolCallAdvisor（ToolCallback 通过 resolver 传入）
    //    disableMemory() 等价于 conversationHistoryEnabled=false，
    //    由 MessageChatMemoryAdvisor(order=3) 统一管理对话历史，避免重复
    ToolCallbackResolver resolver = new StaticToolCallbackResolver(List.of(agentTools));
    ToolCallingManager agentToolManager = DefaultToolCallingManager.builder()
        .toolCallbackResolver(resolver)
        .build();
    chain.add(ToolCallAdvisor.builder()
        .toolCallingManager(agentToolManager)
        .advisorOrder(2)
        .disableMemory()
        .build());

    // 6. 动态 System Prompt（Agent Prompt + CAG 上下文合并）
    String agentPrompt = resolvePrompt(intent.intent());
    String cagContext = buildCagContext(ctx, request);
    String mergedPrompt = agentPrompt;
    if (cagContext != null && !cagContext.isBlank()) {
        mergedPrompt += "\n\n## 当前用户上下文\n" + cagContext;
    }
    chain.add(new AgentSystemPromptAdvisor(intent.intent(), mergedPrompt));

    // 7. Memory
    chain.add(MessageChatMemoryAdvisor.builder(chatMemory).build());

    // workspace 是局部变量，闭包引用它，请求结束 GC 回收
    return chain;
}
```

> **关键变更**：
> - ❌ 删除 `ToolWorkspaceHolder.set(workspace)` — 不再使用 ThreadLocal
> - ❌ 删除 `AgentContextCleanupAdvisor` — 无全局状态需要清理
> - ✅ `agentToolCallbackFactory.createToolCallbacks(intent, workspace)` — 闭包捕获
> - ✅ `StaticToolCallbackResolver` 包装闭包 callbacks，注入到独立的 `DefaultToolCallingManager`
> - ✅ 每次 Agent 请求自建 `ToolCallAdvisor`，不复用全局单例
> - ✅ CAG 上下文在 buildAgentChain 中合并到 Agent Prompt，`AgentSystemPromptAdvisor` 只负责注入

### 4.3 AgentSystemPromptAdvisor — 动态 System Prompt

根据意图识别结果注入不同的 System Prompt。构造时接收**已合并的最终 Prompt 字符串**（Agent Prompt + CAG 上下文），不再关心 CAG 合并逻辑（由调用方 `buildAgentChain` 负责）。

```java
import org.jspecify.annotations.NonNull;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.messages.SystemMessage;

import java.util.ArrayList;
import java.util.List;

/**
 * 根据意图动态注入 System Prompt
 *
 * 职责单一：将预构建的 mergedSystemPrompt 注入到 ChatClientRequest。
 * Prompt 内容由调用方在 buildAgentChain() 中组装
 * （Agent Prompt + CAG 上下文 + Workspace 状态摘要）。
 *
 * 实现 Spring AI 1.1.6 的 BaseAdvisor 接口，
 * 使用 before() 拦截请求并注入 SystemMessage。
 * conversationHistoryEnabled 由 ToolCallAdvisor 管理，
 * 本 Advisor 不涉及对话历史。
 *
 * API 说明：Spring AI 1.1.6 Advisor 体系为 BaseAdvisor
 * （before/after 模式），不是旧版的 CallAroundAdvisor
 * （aroundCall/AdvisedRequest 模式）。
 */
public class AgentSystemPromptAdvisor implements BaseAdvisor {

    private final AgentIntent intent;
    private final String mergedSystemPrompt;

    /**
     * @param intent             意图分类结果（用于日志和条件判断）
     * @param mergedSystemPrompt 已合并的最终 Prompt（Agent + CAG + Workspace 状态）
     */
    public AgentSystemPromptAdvisor(AgentIntent intent, String mergedSystemPrompt) {
        this.intent = intent;
        this.mergedSystemPrompt = mergedSystemPrompt;
    }

    @Override
    @NonNull
    public String getName() {
        return "AgentSystemPromptAdvisor";
    }

    @Override
    public int getOrder() {
        return 1; // 在 ToolCallAdvisor(order=2) 之前执行
    }

    @Override
    @NonNull
    public ChatClientRequest before(@NonNull ChatClientRequest request, @NonNull AdvisorChain chain) {
        // 将 mergedSystemPrompt 作为 SystemMessage 注入到 prompt instructions 首位
        SystemMessage systemMessage = new SystemMessage(mergedSystemPrompt);
        List<org.springframework.ai.chat.messages.Message> instructions =
            new ArrayList<>(request.prompt().getInstructions());
        instructions.add(0, systemMessage);

        return ChatClientRequest.builder()
            .prompt(request.prompt().mutate()
                .instructions(instructions)
                .build())
            .context(request.context())
            .build();
    }

    @Override
    @NonNull
    public ChatClientResponse after(@NonNull ChatClientResponse response, @NonNull AdvisorChain chain) {
        return response; // 不修改响应
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

**三种模式下的 CAG 行为**：

| 模式 | CAG 来源 | 注入方式 |
|------|----------|----------|
| SIMPLE | `ContextPromptInjector` | 直接注入 ChatClient system prompt |
| MULTI_TURN | `ContextPromptInjector` | 直接注入 ChatClient system prompt |
| AGENT | `ContextPromptInjector` → `AgentSystemPromptAdvisor` | 合并到 Agent 动态 System Prompt |

**代码层面**（在 `ChatAdvisorChainFactory` AGENT 分支中）：

```java
// 复用现有 CAG 构建逻辑
String cagContext = buildCagContext(ctx, request);

// Agent 自身的 Prompt（含原子决策引导 + 自省格式 + 检索代价意识）
String agentPrompt = resolvePrompt(intent);

// 合并注入
String mergedPrompt = agentPrompt;
if (cagContext != null && !cagContext.isBlank()) {
    mergedPrompt += "\n\n## 当前用户上下文\n" + cagContext;
}
chain.add(new AgentSystemPromptAdvisor(intent.intent(), mergedPrompt));
```

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

逐项审查现有代码后，识别出 6 个真实存在的集成冲突点。

#### P1: `ChatRequest.mode` 校验正则硬编码

**现状**：
```java
// ChatRequest.java
@Pattern(regexp = "^(SIMPLE|MULTI_TURN)$",
         message = "对话模式仅支持 SIMPLE 或 MULTI_TURN")
String mode,
```

**问题**：传入 `mode=AGENT` 会被 Bean Validation 直接拒绝，请求无法到达 Controller。

**修复**：
```java
@Pattern(regexp = "^(SIMPLE|MULTI_TURN|AGENT)$",
         message = "对话模式仅支持 SIMPLE、MULTI_TURN 或 AGENT")
String mode,
```

**改动文件**：`ChatRequest.java`（1 行）

---

#### P2: `ToolCallAdvisor` 全局单例 — Agent 需按意图动态创建

**现状**：
```java
// ToolAutoConfiguration.java — 全局唯一 Bean
@Bean
public ToolCallAdvisor toolCallAdvisor(ToolCallingManager toolCallingManager) {
    return ToolCallAdvisor.builder()
            .toolCallingManager(toolCallingManager)  // 包含 CalculatorTools、DateTimeTools 等 ALL 工具
            .disableMemory()
            .advisorOrder(2)
            .build();
}

// ChatAdvisorChainFactory.java — 直接拿全局单例
if (hasTools()) {
    chain.add(toolCallAdvisorProvider.getObject());
}
```

**问题**：
- 全局 `ToolCallAdvisor` 的 `ToolCallingManager` 包含所有通用工具（Calculator、DateTime 等）
- Agent 模式需要**只暴露 RAG Tool 子集**（按意图过滤），且每次请求子集不同
- 这是**最核心的架构冲突**

**修复**：Agent 分支自建 `ToolCallAdvisor`，通过闭包工厂创建 ToolCallback，不复用全局 Bean
```java
// ChatAdvisorChainFactory.java AGENT 分支
public List<Advisor> buildAgentChain(String conversationId,
                                    ChatRequest request,
                                    ChatModeStrategy modeStrategy) {
    List<Advisor> chain = new ArrayList<>();

    // 1. 上下文注入（与 buildChain 一致）
    if (modeStrategy.isContextEnabled()) {
        chain.add(new ConversationContextAdvisor(conversationId));
    }

    // 2. 全局 Advisor（RateLimit、ContentFilter 等）
    chain.addAll(getGlobalAdvisors());

    // 3. 意图识别
    Long userId = SecurityUtils.getCurrentUserId();
    Long teamId = request.teamId();
    IntentResult intent = intentClassifier.classify(request.message());

    // 3. 创建 Workspace（局部变量）
    ToolWorkspace workspace = workspaceFactory.create(userId, teamId);
    workspace.setIntent(intent.intent());
    if (intent.hasSubQueries()) {
        workspace.setSubQueries(intent.subQueries());
    }

    // 4. 闭包创建 FunctionToolCallback（捕获 workspace）
    ToolCallback[] agentTools = agentToolCallbackFactory
        .createToolCallbacks(intent.intent(), workspace);

    // 5. Agent 专用 ToolCallAdvisor（闭包 ToolCallback 通过 resolver 传入）
    //    disableMemory() 等价于 conversationHistoryEnabled=false，
    //    由 MessageChatMemoryAdvisor 统一管理对话历史
    ToolCallingManager agentToolManager = DefaultToolCallingManager.builder()
            .toolCallbackResolver(new StaticToolCallbackResolver(List.of(agentTools)))
            .build();
    chain.add(ToolCallAdvisor.builder()
            .toolCallingManager(agentToolManager)
            .disableMemory()
            .advisorOrder(2)
            .build());

    // 6. 动态 System Prompt（Agent Prompt + CAG 上下文合并）
    String agentPrompt = resolvePrompt(intent.intent());
    String cagContext = buildCagContext(ctx, request);
    String mergedPrompt = agentPrompt;
    if (cagContext != null && !cagContext.isBlank()) {
        mergedPrompt += "\n\n## 当前用户上下文\n" + cagContext;
    }
    chain.add(new AgentSystemPromptAdvisor(intent.intent(), mergedPrompt));

    // 7. Memory
    chain.add(MessageChatMemoryAdvisor.builder(chatMemory).build());

    // workspace 是局部变量，闭包引用它，请求结束 GC 回收
    return chain;
}
```

> **核心区别**：每次请求动态创建 `FunctionToolCallback` 闭包捕获 `workspace` 局部变量，
> 通过 `StaticToolCallbackResolver` → `DefaultToolCallingManager` → `ToolCallAdvisor` 完整传递链，
> 不依赖 ThreadLocal，不需要 cleanup Advisor。

**改动文件**：`ChatAdvisorChainFactory.java`（新增方法，不改现有 `buildChain`）

---

#### P3: `ChatRequestSpecFactory` 全量 Tool 绑定

**现状**：
```java
// ChatRequestSpecFactory.createSpec()
if (advisorChainFactory.hasTools()) {
    spec = spec.tools((Object) advisorChainFactory.getToolCallbacks());  // ALL 工具全量绑定
}
```

**问题**：Agent 模式的 Tool 已由自建 `ToolCallAdvisor` 管理，不能再通过 `spec.tools()` 全量挂载，否则会重复注册。

**修复**：Agent 分支跳过 `spec.tools()` 调用
```java
// ChatRequestSpecFactory.createSpec() 增加判断
if (advisorChainFactory.hasTools() && !modeStrategy.isAgentMode()) {
    spec = spec.tools((Object) advisorChainFactory.getToolCallbacks());
}
```

**改动文件**：`ChatRequestSpecFactory.java`（1 行条件判断）

---

#### P4: System Prompt 固定从 DB 查询 — Agent 需动态生成

**现状**：
```java
// ChatRequestSpecFactory.createSpec()
String systemPrompt = resolveSystemPrompt(route);  // 按 modelId 查 DB 固定模板
systemPrompt = contextPromptInjector.inject(systemPrompt, cagContext);
spec = spec.system(systemPrompt);
```

**问题**：Agent 的 System Prompt 是**运行时动态生成**的，包含：
- 原子决策引导（retrieve/parametric）
- 自省输出格式（is_relevant/is_sufficient/next_action）
- 检索代价意识规则
- 当前意图信息 + 可用 Tool 列表
- CAG 上下文

不能从 DB 查固定模板。

**修复**：Agent 分支由 `AgentSystemPromptAdvisor` 接管 System Prompt
```java
// ChatRequestSpecFactory.createSpec() 增加判断
if (modeStrategy.isAgentMode()) {
    // Agent 模式：跳过 DB System Prompt，由 AgentSystemPromptAdvisor 动态注入
    // 跳过 DB ModelParams（temperature 等），Agent 模型的参数由 AgentRagProperties 控制
    // CAG 上下文传递给 Agent 编排层处理
} else {
    String systemPrompt = resolveSystemPrompt(route);
    systemPrompt = contextPromptInjector.inject(systemPrompt, cagContext);
    if (systemPrompt != null && !systemPrompt.isBlank()) {
        spec = spec.system(systemPrompt);
    }
    // 模型参数
    ChatOptions options = resolveChatOptions(route);
    if (options != null) {
        spec = spec.options(options);
    }
}
```

**设计决策**：Agent 模式下 `temperature`、`topP` 等参数由 `AgentRagProperties` 统一控制，
不从 DB `ModelParams` 读取。原因是 Agent ReAct 循环需要稳定的模型行为
（如 temperature 偏低以减少幻觉），不应被 DB 配置意外覆盖。

**改动文件**：`ChatRequestSpecFactory.java`（if-else 分支）

---

#### P5: `ChatModeStrategy` 接口能力不足

**现状**：
```java
public interface ChatModeStrategy {
    ChatMode getMode();
    boolean isMemoryEnabled();
    boolean isContextEnabled();
    boolean isThinkingEnabled();
}
```

**问题**：四个 boolean 方法无法表达 Agent 模式的本质差异：
- Agent 需要自建 ToolCallAdvisor（不共享全局的）
- Agent 需要跳过固定 System Prompt
- Agent 需要独立编排层（IntentClassifier → ReAct Loop）

**修复**：扩展接口，使用 default 方法保持向后兼容
```java
public interface ChatModeStrategy {
    ChatMode getMode();
    boolean isMemoryEnabled();
    boolean isContextEnabled();
    boolean isThinkingEnabled();

n    /**
     * 是否为 Agent 模式（需要动态 Tool 绑定 + 动态 System Prompt）
     * default false 保证 SIMPLE / MULTI_TURN 无需改动
     */
    default boolean isAgentMode() {
        return false;
    }
}
```

`AgentModeStrategy` 覆写 `isAgentMode()` 返回 `true`，其他策略不受影响。

**改动文件**：`ChatModeStrategy.java`（新增 default 方法）、新增 `AgentModeStrategy.java`

---

#### P6: 响应结构缺少 Agent 元数据

**现状**：`ChatServiceImpl` 返回 `ChatResponse`（Spring AI 标准），包含 content + metadata。

**问题**：Agent 模式的响应需要携带额外元数据：
- `AgentTrace`：轮次、各 Tool 调用记录、耗时
- `SelfReflection`：质量自评（is_supported/is_useful）
- 检索文档引用列表（供前端展示）
- 意图识别结果（用于调试）

**修复**：利用 Spring AI `ChatResponse.metadata` 扩展，不破坏现有结构
```java
// Agent 模式响应构建
if (modeStrategy.isAgentMode()) {
    ChatResponse response = spec.chatResponse();
    // 将 Agent 元数据注入 metadata
    Map<String, Object> agentMeta = Map.of(
        "agentTrace", workspace.exportTrace(),
        "agentIntent", intent.name(),
        "retrievedDocs", workspace.getRetrievedDocuments(),
        "selfEvaluation", workspace.getSelfEvaluation()
    );
    // 返回包装后的响应
    return new AgentChatResponse(response, agentMeta);
}
```

新增 `AgentChatResponse` record 包装标准 `ChatResponse` + Agent 元数据。Controller 层按 mode 分支序列化。

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

            ToolWorkspace ws = workspace; // 闭包捕获的 workspace 局部变量
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
 * 指标 3：同一 Tool 连续调用次数（软干预）
 *   - 跟踪每个 Tool 的连续调用次数，超过阈值时注入提醒信息给 LLM
 *   - 默认阈值：3 次
 *   - 不跳出循环，而是告知 LLM 当前状态，让 LLM 自主决策：
 *     信息是否足够？还缺少哪些部分？切换 Tool 能否解决？
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

        // 指标 3：同一 Tool 连续调用检测（软干预）
        if (currentTool != null && currentTool.equals(lastToolName)) {
            consecutiveCount++;
        } else {
            lastToolName = currentTool;
            consecutiveCount = 1;
        }
        if (consecutiveCount > properties.maxConsecutiveSameTool()) {
            // 不跳出循环，返回警告信息让 LLM 自主决策
            return GuardrailCheck.warn(
                "CONSECUTIVE_TOOL",
                "注意：工具 [%s] 已连续调用 %d 次。请评估：当前已收集的信息是否足够回答用户问题？"
                    + "如果不够，还缺少哪些部分？尝试切换其他工具（如 bm25Search、vectorSearch、hybridSearch）"
                    + "是否能获得更好的结果？如果信息已充分，请直接生成最终回答。"
                    .formatted(currentTool, consecutiveCount));
        }

        return GuardrailCheck.ok();
    }

    public int getConsecutiveCount() { return consecutiveCount; }

    public record GuardrailCheck(
        boolean allowed,
        @Nullable String stopReason,   // ITERATION_LIMIT | TOKEN_LIMIT | CONSECUTIVE_TOOL
        @Nullable String message,
        boolean shouldWarn              // true = 软干预（注入提醒但不跳出循环）
    ) {
        static GuardrailCheck ok() {
            return new GuardrailCheck(true, null, null, false);
        }
        static GuardrailCheck stop(String reason, String message) {
            return new GuardrailCheck(false, reason, message, false);
        }
        static GuardrailCheck warn(String reason, String message) {
            return new GuardrailCheck(true, reason, message, true);
        }
    }
}
```

**三指标说明**：

| 指标 | 计算方式 | 默认上限 | 防御目标 |
|------|----------|----------|----------|
| 循环迭代总次数 | 整个 ReAct 循环，每轮 = LLM 决策 → Tool 执行 → 结果回传 | 10 | 防止无限循环 |
| 累计 Token 消耗 | 意图识别 + 所有 ReAct 轮次的 LLM token | 模型上下文窗口 × 80% | 防止 token 爆炸 |
| 同一 Tool 连续调用 | 同一 Tool 连续调用次数（切换 Tool 会重置） | 3 | 软干预：告知 LLM 评估信息是否充足，建议切换 Tool 或直接回答 |

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

> **⚠️ 可行性验证**：Spring AI 1.1.6 的 `ToolCallAdvisor` 在 ReAct 循环中，
> 每轮 Tool 调用后的中间 `ChatResponse` 是否暴露 `usage` 元数据需要验证。
> 如果 `ToolCallAdvisor` 内部吞掉了中间响应的 usage，
> 则 token 计数指标需改为基于输入文本估算（input tokens ≈ 字符数 / 4），
> 而非精确计数。在 Phase 5 前写 PoC 验证。

**护栏触发后的行为**：

```java
// 在 ToolCallAdvisor 每轮回调中检查
GuardrailCheck check = guardrails.check(iteration, tokensUsed, tokenLimit, currentTool);

if (check.shouldWarn()) {
    // 软干预：不跳出循环，将提醒信息注入下一轮 LLM 的 prompt
    // LLM 收到后会自主评估：信息够不够？要不要换 Tool？要不要直接回答？
    log.info("Agent guardrail warning: reason={}, tool={}, consecutive={}",
        check.stopReason(), currentTool, guardrails.getConsecutiveCount());
    // 将 check.message() 注入到下一轮 LLM 调用的 user message 中
    workspace.setPendingWarning(check.message());
    // 继续循环
}

if (!check.allowed()) {
    // 硬中断：迭代超限或 token 超限，立即跳出循环
    log.warn("Agent guardrail stop: reason={}, iteration={}, tokens={}/{}, workspace docs={}",
        check.stopReason(), iteration, tokensUsed, tokenLimit,
        workspace.getRetrievedDocs().size());
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
| F6: 同一 Tool 死循环 | 连续调用检测 → 软干预（告知 LLM 评估信息是否充足，建议切换 Tool） | LLM 自主决策切换 Tool 或直接回答 | 可能略慢但最终收敛 |
| F7: Workspace 损坏 | JsonProcessingException | 重建空 Workspace 继续或降级 | MULTI_TURN 模式 | 回退到 Pipeline RAG |
| F8: Workspace 不可用 | 闭包方案不会发生 | workspace 作为闭包局部变量，随请求创建和回收 | 无（设计消除） |
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
6. **前置 PoC**：验证 `FunctionToolCallback.builder()` 泛型签名与本文档假设一致

### Phase 3: RAG Tool 实现（4-6h）

1. `RagTool` 标记接口
2. `ToolResult` 统一返回格式（success/failure + errorCategory）
3. 7 个 RAG Tool（每个含异常捕获 + ToolResult + Workspace 操作）
4. 每个 Tool 单元测试

### Phase 4: ReAct 循环增强（3-4h）— 论文驱动

1. `AgentSystemPromptAdvisor`：动态 System Prompt（实现 `BaseAdvisor` 接口，非旧版 `CallAroundAdvisor`；含原子决策引导+检索代价意识+自省格式）
2. System Prompt 定义：
   - 原子决策引导（DeepRAG）：对每个子问题先输出 retrieve/parametric 决策
   - 自省评估格式（Self-RAG）：is_relevant/is_sufficient/next_action
   - 中间答案格式（DeepRAG）：每个子问题生成中间答案
   - 检索代价规则（DeepRAG）：优先使用已有知识、引用中间答案
   - 回答质量自评（Self-RAG）：is_supported/is_useful/citations
3. Workspace 中间答案注入机制：后续子问题 prompt 自动包含前面的中间答案
4. 增强逻辑单元测试

### Phase 5: 护栏 + 容错（2-3h）

1. `AgentGuardrails`（三指标：迭代总数/token 消耗/连续 Tool）
2. **前置 PoC**：验证 `ToolCallAdvisor` ReAct 循环中每轮中间 `ChatResponse.metadata().usage()` 是否可获取，
   如果不可用则 token 计数改为基于输入文本估算（input tokens ≈ 字符数 / 4）
3. `AgentDegradationStrategy`（全局降级）
4. `AgentTrace` + `ToolCallRecord`（可观测性）
5. 容错逻辑单元测试

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
| Q5 | 意图识别是否包含查询分解？ | **是 — 意图分类+查询分解合并为单次 LLM 调用** | 减少延迟；子问题写入 Workspace 供 Agent 按子问题逐一检索 |
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
