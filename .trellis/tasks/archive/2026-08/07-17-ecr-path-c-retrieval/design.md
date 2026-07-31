# Design — Path C 在线检索（ecr-path-c-retrieval）

## Authoritative Source

完整技术设计见 **`docs/design/entity-centric-retrieval.md`**（1605 行，已通过 2 轮审计）。本文件仅记录子任务级设计补充；任何正文细节以主文档为准。所有 §x.y 引用均指该主文档。

## 指向章节

| 主文档章节 | 内容 | 本子任务关联 |
|---|---|---|
| §6.1 | Path C 检索流程 + 5 组件 SRP 拆分表 | 全部 5 个组件的职责边界 |
| §6.2 | 融合排序公式 + window-max 归一化 | `EntityFrontierRanker` 核心算法 |
| §6.3 | 实体投票回链（UnWeaver approval election） | `EntityVoteRetriever` SQL |
| §6.4 | SAG query-time expansion（纯结构 SQL JOIN） | `EntityExpansionRetriever` SQL |
| §6.5 | RetrievalPath 抽象 + OCP 注册 | `EntityRetrievalPath` 适配 |
| §7.1 | EntityRetrievalProperties 嵌套 record | 配置 record 定义 |
| §7.2 | application.yml entity.* 默认值 | YAML 配置块 |
| §9.1 | Search Trace JSON 格式 | trace 输出结构 |
| §11.4 | 延迟预算 + LLM 合并缓解 | 性能约束 + OQ1 优化门 |

## 子任务级设计补充

### 5 组件 SRP 拆分映射

```
EntityRetrievalPath (@Component, implements RetrievalPath)
  ├── EntitySeedExtractor         (PC1: query → LLM → seed entities)
  │     依赖: ChatCapable (DIP, via LlmClientRegistry/RewriteClientResolver)
  ├── EntityFrontierRanker        (PC2-PC3: seed → vector match → fusion → frontier)
  │     依赖: EntityMapper.findFrontierEntities() — §6.2 CTE SQL
  ├── EntityVoteRetriever         (PC4a: frontier → rag_chunk_entity → chunks)
  │     依赖: EntityMapper.voteBacklinkChunks() — §6.3 SQL
  ├── EntityExpansionRetriever   (PC4b: frontier → rag_event → new entities/chunks)
  │     依赖: EntityMapper.expandChunks() — §6.4 SQL
  └── PC5: 合并去重 (内存操作，EntityRetrievalPath 内部)
```

PC4a/PC4b 在 `EntityRetrievalPath.search()` 内通过 `ScopedTasks.fork()` 并行执行（同 Path A/B 并行模式）。

### Window-Max 归一化设计要点（§6.2）

SAG 原始方案用 `ORDER BY entity_frequency DESC LIMIT N`（频次剪枝），直接导致低频桥接实体被截断。本设计的三项 window-max 归一化解决此问题：

- `query_relevance` 原始峰值远不到 1.0（实际 ~0.85），而 `bridge_score`/`weak_tie_score` 可达较大值
- 若只归一化后两者，结构信号会被相对放大、压过主信号，α=0.5 的"主信号权重"名不副实
- 三项统一用 `max() OVER()` 归一化到 [0,1]（在 entity_match 窗口内计算，frontier 在归一化后取 top-K）
- 保证 α/β/γ 权重语义对称、可比

### 扩展纯结构契约（§6.4）

SAG §3.4 明确："expansion relies solely on SQL joins"。本设计忠实于此：

- `EntityExpansionRetriever` 的 SQL 不含 query embedding 匹配、不含 query 语义过滤
- 扩展发现的 chunk 分数 = δ × composite_score（发现它的中间 frontier 实体的结构传递分）
- query 语义裁决后移到 PC5 合并后的 RRF + rerank
- 这保证了扩展能发现"向量召回无法发现的、多跳推理链中的关键中间证据"（SAG Table 4: +10.6pt）

### Feature-Flag 门控

```java
@Component
@ConditionalOnProperty(prefix = "app.rag.entity", name = "enabled", havingValue = "true")
public class EntityRetrievalPath implements RetrievalPath { ... }
```

- `enabled=false`（默认）→ Bean 不创建 → `List<RetrievalPath>` 自动不含 Path C → HybridSearchService 零改动、零回归
- `enabled=true` → Bean 注册 → 自动被 `List<RetrievalPath>` 收集 → Path C 参与 RRF 融合
- 消融开关通过 `weakTieEnabled`/`communityDetectionEnabled`/`expansionHops` 细粒度控制组件行为（见下表）

### 消融开关与组件行为映射（§12.1 交叉引用 ecr-evaluation）

| 消融开关 | 影响组件 | 行为变化 | 消融配置 |
|---|---|---|---|
| `weakTieEnabled=false` | `EntityFrontierRanker` | γ 强制为 0 | +Vote+weak_tie 消融 |
| `communityDetectionEnabled=false` | `EntityFrontierRanker` | β 强制为 0 | +Vote+bridge 消融 |
| `expansionHops=0` | `EntityExpansionRetriever` | 返回空集，不执行 SQL | +Vote 消融（无扩展） |
| `enabled=false` | `EntityRetrievalPath` | Bean 不存在，Path C 缺席 | Baseline 消融 |

### 配置 ISP/OCP 映射（§7.1）

| 原则 | 落实点 |
|---|---|
| **ISP**（接口隔离） | `EntityRetrievalProperties` 嵌套 record 独立承载 13 个实体配置字段，与主 record 14 字段解耦。消费方通过 `properties.entity().alpha()` 访问，不污染主 record 的访问路径。 |
| **OCP**（开闭原则） | `EntityRetrievalPath` 通过 `@ConditionalOnProperty` + `RetrievalPath` 接口注册，不修改 `HybridSearchService`。未来新增实体配置字段只改嵌套 record，不动主 record + withOverrides。 |
| **SRP**（单一职责） | 5 个类各司一职，消除原方案单个 `EntityRetrievalService` 的 5 职责。 |
| **DIP**（依赖倒置） | `EntitySeedExtractor` 依赖 `ChatCapable` 接口（非具体客户端），通过 `LlmClientRegistry` 解析。 |

### LLM SPI 合规

`EntitySeedExtractor` 遵循 `llm-spi.md`：
- 通过 `RewriteClientResolver.resolve(entity.extractionModel)` 获取 `ChatClient`
- 模型 ID 遵循 registry 候选 ID 格式（如 `deepseek-v4-flash`）
- 不直接注入 `ChatClient.Builder` / `ChatModel`
- 不可用（模型未配置）时 fail-fast，不静默 fallback

### Trace 输出设计（§9.1）

复用项目现有 `AgentTrace` + `AgentEventStore` 模式：
- `EntityRetrievalPath.search()` 内用 `StopWatch` 记录每步耗时
- 构建 JSON trace（6 个 step），通过 `log.info` 输出（MDC traceId 自动串联）
- 可选通过 `AgentEventStore.recordRetrievalStrategy()` 持久化到 PG

### 风险

| 风险 | 缓解 |
|---|---|
| LLM seed 抽取延迟 ~300ms（§11.4） | 独立调用先行基线测量；LLM 合并优化作为 OQ1 后续 |
| 融合 SQL 复杂度（CTE + window function） | 单测 fixture 手算验证归一化；PostgreSQL 执行计划验证 |
| `EntityMapper` 方法归属冲突（extraction-pipeline owns interface） | 仅新增查询方法，不重定义写入方法；PRD OQ3 已确认 |
