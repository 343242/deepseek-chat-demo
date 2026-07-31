# Graph Algorithm Layer: WeightedGraph + AdjacencyListGraph + LeidenCommunityDetector

## Goal

在 `infrastructure/algorithm/graph/` 包下实现零业务依赖的通用图算法层：`WeightedGraph` 接口（无向加权简单图抽象）+ `AdjacencyListGraph` 实现（long[] packed 邻接表，减少 GC）+ `LeidenCommunityDetector` 纯算法（实现期由 Louvain 替换而来，见 design.md 决策 6）。本子任务为 Wave 0 无依赖任务，产出供 `ecr-structure-scores` 子任务通过 `WeightedGraph` 接口消费。

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
| `weightedDegree` | `double weightedDegree(long node)` | $k_i$（Leiden deltaQ 公式消费） |
| `totalWeight` | `double totalWeight()` | $m = \sum w / 2$（Leiden 归一化因子） |
| `nodeCount` | `int nodeCount()` | 节点总数 |

### R2: AdjacencyListGraph 实现（§5.2 ②）

- 内部用 `Long2ObjectMap<long[]>` 存储——`long[]` packed 邻接表（neighbor/weight 交替），减少对象化边的 GC 压力。
- `neighbors()` 返回 `Long2DoubleOpenHashMap` 只读视图。
- 无向语义：`addEdge(a, b, w)` 同时写入 a→b 和 b→a；重复调用累加权重。
- 空节点通过 `addNode()` 注册，`addEdge()` 自动注册两端节点。

### R3: LeidenCommunityDetector 纯算法（§5.2 ③，实现期由 Louvain 替换）

- 依赖 `WeightedGraph` 接口（DIP），不依赖任何业务概念。
- 三阶段迭代（Traag, Waltman & van Eck 2019）：(1) fast local moving——队列驱动贪心移动（含移入空社区候选）；(2) refinement——每个社区拆分为 γ-connected 子社区（修复 Louvain 的 disconnected communities 缺陷，`bridge_score` 依赖此性质）；(3) aggregation——按 refined 划分折叠为超节点，边权相加；下一层初始划分按 unrefined 分组。
- $\Delta Q$ 公式（节点先移出旧社区，含 resolution 参数 γ）：

$$\Delta Q = \frac{k_{i,C}}{m} - \frac{\gamma \cdot \Sigma_{\text{tot},C} \cdot k_i}{2 m^2}$$

- 移动条件：`add(best) > add(old) + ε`（ε = 1e-6，严格正改进）。
- 返回 `Long2IntMap`（node → community_id）。
- 确定性实现：升序 ID 遍历 + 确定性 max-ΔQ 选择（θ→0 极限）替代论文随机 θ，保证可复现。
- 算法输入为构造注入的 `WeightedGraph`；无 Spring Bean 注解（纯算法类，由 `CommunityDetectionJob` 通过 `new` 构造，见 §5.2 ⑤）。

### R4: 单元测试（全部落地，22/22 绿）

- Zachary Karate Club 标准图（34 节点、78 边）ground truth 验证（AC1）：确定性 Leiden 的 θ→0 refinement 会拆分坏连接子结构，社区数落在 [2, 7]，节点 1 和节点 34（Mr. Hi / John A）必属不同社区，Q > 0.3。
- **Headline Leiden 保证**：噪声合成图上 BFS 验证每个社区内部连通（γ-connected）。
- 确定性：两次 `detect()` 结果逐键一致。
- 合成聚类图测试：3 个团（各 10 节点，团内全连接 $w=1$）+ 团间稀疏桥边（$w=0.1$），断言 ≥ 3 个社区。
- resolution 参数：γ=2.0 将 K5 拆为 5 个单点社区，γ=0.01 合并为 1 个。
- 边缘 case 覆盖：孤立节点 + 连通分量、完全图（K₅）、单节点图、空图。
- 性能预算测试（AC6）：合成随机图 $V=10^4$、$E=10^5$（`Random(42)`），断言 `detect()` < 1s（`@Timeout(5)` 兜底）。

## Acceptance Criteria

- [x] AC1：**Leiden 在 Zachary Karate Club 上产出 [2, 7] 个社区且节点 1 与 34 分属不同社区、Q > 0.3**——本子任务的 headline 验收（`zacharyKarateClub_twoCommunities_node1Vs34Separate`）。
- [x] AC2：无向权重累加正确——`addEdge(1, 2, 3.0)` 后 `edgeWeight(1, 2) == 3.0`，再 `addEdge(2, 1, 2.0)` 后 `edgeWeight(1, 2) == 5.0`，`weightedDegree(1) == 5.0`。
- [x] AC3：孤立节点处理——`addNode(99)` 后 `nodes()` 含 99，`neighbors(99)` 为空 map，`weightedDegree(99) == 0.0`，Leiden 不崩溃。
- [x] AC4：完全图 K₅ edge case——`detect()` 不抛异常，产出合理社区分配。
- [x] AC5：模块度和 deltaQ 公式实现与 Traag 2019 一致——2-社区图 $Q \in [0, 1]$。
- [x] AC6：性能预算——合成图 $V=10^4$、$E=10^5$（`Random(42)`），`detect()` < 1s（@Timeout(5) 兜底；预算从计划的 100ms 放宽，见 implement.md Phase 5 说明）。
- [x] AC7：`WeightedGraph` 接口无 `import` 任何 `com.smart.rag` 包内类——零业务依赖。
- [x] AC8：fastutil 依赖已添加到 `pom.xml`（scope: compile，8.5.18）。
- [x] AC9：全量单测通过——`./mvnw test -Dtest="AdjacencyListGraphTest,LeidenCommunityDetectorTest"` 22/22 绿；`./mvnw test -Pdefault` 零回归。

## Dependencies

- **无前置依赖**——本子任务为 Wave 0，可与 `ecr-db-migration`、`ecr-retrieval-path-abstraction` 并行启动。
- **被依赖方**：`ecr-structure-scores` 子任务消费 `WeightedGraph` 接口 + `AdjacencyListGraph` 构造 + `LeidenCommunityDetector.detect()` 返回值（API 契约在 Louvain → Leiden 替换后不变）。

## Out of Scope

- 任何业务代码：`CooccurrenceGraphLoader`、`CommunityDetectionJob`、`EntityCooccurrenceMapper`——属 `ecr-structure-scores`。
- 任何 DB/mapper 代码——属 `ecr-db-migration` / `ecr-structure-scores`。
- 任何配置（`application.yml`、Properties 类）——本层无配置。
- 任何 Spring Bean 注解（`@Component` 等）——`WeightedGraph` 是纯接口，`AdjacencyListGraph` 是纯数据结构，`LeidenCommunityDetector` 是纯算法，均非 Spring 管理。
- weak_tie_score / bridge_score 计算——属 `ecr-structure-scores`，本层仅提供图抽象供其消费。

## Open Questions（全部已决）

- **OQ1：resolution 参数**——**已决**：`LeidenCommunityDetector(graph, resolution)` 构造器暴露 γ（double，默认 1.0，`<= 0` 抛异常）。`resolution_controlsGranularity` 测试验证 γ=2.0 拆分 / γ=0.01 合并。
- **OQ2：随机化种子稳定性**——**已决**：确定性实现（升序 ID 遍历 + 确定性 max-ΔQ 选择替代论文随机 θ）。`determinism_twoRunsIdentical` 锁死；AC1 断言同时保留宽度（社区数 ∈ [2, 7]）。
- **OQ3：fastutil 版本选择**——**已决**：`8.5.18`（2025-10-05 Maven Central，JDK 21 / Spring Boot 3.5 兼容），已入 pom.xml。
- **OQ4（新增，实现期）**：Louvain → Leiden 替换——**已决**：`bridge_score` 依赖社区内部连通性，Leiden 的 γ-connected 保证是必需性质。详见 design.md 决策 6。
