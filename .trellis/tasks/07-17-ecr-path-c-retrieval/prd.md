# Path C 在线检索（ecr-path-c-retrieval）

## Goal

实现 Path C 在线检索——以 `EntitySeedExtractor` → `EntityFrontierRanker` → `EntityVoteRetriever` / `EntityExpansionRetriever` 三层流水线，作为 hybridSearch 的第三路并行召回路径。通过 `entity.enabled` feature flag 控制注册，默认 `false` 保证零回归。

## Confirmed Facts（代码库核验，2026-07-17）

1. **`RagRetrievalProperties`** 位于 `src/main/java/com/smart/rag/rag/config/RagRetrievalProperties.java`（85 行），当前 14 个字段，record + compact constructor + `withOverrides(Integer, Integer, Integer)` 方法。无嵌套 record。扩展点清晰：在末尾新增 `EntityRetrievalProperties entity` 字段 + `withOverrides` 透传 `entity`。对应测试 `src/test/java/com/smart/rag/rag/config/RagRetrievalPropertiesTest.java`（3 个 @Nested 测试组）。

2. **`RetrievalPath` 接口** 由 `ecr-retrieval-path-abstraction` 子任务拥有。`EntityRetrievalPath` 在本子任务中实现该接口，import 路径为 `com.smart.rag.agent.service.retrieval.RetrievalPath`（以 `retrieval-path-abstraction` 最终落盘为准，设计文档 §6.5 给出 `HybridSearchService` 位于 `agent/service/`）。

3. **LLM SPI**：`EntitySeedExtractor` 通过 `ChatCapable`（`com.smart.rag.infrastructure.llm.ChatCapable`）接口调用 LLM 抽取 seed entities，遵守 `llm-spi.md` 契约——通过 `RewriteClientResolver` 或 `LlmClientRegistry.get(candidateId, ChatCapable.class)` 获取客户端。模型 ID 由 `entity.extractionModel` 配置（默认 `null`，走默认候选），格式遵循 registry 候选 ID（如 `deepseek-v4-flash`）。现有用法参考 `IntentClassifier` 和 `QueryRewriteTool`。

4. **MyBatis Mapper 约定**：`@MapperScan` 已包含 `com.smart.rag.rag.mapper`。Mapper XML 位于 `src/main/resources/mapper/`，命名与 Java 接口一致（如 `VectorStoreMapper.xml`）。复杂 CTE SQL 走 XML 而非注解。本子任务新增的 `EntityMapper` 方法（fusion/vote/expansion SQL）须在 `com.smart.rag.rag.mapper.EntityMapper`（接口）+ `src/main/resources/mapper/EntityMapper.xml`（XML）中定义。**注意**：`EntityMapper` 归属 `ecr-extraction-pipeline`（父级 Mapper 归属契约 #1），本子任务只新增查询方法，不重定义已有方法。

5. **Trace 模式**：项目使用 `AgentTrace`（`com.smart.rag.agent.trace.AgentTrace`，请求级 record）+ `AgentEventStore`（持久化到 PG `agent_session_event`）。Path C trace 复用此模式——在 `EntityRetrievalPath.search()` 中构建 JSON 结构化 trace 数据，通过 log + AgentEventStore.recordRetrievalStrategy() 输出，遵循现有 `traceId` MDC 传播链。

6. **`HybridSearchService`** 位于 `com.smart.rag.agent.service.HybridSearchService`，当前直接依赖 `VectorStore`/`VectorStoreMapper`/`RagRetrievalProperties`/`QueryNormalizer`/`ScopedTasks`，内部用 `DefaultScopedTasks` 做 fork/join 并行。重构（`ecr-retrieval-path-abstraction`）后改为依赖 `List<RetrievalPath>`，Path C Bean 不存在时自动缺席。

7. **安全隔离**：`rag_entity.user_id = :userId`（BIGINT）+ `vector_store.metadata->>'userId' = :userIdStr`（字符串），两种绑定在 SQL 中同时传入。设计文档 §10.1 确认双轨隔离约定。

8. **`application.yml` 现状**：`app.rag` 下仅有 `fts-config: ${RAG_FTS_CONFIG:jiebacfg}`，其余 RAG 配置走 Spring Boot 默认绑定。新增 `entity:` 嵌套块。

## Requirements

### R1：五组件 SRP 拆分（§6.1 表）

| 组件 | 职责 | 依赖 |
|---|---|---|
| `EntitySeedExtractor` | query → LLM 抽取 seed entities（PC1） | `ChatCapable`（DIP） |
| `EntityFrontierRanker` | seed → 向量匹配 → 融合排序 → frontier 剪枝（PC2-PC3） | `EntityMapper`（fusion SQL） |
| `EntityVoteRetriever` | frontier → 投票回链 chunks（PC4a，UnWeaver） | `EntityMapper`（vote SQL） |
| `EntityExpansionRetriever` | frontier → SAG H 跳扩展 → chunks（PC4b） | `EntityMapper`（expansion SQL） |
| `EntityRetrievalPath` | 编排上述 4 类 + PC5 合并去重，实现 `RetrievalPath` | 上述 4 类 |

### R2：EntityRetrievalPath 注册（§6.5）

- `@Component + @ConditionalOnProperty(prefix="app.rag.entity", name="enabled", havingValue="true")`
- `rrfWeighting() = SCORE_WEIGHTED`
- `name() = "entity"`（trace/日志标识）

### R3：融合排序（§6.2）

- `composite_score = α·query_rel_norm + β·bridge_norm + γ·weak_tie_norm`
- 三项均用 `max() OVER()` window 归一化到 [0,1]（归一化在剪枝前计算，frontier 在归一化后取 top-K）
- α 默认 0.5、β 默认 0.3、γ 默认 0.2
- `weakTieEnabled=false` 时 γ 强制为 0；`communityDetectionEnabled=false` 时 β 强制为 0

### R4：投票回链（§6.3）

- chunk_score = `max(entity.composite_score)`（默认 max 策略）
- 返回 `voted_by_entities` 数组用于 trace

### R5：SAG 结构扩展（§6.4）

- H=1 默认（0=禁用扩展）
- 纯结构 SQL JOIN（`entity → event → new_entity → new_event`），**不在扩展阶段施加 query 语义过滤**
- 衰减因子 δ=0.7（`expansionDecay`）
- `expansionHops=0` 时 `EntityExpansionRetriever` 返回空集（干净禁用路径）

### R6：配置扩展（§7.1 + §7.2）

- `RagRetrievalProperties` 新增 `EntityRetrievalProperties entity` 字段（嵌套 record，13 字段）
- `withOverrides` 透传 `entity` 原值（唯一新增行）
- `EntityRetrievalProperties` compact constructor 包含所有校验（阈值默认值、α/β/γ >=0 且不全为 0）
- `application.yml` 新增 `app.rag.entity:` 嵌套块（15 个配置项，默认 `enabled: false`）

### R7：Trace（§9.1）

- 每步输出 `{ step, durationMs, ... }` JSON 结构
- 6 个 step：entity_extraction / entity_match / fusion_ranking / vote_backlink / sag_expansion / merge
- 通过 log.info 输出 + 可选写入 AgentEventStore

### R8：只读结构分列

- `weak_tie_score`/`bridge_score`/`community_id` 由 `ecr-structure-scores` 离线写入，本子任务仅读取参与融合排序
- 未计算时用默认值兜底不阻塞查询（weak_tie 默认 0.5，bridge 默认 0）

## Acceptance Criteria

- [ ] **AC1**：`entity.enabled=false`（默认）时，`EntityRetrievalPath` Bean 不存在，`List<RetrievalPath>` 仅含 Path A/B，hybridSearch 行为与现状完全等价——既有 HybridSearchService 全部测试绿色。
- [ ] **AC2**：`entity.enabled=true` 时，`EntityRetrievalPath` 注册为第三路 `RetrievalPath`，`name()="entity"`，`rrfWeighting()=SCORE_WEIGHTED`。
- [ ] **AC3**：`EntitySeedExtractor.extract(query)` 返回非空 `List<String>`（seed entities），使用 `ChatCapable` SPI，模型由 `extractionModel` 配置控制。
- [ ] **AC4**：融合排序 window-max 归一化正确——给定 fixture（3 个 entity，query_rel=[0.9, 0.5, 0.1], bridge=[0, 3, 6], weak_tie=[0.5, 0.8, 0.2]），归一化后为 query_rel_norm=[1.0, 0.556, 0.111], bridge_norm=[0, 0.5, 1.0], weak_tie_norm=[0.625, 1.0, 0.25]，composite_score=0.5×norm + 0.3×norm + 0.2×norm 手算验证通过。
- [ ] **AC5**：`EntityVoteRetriever` 返回 chunks 包含 `voted_by_entities` trace 字段。
- [ ] **AC6**：`EntityExpansionRetriever` H=1 时发现不在 vote 集中的结构 chunks（验证 `expandedChunks ∩ voteChunks ⊂ totalChunks`）。
- [ ] **AC7**：`expansionHops=0` 时 `EntityExpansionRetriever.search()` 返回空集，不执行 SQL。
- [ ] **AC8**：`weakTieEnabled=false` 时 γ 强制为 0，融合公式退化为 `α·query_rel_norm + β·bridge_norm`。
- [ ] **AC9**：`communityDetectionEnabled=false` 时 β 强制为 0，融合公式退化为 `α·query_rel_norm + γ·weak_tie_norm`。
- [ ] **AC10**：trace JSON 输出格式符合 §9.1（含 6 个 step、totalDurationMs）。
- [ ] **AC11**：`EntityRetrievalProperties` 校验：α/β/γ < 0 或全为 0 时构造抛 `IllegalArgumentException`；各字段默认值正确。
- [ ] **AC12**：`RagRetrievalProperties.withOverrides` 正确透传 `entity` 原值（不覆盖）。

## Dependencies（父级 dependency graph，verbatim）

- **ecr-db-migration**（Wave 0）：V21 schema 四表必须存在
- **ecr-retrieval-path-abstraction**（Wave 0）：`RetrievalPath` 接口 + `ScoredDocument` record + `HybridSearchService` 重构完成，Path A/B 适配器就位
- **ecr-extraction-pipeline**（Wave 1）：`EntityMapper` 接口 + mapper XML 存在（含 fusion/vote/expansion 查询方法）；实体/event/chunk_entity 数据可供查询
- **读取** `ecr-structure-scores` 列（`weak_tie_score`/`bridge_score`/`community_id`），不依赖其计算完成（未计算时用默认值兜底）
- 本子任务位于 **Wave 3**

## Out of Scope

- 结构分计算（`ecr-structure-scores`，仅读取其列）
- `HybridSearchService` 重构（`ecr-retrieval-path-abstraction`，本子任务仅实现 `EntityRetrievalPath` 适配器）
- Agent 工具暴露（§10.4 后续阶段）
- `EntityMapper` 中属于 `ecr-extraction-pipeline` 的写入方法（UPSERT/INSERT）
- LLM seed extraction 与 query rewrite 合并优化（§11.4 缓解，记为优化门）

## Open Questions

- **OQ1**：LLM seed extraction 与 query rewrite 合并为单次 LLM 调用（§11.4，输出 `{rewrittenQuery, entities}`）——是本阶段优化门还是后续迭代？合并可消除 ~300ms 延迟（Path C < 200ms），但需改动 `RewriteQueryTransformer`/`EntitySeedExtractor` 接口。**建议**：本阶段独立调用，性能基线建立后作为首优优化。
- **OQ2**：`EntityRetrievalPath` 实现类的 Java 包位置——设计文档 §10.2 建议 `rag/retrieval/`，但当前 `HybridSearchService` 在 `agent/service/`，`RetrievalPath` 接口由 `ecr-retrieval-path-abstraction` 确定最终位置。**建议**：`com.smart.rag.rag.retrieval.path.EntityRetrievalPath`，待 `retrieval-path-abstraction` 落盘后对齐。
- **OQ3**：`EntityMapper` 新增的 fusion/vote/expansion 查询方法——接口定义在 `ecr-extraction-pipeline` 的 mapper 中还是本子任务新建独立 mapper？父级 Mapper 归属契约 #1 说 `EntityMapper` 属 `ecr-extraction-pipeline`。**结论**：本子任务在已有 `EntityMapper` 接口中新增查询方法，mapper XML 也放在同一 `EntityMapper.xml` 中。新增方法不得重定义 extraction-pipeline 已有方法。
