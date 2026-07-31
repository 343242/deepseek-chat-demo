# Design — Graph Algorithm Layer

## Authoritative Source

技术设计见 `docs/design/entity-centric-retrieval.md` **§5.2 ①②③**（`WeightedGraph` 接口骨架、`AdjacencyListGraph` 实现、`LeidenCommunityDetector` 纯算法）。

本文件仅记录本子任务的设计补充和边界说明。**不复制主文档正文**，避免漂移。

## Scope

- **拥有**：`src/main/java/com/smart/rag/infrastructure/algorithm/graph/` 包下 3 个 Java 文件 + 对应测试。
- **不拥有**：`CooccurrenceGraphLoader`、`CommunityDetectionJob`、任何 Mapper/SQL/Properties（属 `ecr-structure-scores`）。

## Design References（主文档章节映射）

| 产物 | 主文档章节 | 关键设计点 |
|---|---|---|
| `WeightedGraph` 接口 | §5.2 ① | 7 方法；DIP 抽象；无 JGraphT 依赖 |
| `AdjacencyListGraph` | §5.2 ② | `Long2ObjectMap<long[]>` packed 邻接表；GC 压力优化 |
| `LeidenCommunityDetector` | §5.2 ③ | Traag 2019 三阶段（含 refinement）；$\Delta Q$ 公式；`Long2IntMap` 返回。**实现期从 Louvain 替换而来**（见下方决策 6） |
| "为什么不引入图库" | §5.2 | 5 点论证：用不上 / JGraphT 不含 Louvain/Leiden / 许可证 / GC / 可控 |
| 复杂度预算 | §5.2 | $O(E \log V)$；<100ms on $V<10^4$/$E<10^5$ |
| 三层架构 | §5.2 架构图 | INFRA(本子任务) → RAG(`ecr-structure-scores`)；CARP + DIP + SRP |
| weak_tie 消费 | §5.1 | `neighbors(e)` 返回邻居集 → 本接口 `neighbors()` 方法 |
| bridge_score 消费 | §5.2 ④ | `community_id` 来自 `LeidenCommunityDetector.detect()` 返回值 |

## Child-Specific Design Decisions

### 1. 包结构：`infrastructure/algorithm/graph/`（CARP 原则落地）

图算法下沉到 `infrastructure/` 基础设施层（非 `rag/` 业务层），理由：

- **零业务依赖**：`WeightedGraph` 接口仅依赖 JDK + fastutil，不 import 任何 `com.smart.rag` 类。
- **可复用性**：未来可用于文档相似度图、用户关系图等任何加权无向图场景（OCP）。
- **依赖方向**：`rag/service/impl/`（业务层）→ `infrastructure/algorithm/graph/`（基础层），符合 DIP 依赖倒置——高层依赖抽象，不依赖实现细节。

```
com.smart.rag.infrastructure.algorithm.graph/
├── WeightedGraph.java           # 接口（§5.2 ①）
├── AdjacencyListGraph.java      # 实现（§5.2 ②）
└── LeidenCommunityDetector.java # 纯算法（§5.2 ③）
```

### 2. DIP：LeidenCommunityDetector 依赖 WeightedGraph 接口

```
LeidenCommunityDetector
    └── 构造注入 WeightedGraph（接口类型）
        ├── AdjacencyListGraph（实际注入的实现）
        └── 未来可替换为其他 WeightedGraph 实现（OCP）
```

`LeidenCommunityDetector` 不是 Spring Bean——由 `CommunityDetectionJob`（属 `ecr-structure-scores`）通过 `new LeidenCommunityDetector(graph)` 构造（§5.2 ⑤ 注释："Detector 是无状态纯算法，构造即用，无需 Factory 抽象"）。

### 3. AdjacencyListGraph packed long[] GC 理由（§5.2 ② + "为什么不引入图库"第4点）

共现图天然稀疏（$V$ 百~千级、$E$ 万级），邻接表是最优数据结构。`Long2ObjectMap<long[]>` 用 primitive `long[]` 存储 neighbor/weight 交替，避免 `Long`/`Double` 对象装箱：

```java
// packed long[] 示意：[neighbor1, weight1_bits, neighbor2, weight2_bits, ...]
// weight 用 Double.doubleToRawLongBits / Double.longBitsToDouble 转换
```

对比 JGraphT 的 `DefaultWeightedEdge`（每条边一个对象，含 2 个 `Long` + 1 个 `Double`），packed long[] 减少约 3× 对象分配。

### 4. Leiden deltaQ 推导（§5.2 ③）

Leiden 与 Louvain 使用同一模块度质量函数（节点先移出旧社区）：

$$\Delta Q = \frac{k_{i,C}}{m} - \frac{\gamma \cdot \Sigma_{\text{tot},C} \cdot k_i}{2 m^2}$$

其中：
- $k_{i,C}$ = 节点 $i$ 到社区 $C$ 内所有节点的边权之和
- $\Sigma_{\text{tot},C}$ = 社区 $C$ 内所有节点的加权度数之和（含社区内部边）
- $k_i$ = 节点 $i$ 的加权度数
- $m$ = 图的总权重（$= \sum w / 2$）
- $\gamma$ = resolution 参数（构造器可选，默认 1.0；`resolution <= 0` 抛异常）

移动条件为严格正改进：`add(best) > add(old) + ε`（ε = 1e-6）。实现时通过 `WeightedGraph.weightedDegree(node)` 获取 $k_i$，通过 `WeightedGraph.totalWeight()` 获取 $m$，通过 `WeightedGraph.neighbors(node)` 遍历邻居累计 $k_{i,C}$。

### 5. Test Fixtures

| Fixture | 用途 | 来源 |
|---|---|---|
| Zachary Karate Club（34 节点、78 边） | Louvain ground truth 验证——Blondel 2008 标准基准图，应检测出 2 个社区 | 标准社交网络数据集（边列表公开） |
| 合成 3-团图（3×10 节点 + 稀疏桥边） | 验证多社区检测能力 | 手动构造 |
| 合成随机图（$V=10^4$, $E=10^5$） | 性能预算 <100ms | 随机生成（固定 seed） |
| 边缘 case 图（空图、单节点、孤立节点 + K₅） | 鲁棒性验证 | 手动构造 |

Zachary Karate Club 标准边列表（34 节点，0-indexed）将在测试中硬编码为 `static final` 方法或测试资源文件。

## Design Principle Mapping

| 原则 | 落实点 | 说明 |
|---|---|---|
| SRP | `WeightedGraph` 只定义图操作契约、`AdjacencyListGraph` 只管存储、`LeidenCommunityDetector` 只管算法 | 三类各司一职 |
| OCP | 新增 `WeightedGraph` 实现即可替换存储结构；Leiden 可替换 Louvain 而下游不改（本次 pivot 即 OCP 实例） | 接口隔离变化 |
| DIP | Leiden 依赖 `WeightedGraph` 接口而非 `AdjacencyListGraph` 具体类 | 依赖抽象 |
| CARP | 算法下沉 `infrastructure/algorithm/graph/`，不污染 `rag/` 业务包 | 基础设施层隔离 |
| ISP | `WeightedGraph` 7 方法刚好覆盖三种算法（Louvain/weak_tie/bridge）所需的全部操作，无冗余方法 | 最小接口 |

### 6. 变更记录：Louvain → Leiden（实现期决策，2026-07）

**决策**：实现阶段将 `LouvainCommunityDetector`（Blondel 2008）替换为 `LeidenCommunityDetector`（Traag, Waltman & van Eck 2019，"From Louvain to Leiden: guaranteeing well-connected communities"）。主设计文档 §5.2 ③、§5.2 ⑤ 及架构图已同步改写；本子任务 `implement.md`/`prd.md` 同步更新。

**动机**：Louvain 的 local moving + aggregation 可产出**内部不连通**的社区（disconnected communities 缺陷，论文 §1 例证）。本任务下游 `bridge_score`（§5.2 ④）统计"跨社区邻居数"——一个不连通的社区会把本应属于同社区的两部分误判为桥接关系，直接污染 bridge 语义。Leiden 在 local moving 与 aggregation 之间插入 **refinement** 阶段，保证每个社区 γ-连通。

**差异**（详见 `LeidenCommunityDetector` Javadoc）：
- 三阶段：fast local moving（队列驱动，仅重访邻域变化的节点）→ refinement（拆分为 γ-connected 子社区）→ aggregation（按 refined 划分折叠，下一层初始划分按 unrefined 分组）。
- 确定性：θ→0 极限（恒选最大 ΔQ 的 γ-connected 目标）+ 升序 ID 遍历，替代论文的随机 θ 选择——换取可复现性，放弃论文的渐近子集最优性保证（对本任务规模无实际影响）。
- 复杂度：每轮 O(E)，fast local move 比 Louvain 全量扫描更快；V=10⁴/E=10⁵ 单次 detect < 1s（实测远低于预算，AC6）。

**兼容性**：API 契约不变（`detect(): Long2IntMap` node → community_id，构造器注入 `WeightedGraph`），下游 `CommunityDetectionJob` 仅换 import；`WeightedGraph` 接口本身未变。回退路径：revert 本提交即恢复 Louvain。

## Risks

- **R1：社区检测非确定性**——节点遍历顺序影响结果。Zachary Karate Club 在不同实现中可能分出 2/3/4 个社区。**已解决**：Leiden 采用确定性实现（升序 ID 遍历 + 确定性 max-ΔQ 选择替代论文随机 θ），`determinism_twoRunsIdentical` 测试锁死该性质；AC1 断言同时保留宽度（社区数 ∈ [2, 7]）。
- **R2：packed long[] 正确性**——`Double.doubleToRawLongBits`/`Double.longBitsToDouble` 转换需精确。缓解：单测覆盖 NaN / Infinity / -0.0 等特殊浮点值。
- **R3：fastutil 版本兼容**——需确认 JDK 21 兼容性。fastutil 8.5.18（2025-10-05 发布，Maven Central 最新）支持 JDK 11+，已验证可行。
