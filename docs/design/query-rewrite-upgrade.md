# 查询改写升级设计（脱语境三策略：富化 / 回溯提示 / 分解）

> **目标**：将现有 history-blind 的检索词优化改写，升级为**历史感知的结构化改写子系统**，实现三种策略——**富化**（脱语境 + 补全）、**回溯提示**（Step-Back Prompting）、**分解**（子问题拆解）——由**单次 LLM 调用统一编排**产出，服务于检索质量与语义缓存两个消费方。
>
> **定位**：本设计是 [LLM 语义缓存设计](./llm-semantic-cache.md) 的**前置条件**——语义缓存以**富化产物**为缓存键（有历史路径），富化策略先行落地（R1）后语义缓存 P2 才能启用多轮/Agent 缓存；语义缓存 P1（无历史 exact）不依赖本设计。
>
> **核心决策**：
> 1. **单次调用统一编排**：三策略产物 + 决策标志在一次结构化改写调用中产出（JSON 结构化输出），延迟与现状持平（1 次调用），相比分别调用省 2 次。
> 2. **富化是唯一缓存键来源**：step-back 与分解产物仅用于检索融合，不参与缓存键。
> 3. **检索融合起步用等权 RRF + rerank 兜底**：项目已有 RRF 经验（hybrid 内 `rrf-k=60`），加权融合待评测数据后再引入。
> 4. **分解仅限"相互独立的子问题"**（并行检索）；依赖式多跳分解仍是 Agent ReAct 的领域，本设计不做。
> 5. **向后兼容与降级**：结构化解析失败 → 降级为现有单字符串改写路径（`QUERY_REWRITE_TEMPLATE` 保留）；无历史的简单查询行为不劣化。
>
> **范围**：chat 检索路径（`ChatRetrievalService` → `RagAdvisorFactory`）的查询改写与多查询融合。**不含**：Agent 内部 ReAct 编排（Agent 的 `QueryRewriteTool` 初期沿用现有模板，R3 后可选升级）、两段式 step-back 生成（本设计仅检索增强，不做"先答抽象问题再答原问题"的两段生成）。
>
> **状态**：设计已定稿，待立项实施（R1–R3 分期见 §8）。

---

## 1. 背景与现状

### 1.1 现状：history-blind 的单字符串改写（已核验代码事实）

| 事实 | 位置 |
|---|---|
| 改写模板仅接收 `{query}`（与 `{target}`），**无历史输入**；规则为去填充词/术语展开/≤30 词/已自包含则原样返回 | `QueryRewritePromptTemplates.java:19` |
| 改写器为 Spring AI `RewriteQueryTransformer`，模型与温度可配 | `RagConfig.java:32-52` |
| 配置为平铺三键：`query-rewrite-enabled`（dev 为 true）/ `query-rewrite-model` / `query-rewrite-temperature` | `RagRetrievalProperties.java:14,28,30` |
| 触发点在检索内部：`RagAdvisorFactory.retrieve()` 每次检索前改写（位于语义缓存点之后） | `RagAdvisorFactory.retrieve()` |
| 检索为单查询入口，内部多路径已并行 | `HybridSearchService.hybridSearch(queryText, userId, teamId)` |
| 后处理器链（rerank/MMR/parent）以单一 `Query` 运行 | `RerankThenMmrPostProcessor.process(Query, List<Document>)` |

**后果**：① 多轮对话中含指代的原句（"它多少钱"）直接进检索，召回质量受拖累；② 语义缓存无法安全以原句为键（同句异境），这是 `llm-semantic-cache.md` 的"前提缺口"。

### 1.2 三策略定义（设计输入）

| 策略 | 定义 | 产物 | 检索用法 | 缓存角色 |
|---|---|---|---|---|
| **富化**（Enrichment） | 在原始查询中添加上下文信息、背景知识或必要的限制条件，以消除歧义、补充缺失的信息，使查询更完整、更具体 | 单个自包含规范问题（"它多少钱"+iPhone 语境 → "iPhone 17 官方售价"） | **主检索查询** + rerank 上下文 | **唯一缓存键来源** |
| **回溯提示**（Step-Back Prompting） | 先引导模型"后退一步"，从具体问题中抽象出更一般的原理、概念或背景知识；再基于这些抽象信息进行推理或检索，最终回答原始问题 | 单个抽象层问题（"2024Q3 华东区销售冠军是谁" → "公司各区域季度销售排名结果"） | 并行辅助检索，与主查询结果融合 | 不参与键；存入 payload 备审计 |
| **分解**（Decomposition） | 将复杂、多步骤或包含多个子问题的查询，拆解成若干个**相互独立**、更简单、更具体的子问题；每个子问题独立检索，多个检索结果合并用于回答原问题 | 子问题列表（≤3）（"A 和 B 的区别及各自价格" → ["A 与 B 的核心差异", "A 的价格", "B 的价格"]） | 各子问题并行检索，融合去重 | 不参与键；整体答案仍以富化产物为键 |

### 1.3 参照系

- **Step-Back**：Google DeepMind《Take a Step Back: Evoking Reasoning via Abstraction in Large Language Models》（2023）——原论文含两段式生成（先答抽象问题再答原问题）；本设计**只取检索增强部分**（抽象问题作为补充召回通道），生成端仍由主答案一次完成，避免多一次生成调用。
- **富化** ≈ LangChain CondenseQuestion / Spring AI `CompressionQueryTransformer`（历史压缩为独立问题）的概念，与现有检索词优化（`RewriteQueryTransformer` 职责）**合并为一张模板**。
- **分解** ≈ 多查询扩展（Spring AI `ExpansionQueryTransformer` / LangChain MultiQueryRetriever）与子问题分解（IRCoT 一系）的交集版：仅拆"可并行独立"的子问题。

---

## 2. 设计目标与非目标

### 2.1 目标

| # | 目标 | 度量 |
|---|---|---|
| G1 | 三策略产物由**单次** LLM 调用产出，改写延迟与现状持平 | 调用数 = 1；耗时面板 |
| G2 | 富化指代消解正确率 ≥ 95%（多轮缓存正确性的上限） | 人工标注评测集（§7） |
| G3 | 检索召回增益：细节型问题（step-back）与多子问题（分解）召回/答案质量可测提升 | recall@20 / ragas 指标（§7） |
| G4 | 向后兼容：无历史简单查询的改写行为不劣化；结构化解析失败可降级 | 回归 + 等价率评测 |
| G5 | 任一策略失败不影响检索主链路 | 降级测试 |

### 2.2 非目标

- **依赖式多跳分解**（第二跳依赖第一跳答案，如"X 的作者还写过什么"）——Agent ReAct 已覆盖，chat 路径不引入串行多轮。
- **两段式 step-back 生成**（先答抽象问题再答原问题）——仅用抽象问题做补充检索。
- **子问题级缓存**（每个子答案单独缓存复用）——语义缓存 P3 可选项，另行评估。
- Agent 模式内部编排的改造（`QueryRewriteTool` 初期沿用现有模板）。

---

## 3. 统一编排：单次结构化改写调用

### 3.1 输出契约

```java
/** 统一改写产物：三策略一次调用产出，随请求传递复用。 */
public record RewriteResult(
        String enrichedQuery,        // 必有：富化产物 = 缓存键 + 主检索查询 + 后处理器 Query
        boolean needsStepBack,       // 决策：是否需要回溯
        @Nullable String stepBackQuery,
        boolean needsDecomposition,  // 决策：是否需要分解
        List<String> subQueries      // needsDecomposition 时，≤ max-sub-queries
) {}
```

JSON 结构化输出（Spring AI `BeanOutputConverter` 或 tool-call JSON 模式，依项目 `GenericChatClient` 能力实现时定）。**解析失败/超时 → 降级**为现有单字符串改写路径（`QUERY_REWRITE_TEMPLATE` 保留不删），主链路不受影响。

### 3.2 决策规则（模板内引导，不做硬编码路由）

| 场景特征 | 决策 |
|---|---|
| 已自包含、单一意图（无历史或独立完整问题） | `enrichedQuery` ≈ 原句（继承现有模板规则 6），flags 全 false |
| 含指代（它/这个/上面说的）、省略（"那退款呢"）、语境约束（时间/版本/范围） | 富化生效：消解、补全、继承约束 |
| 细节密集（具体案例/数字/日期/专有实例）且直接检索可能 miss 一般性文档 | `needsStepBack = true`，产出抽象层问题 |
| 多问号/对比连词/多实体多属性/并列子诉求 | `needsDecomposition = true`，产出 ≤3 个独立子问题 |
| 子问题间存在依赖（第二跳需要第一跳答案） | **拒绝分解**（模板规则明确），flags false——该场景归 Agent 模式 |

启发式预筛**不引入**（避免维护两套路由逻辑）；单次调用内由 LLM 决策，规则全部写入模板。

### 3.3 输入与模板

- 输入：当前问题 + **最近 K 轮历史**（默认 5，每条截断至 512 字符）+ 检索目标 `{target}` 占位（沿用）。
- 新模板 `UNIFIED_REWRITE_TEMPLATE` 落在 `QueryRewritePromptTemplates`（该文件 javadoc 已声明为聊天/Agent 共享模板处）。富化规则 = 现有规则 2–6 全继承 + 新增：指代消解用历史中的实体名；省略补全仅限历史可支撑的信息；约束继承（时间/版本/范围随语境带入）；**禁止添加历史与原问题之外的事实**（防幻觉硬规则）。
- 现有 `QUERY_REWRITE_TEMPLATE` 保留作为降级路径与 Agent `QueryRewriteTool` 的既有依赖。

### 3.4 模型与成本

- 复用 `query-rewrite-model` / `query-rewrite-temperature`（`RagRetrievalProperties` 现有三键），选 flash 级候选控制延迟。
- 调用数不变（1 次），token 略增（结构化输出 + 决策指令）；三策略若分别调用需 3 次，统一编排净省 2 次。
- 检索扇出成本见 §4.3。

---

## 4. 检索融合（step-back 与分解的消费侧）

### 4.1 多查询并行检索

`RagAdvisorFactory.retrieve()` 由"单查询"升级为"接收 `RewriteResult`"：

```
主查询（enrichedQuery）──┐
stepBackQuery（≤1）──────┼── 各自调 HybridSearchService.hybridSearch 并行执行
subQueries（≤3，去重后）─┘
        │
        ▼
   文档级去重（同文档 id 保留最高名次）
        ▼
   RRF 融合（等权起步，rrf-k 沿用 60）
        ▼
   现有后处理器链（Rerank → MMR → Parent），Query 统一用 enrichedQuery
        ▼
   ChatReferenceCollector 统一编号（不变）
```

### 4.2 融合策略

- **起步：等权 RRF**——项目已有 RRF 实现与参数经验，最少新概念；rerank（`qwen3-rerank`）在融合后重排，天然压制 step-back 泛化文档淹没精确结果的风险。
- **后续（评测数据支撑后）**：加权 RRF（主查询 ×1.0 > 子查询 ×0.9 > step-back ×0.7），`fusion.weighting: equal | weighted` 配置位预留。

### 4.3 成本控制（扇出上限）

| 控制 | 值 |
|---|---|
| step-back 查询数 | ≤ 1 |
| 子问题数 | ≤ 3（`max-sub-queries`），与主查询/彼此重复的去重后跳过 |
| 附加查询总数 | ≤ 4（即最坏 5 路 hybrid 并行，每路内部再分 vector/BM25 两路径） |
| 并发上限 | 复用现有并发设施限流，防止扇出打满检索线程池 |
| 策略开关 | `strategies.{enrichment,step-back,decomposition}` 各自可独立关闭 |

---

## 5. 与语义缓存的衔接

详见 [`llm-semantic-cache.md`](./llm-semantic-cache.md) §5.3，此处仅列契约：

1. **缓存键 = `enrichedQuery`**（有历史路径）；step-back / 分解产物不参与键，`stepBackQuery`/`subQueries` 可存入缓存 payload 备审计。
2. **改写前移与结果传递**：有历史路径的统一改写调用提升至语义缓存点之前（`ChatServiceImpl`）；miss 后 `RewriteResult` **整体**向下传递——主查询与附加查询全部复用，检索侧跳过内层改写，**不产生第二次改写调用**。
3. **policyVersion** 输入纳入 `UNIFIED_REWRITE_TEMPLATE` 版本（模板迭代 → 语义缓存键空间自动切换）。
4. **前置关系**：本设计 **R1（富化）是语义缓存 P2（有历史路径）的硬前置**；语义缓存 P1（无历史 exact，raw query 键）不依赖，可先行。

---

## 6. 工程落点

### 6.1 包结构

```text
src/main/java/com/smart/rag/rag/retrieval/rewrite/
├── RewriteResult.java               # 统一产物 record（§3.1）
├── UnifiedRewriteTransformer.java   # 统一编排调用（结构化输出 + 降级）
├── RewriteResultParser.java         # JSON 解析 + 失败降级为单字符串路径
├── MultiQueryRetrievalExecutor.java # 多查询并行检索 + 去重 + RRF 融合（§4）
└── dto/...

模板：QueryRewritePromptTemplates.UNIFIED_REWRITE_TEMPLATE（新增）
改造：RagAdvisorFactory.retrieve(RewriteResult, ...) 增加预改写入口（跳过内层改写）
      ChatRetrievalService → AbstractModeStrategy 两处调用点（阻塞 :101 / 流式 :168）同步
```

### 6.2 配置（`RagRetrievalProperties` 扩展，向后兼容）

```yaml
app:
  rag:
    query-rewrite-enabled: true        # 主开关（现有，保留）
    query-rewrite-model: ...           # 现有
    query-rewrite-temperature: ...     # 现有
    query-rewrite:                     # 新增嵌套段（与上面平铺键共存）
      history-rounds: 5                # 富化输入的历史轮数
      max-sub-queries: 3
      strategies:
        enrichment: true               # 富化（脱语境 + 补全）
        step-back: true                # 回溯提示
        decomposition: true            # 分解
      fusion:
        weighting: equal               # equal | weighted（加权待评测后启用）
```

### 6.3 指标

| 指标 | 类型 | 标签 |
|---|---|---|
| `query_rewrite_duration` | Timer | — |
| `query_rewrite_decisions_total` | Counter | `stepback=true\|false`、`decomposition=true\|false` |
| `query_rewrite_degraded_total` | Counter | `reason=parse_error\|timeout` |
| `query_rewrite_extra_queries` | Counter | 每次附加检索查询数分布 |
| `retrieval_fusion_docs` | DistributionSummary | 融合前后文档数（去重效果） |

---

## 7. 评测

复用 evaluation 模块（ragas 对齐），四组：

| 组 | 内容 | 指标 / 门槛 |
|---|---|---|
| **富化质量** | 含代词/省略的追问 + 历史 → 富化结果与人工标注自包含问题比对 | 指代消解正确率 **≥ 95%**（多轮缓存启用门槛）；幻觉率（添加历史外信息）≈ 0 |
| **Step-back 增益** | 细节密集型问题集，开关对比 | recall@20 提升 ≥ 10%（初步值，评测后校准） |
| **分解增益** | 多子问题/对比型问题集，开关对比 | ragas correctness / answer relevancy 提升可测 |
| **回归** | 无历史简单查询，新旧改写输出比对 | 等价率 ≥ 95%（行为不劣化） |

---

## 8. 分阶段实施计划

### R1：统一结构化改写 + 富化（核心，语义缓存前置）

| # | 任务 | 产出 |
|---|---|---|
| 1.1 | `RewriteResult` 契约 + `UNIFIED_REWRITE_TEMPLATE` + `UnifiedRewriteTransformer`（结构化输出 + 解析降级） | 单次调用产三策略产物 |
| 1.2 | 富化生效：主检索查询改用 `enrichedQuery`；`RagAdvisorFactory` 增加预改写入口 | 多轮检索质量修复即生效 |
| 1.3 | 富化质量评测组（§7） | ≥95% 门槛验证 |

**AC**：改写调用数恒为 1；解析失败降级可用（注入测试）；无历史回归等价率 ≥95%；富化指代消解 ≥95%；多轮检索（含指代追问）召回提升可测。

### R2：Step-back 检索融合

| # | 任务 | 产出 |
|---|---|---|
| 2.1 | `MultiQueryRetrievalExecutor`（并行 + 去重 + 等权 RRF） | stepBackQuery 接入融合 |
| 2.2 | 扇出上限与并发限流 | 成本控制 |
| 2.3 | Step-back 增益评测 | recall@20 对比报告 |

**AC**：细节型问题 recall@20 提升 ≥10%（或报告校准值）；附加查询 ≤1；开关关闭零影响。

### R3：分解并行检索融合

| # | 任务 | 产出 |
|---|---|---|
| 3.1 | subQueries 并行检索接入融合（≤3，去重） | 多子问题覆盖 |
| 3.2 | 依赖式问题拒绝分解的模板规则 + 测试 | 边界控制 |
| 3.3 | 分解增益评测 + 加权融合（如数据支持） | 报告 + `weighting` 切换 |

**AC**：多子问题集答案质量提升可测；依赖式问题不被错误拆分；附加查询 ≤4（合计）。

> 语义缓存联动：其 P1 可与本设计 R1 并行启动；其 P2（有历史路径 + `VectorSetSemanticCache`）排在本设计 **R1 验收之后**。

---

## 9. 风险与对策

| 风险 | 等级 | 对策 |
|---|---|---|
| **富化幻觉**（添加历史之外的信息 → 键错 + 检索错） | 高 | 模板硬规则"仅可使用历史与原问题中的信息"；幻觉率 ≈0 入评测门槛；模板迭代走语义缓存 policyVersion |
| 结构化输出解析失败 | 中 | 降级路径（现有单字符串模板保留）；`query_rewrite_degraded_total` 监控；降级率告警 |
| Step-back 泛化文档淹没精确结果 | 中 | 等权 RRF 起步 + rerank 兜底重排；加权融合备选；开关可关 |
| 分解错误拆分改变原意 / 漏覆盖 | 中 | 模板"子问题须完整覆盖原问题全部要点 + 相互独立"规则；依赖式拒绝分解；评测组把关 |
| 检索扇出成本（最坏 5 路 hybrid） | 中 | 附加查询 ≤4 上限 + 去重跳过 + 并发限流 + per-strategy 开关；扇出指标监控 |
| 多轮历史过长拖慢改写 | 低 | history-rounds=5 + 每条截断 512 字符 |
| 与 Agent `QueryRewriteTool` 模板漂移 | 低 | 模板同文件管理（现有 javadoc 约束）；R3 后评估 Agent 工具是否切换统一模板 |

---

## 10. 测试计划

| 层次 | 覆盖 |
|---|---|
| 单元测试 | 决策规则边界（自包含/指代/细节密集/多子问题/依赖式拒绝）；解析降级（非法 JSON/超时/缺字段）；历史轮数与截断；子问题上限与去重 |
| 集成测试 | 多查询并行融合（去重/RRF/融合后进后处理器链，Query=enrichedQuery）；预改写入口跳过内层改写（**双重调用防回归**）；两处调用点（`AbstractModeStrategy:101/:168`）均覆盖 |
| 评测 | §7 四组（富化/step-back/分解/回归） |
| 混沌 | 改写超时/解析失败 → 降级路径 → 检索照常；策略开关组合（全关=现状） |

---

## 11. 参考资料

- Step-Back Prompting：*Take a Step Back: Evoking Reasoning via Abstraction in Large Language Models*（Google DeepMind, 2023, arXiv:2310.06117）
- Spring AI Query Transformation（`RewriteQueryTransformer` / `CompressionQueryTransformer` / `ExpansionQueryTransformer` 概念参照）：https://docs.spring.io/spring-ai/reference/api/retrieval-augmentation.html
- 本项目关联：[`docs/design/llm-semantic-cache.md`](./llm-semantic-cache.md)（消费方：缓存键）、`docs/AGENTIC-RAG-OPTIMIZATIONS.md`（Agent 侧多跳先例）、`docs/design/entity-centric-retrieval.md`（多跳检索下沉先例）
