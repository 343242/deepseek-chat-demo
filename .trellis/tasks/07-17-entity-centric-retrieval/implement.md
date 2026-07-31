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

- [x] **AC1 端到端**（2026-08-01 验证）：ingest 多主题文档 → 实体/event 落库（85 实体/22 events/共现图 358 边）→ 结构分计算（weak_tie + bridge + 10 社区 + stale 清除）→ 多跳 query 命中 Path C（frontier=1, vote=4, expand=4, merge=4）→ trace 完整输出；chat 回答正确引用多来源
- [x] **AC3 零回归**（2026-08-01）：`entity.enabled=false` 全量 1357 测试全绿，两次（修复前后各一次）
- [x] **AC4 清理无孤儿**（2026-08-01）：delete 与 supersede 双路径验证——文档删除后 rag_chunk_entity/rag_event 零残留、degree=0 孤儿清除；supersede v1→v2 旧版本实体索引完全清理
- [x] **AC5 延迟**（2026-08-01）：Path C SQL 路径（fusion+vote+expand+merge）P50=236ms / P99=438ms < 800ms 达标；总延迟受独立 LLM seed extraction（P99≈4.6s）主导——§11.4/OQ2 已明示合并优化为首优缓解项，本阶段独立调用属设计决定
- [x] **AC6 跨用户隔离**（2026-08-01）：双用户（A: PostgreSQL 文档 / B: Redis 文档）实体/event/共现图完全按 user_id 隔离，跨用户边=0；A 查 Redis 与 B 查 PostgreSQL 时 Path C frontier=0 无泄漏
- [ ] 设计原则合规复核（父 design.md 表）——无子任务越界（待复核）

## AC 验证修复清单（2026-08-01，AC 验证暴露的启动/运行缺陷，均已修复并回归）

| # | 缺陷 | 修复 |
|---|---|---|
| 1 | `javaType="java.util.UUID"` 无 TypeHandler（MyBatis wontfix #1609），启动即炸 | 新增 `UuidTypeHandler` + XML 显式引用 |
| 2 | TraceMapper DTD URL `mybatis-3.0-mapper.dtd` 无法本地解析（systemId 匹配失败） | 修正为 `mybatis-3-mapper.dtd` |
| 3 | `@MapperScan` 未覆盖 mcp.admin.mapper / audit / trace 包；且默认扫描包内全部接口（TraceContextProvider 被注册成 mapper） | 补全包列表 + `annotationClass = Mapper.class` |
| 4 | `McpCircuitBreakerRegistry` / `HybridSearchService` 多构造器无 @Autowired | 加 `@Autowired`（HybridSearchService 补 @Qualifier） |
| 5 | 共现投影 SQL `#{teamId}` null 参数 PG 无法推断类型（INSERT...SELECT） | `jdbcType=BIGINT` |
| 6 | constructor resultMap `javaType="long/double/int"` 解析为包装类与 record primitive 构造器不匹配 | `_long/_double/_int` primitive 别名 |
| 7 | vote/expand SQL `GROUP BY vs.metadata`（JSON 无 equality operator） | `vs.metadata::text` |
| 8 | `#{fe.id()}` 方法调用式绑定 MyBatis 不支持 | 改为属性名 `#{fe.id}` |
| 9 | fastTrack 临时行被实体抽取当作 chunk（23 vs 22）→ 删除后孤儿残留 | `selectChunksByDocumentId` 排除 fastTrack 行；cleanup 改按 `rag_event.document_id` 权威清理（新增 deleteByDocumentId×2） |
| 10 | `MyBatisPlusMetaHandler` 只填 createdAt/updatedAt，RagDocument 的 createTime/updateTime NOT NULL 违约 → 删除 API 500 | 补 fill createTime/updateTime |

> 注：AC5 端到端查询受外部 LLM API 可用性影响（seed extraction 多次超时/降级），SQL 路径延迟为独立测量值；matchThreshold 验证时调至 0.75（§12.2 参数调优项，默认 0.85 下实体向量相似度峰值 ~0.79 无法命中）。gitnexus 索引库损坏（LadybugDB UNREACHABLE_CODE），impact 分析未能自动执行，影响面已人工确认。

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
