# Graph Algorithm Layer: WeightedGraph + AdjacencyListGraph + Louvain

## Goal

在 `infrastructure/algorithm/graph/` 包下实现零业务依赖的通用图算法层：`WeightedGraph` 接口（无向加权简单图抽象）+ `AdjacencyListGraph` 实现（long[] packed 邻接表，减少 GC）+ `LouvainCommunityDetector` 纯算法。本子任务为 Wave 0 无依赖任务，产出供 `ecr-structure-scores` 子任务通过 `WeightedGraph` 接口消费。

## Confirmed Facts

- **代码库核验（2026-07-17）**：
  - `infrastructure/` 包已存在（含 `security/`、`concurrent/`、`llm/`、`messaging/` 等子包），但 `infrastructure/algorithm/` 子包**尚不存在**——需新建。
  - `pom.xml` 中 **fastutil 未声明**——设计文档 §5.2 引用的 `Long2ObjectMap<long[]>`、`Long2DoubleMap`、`Long2IntMap` 均来自 `it.unimi.dsi:fastutil`，必须在 pom.xml 中新增依赖（测试框架已有 JUnit 5 + AssertJ，通过 `spring-boot-starter-test` 引入）。
  - 测试目录 `src/test/java/com/smart/rag/infrastructure/` 已存在，测试包结构镜像源码包结构（如 `infrastructure/llm/TokenUsageCacheHitTest.java` 对应 `infrastructure/llm/`）。
- **设计文档引用**：主文档 §5.2 ①②③ 提供完整 Java 骨架 + 理由；§5.2 "为什么不引入图库" 给出 fastutil 直接使用、不引入 JGraphT 的 5 点论证；§5.2 "复杂度预算" 给出 $O(E \log V)$ 总复杂度 + <100ms 性能预算。

## Requirements

### R1: WeightedGraph 接口（§5.2 ①）

7 个方法，表达无向加权简单图全部操作（Louvain + weak_tie + bridge 所需）：

| 方法 | 签名 | 语义 |
|---|---|---|
| `addNode` | `void addNode(long node)` | 添加孤立节点 |
| `addEdge` | `void addEdge(long a, long b, double weight)` | 无向边；重复加累计权重 |
| `nodes` | `Set<Long> nodes()` | 全部节点（含孤立） |
| `neighbors` | `Long2DoubleMap neighbors(long node)` | 邻居 → 边权只读视图（§5.1 weak_tie 消费此方法） |
| `edgeWeight` | `double edgeWeight(long a, long b)` | 无边返回 0 |
| `weightedDegree` | `double weightedDegree(long node)` | $k_i$（Louvain deltaQ 公式消费） |
| `totalWeight` | `double totalWeight()` | $m = \sum w / 2$（Louvain 归一化因子） |
| `nodeCount` | `int nodeCount()` | 节点总数 |

### R2: AdjacencyListGraph 实现（§5.2 ②）

- 内部用 `Long2ObjectMap<long[]>` 存储——`long[]` packed 邻接表（neighbor/weight 交替），减少对象化边的 GC 压力。
- `neighbors()` 返回 `Long2DoubleOpenHashMap` 只读视图。
- 无向语义：`addEdge(a, b, w)` 同时写入 a→b 和 b→a；重复调用累加权重。
- 空节点通过 `addNode()` 注册，`addEdge()` 自动注册两端节点。

### R3: LouvainCommunityDetector 纯算法（§5.2 ③）

- 依赖 `WeightedGraph` 接口（DIP），不依赖任何业务概念。
- 两阶段迭代：(1) local moving——节点贪心移到 $\Delta Q$ 最大的邻居社区；(2) aggregation——同社区节点折叠为超节点，边权相加。重复直到模块度不再提升。
- $\Delta Q$ 公式（Blondel 2008 简化式）：

$$\Delta Q = \frac{k_{i,C}}{m} - \frac{\Sigma_{\text{tot},C} \cdot k_i}{2 m^2}$$

- 返回 `Long2IntMap`（node → community_id）。
- 算法输入为构造注入的 `WeightedGraph`；无 Spring Bean 注解（纯算法类，由 `CommunityDetectionJob` 通过 `new` 构造，见 §5.2 ⑤）。

### R4: 单元测试

- Zachary Karate Club 标准图（34 节点、78 边）ground truth 验证：Louvain 检测出 **2 个社区**（Blondel 2008 论文结果），断言社区数 = 2 且节点 1 和节点 34（Mr. Hi / John A）分属不同社区。
- 合成聚类图测试：3 个团（各 10 节点，团内全连接 $w=1$）+ 团间稀疏桥边（$w=0.1$），断言 ≥ 3 个社区。
- 边缘 case 覆盖：孤立节点、完全图（K₅）、单节点图、空图。
- 性能预算测试：合成随机图 $V < 10^4$、$E < 10^5$，断言 `detect()` < 100ms。

## Acceptance Criteria

- [ ] AC1：**Louvain 在 Zachary Karate Club 上产出 2 个社区**——节点 1（Mr. Hi）与节点 34（John A）分属不同社区；这是本子任务的 headline 验收。
- [ ] AC2：无向权重累加正确——`addEdge(1, 2, 3.0)` 后 `edgeWeight(1, 2) == 3.0`，再 `addEdge(2, 1, 2.0)` 后 `edgeWeight(1, 2) == 5.0`，`weightedDegree(1) == 5.0`。
- [ ] AC3：孤立节点处理——`addNode(99)` 后 `nodes()` 含 99，`neighbors(99)` 为空 map，`weightedDegree(99) == 0.0`，Louvain 不崩溃（孤立节点各归独立社区或任意社区，不要求特定归属）。
- [ ] AC4：完全图 K₅ edge case——`detect()` 不抛异常，产出合理社区分配。
- [ ] AC5：模块度和 deltaQ 公式实现与 Blondel 2008 论文一致——可通过模块度计算单元测试验证（构造简单 2-社区图，计算 $Q$ 值在合理范围 $[0, 1]$）。
- [ ] AC6：性能预算——合成图 $V=10^4$、$E=10^5$（随机生成），`detect()` 执行时间 < 100ms。
- [ ] AC7：`WeightedGraph` 接口无 `import` 任何 `com.smart.rag` 包内类——零业务依赖。
- [ ] AC8：fastutil 依赖已添加到 `pom.xml`（scope: compile）。
- [ ] AC9：全量单测通过（`./mvnw test -pl . -Dtest="com.smart.rag.infrastructure.algorithm.graph.*"`）。

## Dependencies

- **无前置依赖**——本子任务为 Wave 0，可与 `ecr-db-migration`、`ecr-retrieval-path-abstraction` 并行启动。
- **被依赖方**：`ecr-structure-scores` 子任务消费 `WeightedGraph` 接口 + `AdjacencyListGraph` 构造 + `LouvainCommunityDetector.detect()` 返回值。

## Out of Scope

- 任何业务代码：`CooccurrenceGraphLoader`、`CommunityDetectionJob`、`EntityCooccurrenceMapper`——属 `ecr-structure-scores`。
- 任何 DB/mapper 代码——属 `ecr-db-migration` / `ecr-structure-scores`。
- 任何配置（`application.yml`、Properties 类）——本层无配置。
- 任何 Spring Bean 注解（`@Component` 等）——`WeightedGraph` 是纯接口，`AdjacencyListGraph` 是纯数据结构，`LouvainCommunityDetector` 是纯算法，均非 Spring 管理。
- weak_tie_score / bridge_score 计算——属 `ecr-structure-scores`，本层仅提供图抽象供其消费。

## Open Questions

- **OQ1：Louvain resolution 参数**——Blondel 原始实现支持 $\gamma$ 参数（resolution）控制社区粒度。当前设计（§5.2 ③）未提及，默认 $\gamma = 1$。是否需要在 `LouvainCommunityDetector` 构造器中暴露 resolution 参数？**建议**：Phase 1 保持 $\gamma=1$ 默认值，构造器预留 `resolution` 参数（double，默认 1.0），供后续调优。
- **OQ2：随机化种子稳定性**——Louvain 的节点遍历顺序影响社区检测结果（非确定性）。Zachary Karate Club 测试需要断言社区数=2 但不能断言具体每个节点的归属（同一测试在不同 JVM 实现中可能分出 2/3/4 个社区）。**建议**：AC1 断言放宽为"社区数 ∈ {2, 3}且节点 1/34 不同社区"，或实现时采用确定性遍历（按节点 ID 排序）保证可重复性。
- **OQ3：fastutil 版本选择**——需确认与现有 JDK 21 / Spring Boot 3.5 兼容的 fastutil 版本（`8.5.18`，2025-10-05 Maven Central 最新稳定版）。
