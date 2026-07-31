# Implementation Plan — Entity-Centric Retrieval (Parent)

本文件给出子任务执行排序与集成/复核门。各子任务有自己的 `implement.md`（ordered checklist + validation）。父任务不直接写生产代码，除非集成测试需要。

## Execution Waves（按依赖波次推进）

### Wave 0（并行启动，三者互不依赖）
- [x] `ecr-db-migration` — V21 schema 落地，clean DB 迁移通过
- [x] `ecr-graph-algorithm` — Leiden 在 Zachary Karate Club ground truth 通过
- [x] `ecr-retrieval-path-abstraction` — HybridSearchService 重构后既有 Path A/B 测试全绿（行为保持）

**Gate 0**：三者完成后，主干 `agentic-rag-dev` 应处于：迁移可应用、图算法单测绿、hybridSearch 既有行为零回归。任一未过不得进入 Wave 1。

> **执行记录（2026-07-31）**：Wave 0 三项均已完成并提交（`60ab5a3` retrieval-path-abstraction；graph-algorithm 随 `615190d` 落地 Louvain，`079e31e` 之后以 Leiden 替换，见 graph-algorithm implement.md 变更记录；db-migration 含于 `615190d` 前的 V21 提交）。Gate 0 通过。

### Wave 1（依赖 Wave 0）
- [x] `ecr-extraction-pipeline`（依赖 db-migration）— ingest 文档后四表 populated、degree 正确、delete/supersede 清理无孤儿

**Gate 1**：离线索引端到端可用（实体/event/chunk_entity 落库 + embedding）。

### Wave 2（依赖 Wave 0 + Wave 1）
- [x] `ecr-structure-scores`（依赖 db-migration + graph-algorithm + extraction-pipeline）— 共现图投影正确、weak_tie 手算值匹配、bridge 正确、CommunityDetectionJob 端到端 + clearStaleFlag 全量清除

**Gate 2**：离线结构分可计算且写入 rag_entity 列。

### Wave 3（依赖 Wave 0 + Wave 1 + Wave 2 列读取）
- [x] `ecr-path-c-retrieval`（依赖 db-migration + retrieval-path-abstraction + extraction-pipeline；读取 structure-scores 列）— EntityRetrievalPath 注册、frontier 融合排序、投票回链、SAG H=1 扩展、trace 输出、entity.enabled=false 时零回归

**Gate 3**：Path C 端到端在线可用。

## Integration Review Gate（父任务最终复核，子任务全部 archive 前）

- [ ] 跨子任务集成测试通过（父 design.md "Integration Test Strategy"）
- [ ] AC1-AC5（父 prd.md）逐条验证
- [ ] `entity.enabled=false` 全量既有测试套件绿色（零回归证明）
- [ ] 跨用户隔离测试通过
- [ ] supersede 级联清理测试通过
- [ ] 设计原则合规复核（父 design.md 表）——无子任务越界

## Validation Commands（父任务层，集成验证用）

```bash
# 迁移 + 全量测试（零回归基线）
./mvnw flyway:info && ./mvnw test -Pdefault

# entity 开关对照
./mvnw test -Dtest='*HybridSearch*' -Dargs=app.rag.entity.enabled=false   # 既有行为
./mvnw test -Dtest='*EntityRetrieval*' -Dargs=app.rag.entity.enabled=true  # Path C
```

（具体测试名/ profile 由各子任务 implement.md 落实；父任务层只校验开关二态。）

## Rollback Points

- 任一 Wave gate 未过：该 Wave 产出的 commit 可独立 revert（子任务边界即回滚边界）。
- V21 迁移 down SQL 提供数据层兜底。
- `entity.enabled=false` 是运行时即时回滚开关。
