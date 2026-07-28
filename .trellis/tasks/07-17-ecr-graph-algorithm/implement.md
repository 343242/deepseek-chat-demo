# Implementation Plan — Graph Algorithm Layer

## Ordered Checklist

### Phase 1: 依赖 & 包结构

- [ ] **1.1** 在 `pom.xml` 中添加 fastutil 依赖：
  ```xml
  <dependency>
      <groupId>it.unimi.dsi</groupId>
      <artifactId>fastutil</artifactId>
      <version>8.5.18</version>
  </dependency>
  ```
  验证：`./mvnw dependency:resolve -Dincludes=it.unimi.dsi:fastutil` 成功。
- [ ] **1.2** 创建包目录 `src/main/java/com/smart/rag/infrastructure/algorithm/graph/`。
- [ ] **1.3** 创建测试包目录 `src/test/java/com/smart/rag/infrastructure/algorithm/graph/`。

### Phase 2: WeightedGraph 接口

- [ ] **2.1** 创建 `WeightedGraph.java`（§5.2 ①）——7 个方法，Javadoc 完整。
  - 验证：`addNode` / `addEdge` / `nodes` / `neighbors` / `edgeWeight` / `weightedDegree` / `totalWeight` / `nodeCount`。
  - 约束：接口文件**零 `com.smart.rag` import**。

### Phase 3: AdjacencyListGraph 实现 + 测试

- [ ] **3.1** 创建 `AdjacencyListGraph.java`（§5.2 ②）——`Long2ObjectMap<long[]>` packed 邻接表。
  - 关键实现点：
    - `addEdge(a, b, w)`：无向语义（a→b 和 b→a 均写入），重复累加。
    - `neighbors(node)`：返回 `Long2DoubleOpenHashMap` 只读视图（从 packed long[] 解包）。
    - `edgeWeight(a, b)`：查找 packed 数组，无边返回 0。
    - `totalWeight()`：维护运行时累加（或遍历计算）。
  - 参考：`.trellis/spec/guides/code-reuse-thinking-guide.md`（无现有代码可复用，全新实现）。
- [ ] **3.2** 创建 `AdjacencyListGraphTest.java`：
  - 测试用例：
    - `addNode_isolatedNode_neighborsEmpty`（AC3）
    - `addEdge_undirectedWeightAccumulation`（AC2：3+2=5）
    - `edgeWeight_noEdge_returnsZero`
    - `nodes_returnsAllNodesIncludingIsolated`
    - `totalWeight_singleEdge_returnsHalfSum`
    - `emptyGraph_nodeCountZero`
    - `completeGraph_K5_noException`（AC4）
  - 验证：`./mvnw test -Dtest="com.smart.rag.infrastructure.algorithm.graph.AdjacencyListGraphTest"`

### Phase 4: LouvainCommunityDetector + Karate Club 测试

- [ ] **4.1** 创建 `LouvainCommunityDetector.java`（§5.2 ③）——Blondel 2008 两阶段迭代。
  - 关键实现点：
    - 构造器注入 `WeightedGraph`，预计算 `m = graph.totalWeight()`。
    - `detect()` 返回 `Long2IntMap`（node → community_id）。
    - `deltaQ(node, targetCommunity)` 实现 $\Delta Q = k_{i,C}/m - \Sigma_{\text{tot},C} \cdot k_i / (2m^2)$。
    - Phase 1 (local moving)：遍历所有节点，贪心移到 $\Delta Q > 0$ 最大的社区；重复直到无移动。
    - Phase 2 (aggregation)：同社区节点折叠为超节点，边权相加；重新构造缩略图。
    - 终止条件：模块度增量 ≤ ε（建议 ε = 1e-6）或达到最大轮数（建议 100）。
    - **确定性遍历**：按节点 ID 排序遍历（消除随机性，保证测试可重复）。
  - 参考：`.trellis/spec/backend/quality-guidelines.md`（Java 21 / record / var 风格）。
- [ ] **4.2** 创建 `LouvainCommunityDetectorTest.java`：
  - **Karate Club 测试**（AC1，headline）：
    - 硬编码 Zachary Karate Club 34 节点 78 边标准边列表。
    - 构造 `AdjacencyListGraph`，加载边列表。
    - `detect()` → 断言：社区数 = 2（确定性实现下），节点 1 与节点 34 分属不同社区。
  - 合成 3-团图测试：
    - 构造 3 个团（各 10 节点，团内 $w=1$）+ 3 条团间桥边（$w=0.1$）。
    - `detect()` → 断言社区数 ≥ 3。
  - 边缘 case：
    - 空图 → 空结果。
    - 单节点 → 1 个社区。
    - 孤立节点 + 连通分量 → 孤立节点不导致崩溃。
    - 完全图 K₅ → 不抛异常（AC4）。
    - 模块度合理性 → 构造简单 2-社区图，计算 $Q \in [0, 1]$（AC5）。
  - 验证：`./mvnw test -Dtest="com.smart.rag.infrastructure.algorithm.graph.LouvainCommunityDetectorTest"`

### Phase 5: 性能预算测试

- [ ] **5.1** 在 `LouvainCommunityDetectorTest.java` 中添加性能测试：
  - 合成随机图 $V=10^4$、$E=10^5$（固定 Random seed = 42 保证可重复）。
  - 断言 `detect()` 执行时间 < 100ms（AC6）。
  - 注意：CI 环境可能较慢，设置宽松上限 500ms（CI）/ 100ms（本地），或使用 JUnit `@Timeout`。

### Phase 6: 全量验证 & Gate

- [ ] **6.1** 运行全量单测：
  ```bash
  ./mvnw test -Dtest="com.smart.rag.infrastructure.algorithm.graph.*"
  ```
  验证 AC9：全部通过。
- [ ] **6.2** 运行全量测试套件确认零回归：
  ```bash
  ./mvnw test -Pdefault
  ```
  验证新增代码未破坏既有测试。

## Validation Commands

```bash
# 依赖解析
./mvnw dependency:resolve -Dincludes=it.unimi.dsi:fastutil

# 本子任务单测
./mvnw test -Dtest="com.smart.rag.infrastructure.algorithm.graph.*"

# 全量回归
./mvnw test -Pdefault
```

## File Inventory

| 文件 | 类型 | 路径 |
|---|---|---|
| WeightedGraph.java | 接口 | `src/main/java/com/smart/rag/infrastructure/algorithm/graph/WeightedGraph.java` |
| AdjacencyListGraph.java | 实现 | `src/main/java/com/smart/rag/infrastructure/algorithm/graph/AdjacencyListGraph.java` |
| LouvainCommunityDetector.java | 算法 | `src/main/java/com/smart/rag/infrastructure/algorithm/graph/LouvainCommunityDetector.java` |
| AdjacencyListGraphTest.java | 单测 | `src/test/java/com/smart/rag/infrastructure/algorithm/graph/AdjacencyListGraphTest.java` |
| LouvainCommunityDetectorTest.java | 单测 | `src/test/java/com/smart/rag/infrastructure/algorithm/graph/LouvainCommunityDetectorTest.java` |
| pom.xml | 修改 | 添加 fastutil 依赖（scope: compile） |

## Spec References

- `.trellis/spec/backend/quality-guidelines.md`——Java 21 风格、SOLID 原则、Forbidden Patterns。
- `.trellis/spec/guides/code-reuse-thinking-guide.md`——实现前搜索现有代码（本子任务为全新包，无现有代码可复用）。
- `docs/design/entity-centric-retrieval.md` §5.2 ①②③——Java 骨架、算法公式、复杂度预算。

## Rollback Points

- Phase 1 失败（fastutil 引入）：回退 pom.xml，不建包。
- Phase 2-4 失败：删除 `infrastructure/algorithm/` 整个目录 + pom.xml 中 fastutil 依赖。零业务影响——Wave 0 无下游依赖。
