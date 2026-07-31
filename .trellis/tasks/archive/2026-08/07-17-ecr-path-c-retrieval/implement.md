# Implementation Plan — Path C 在线检索（ecr-path-c-retrieval）

## 前置依赖确认

本子任务位于 **Wave 3**，开始前须确认：
- [ ] `ecr-db-migration`（Wave 0）：V21 schema 已应用，四表存在
- [ ] `ecr-retrieval-path-abstraction`（Wave 0）：`RetrievalPath` 接口 + `ScoredDocument` record + `HybridSearchService` 重构完成，Path A/B 适配器就位，既有测试全绿
- [ ] `ecr-extraction-pipeline`（Wave 1）：`EntityMapper` 接口 + `EntityMapper.xml` 已创建（含写入方法），本子任务在其上追加查询方法

## Ordered Checklist

### Phase 1：配置层（无 Java 业务依赖，可先完成）

- [ ] **1.1** 新增 `EntityRetrievalProperties` 嵌套 record
  - 文件：`src/main/java/com/smart/rag/rag/config/RagRetrievalProperties.java`
  - 在主 record 末尾新增字段 `EntityRetrievalProperties entity`
  - 在主 record 内部定义 `public record EntityRetrievalProperties(...)` （13 字段）
  - compact constructor 校验：matchThreshold <= 0 → 0.85，frontierBudget <= 0 → 50，chunkTopK <= 0 → 20，expandChunkTopK < 0 → 10，expansionHops < 0 → 1，expansionDecay 范围校验，α/β/γ >=0 且不全为 0，descriptionMaxChars <= 0 → 500
  - 遵循 `backend/database-guidelines.md` 配置绑定规范

- [ ] **1.2** 更新 `withOverrides` 方法
  - 文件：同上
  - 现有签名 `withOverrides(Integer vectorTopKOverride, Integer bm25TopKOverride, Integer rrfKOverride)`
  - 新增构造参数 `entity`（第 16 个参数，原值透传）
  - 参考 spec `backend/code-review-checklist.md` 验证不遗漏参数

- [ ] **1.3** 更新 `RagRetrievalPropertiesTest`
  - 文件：`src/test/java/com/smart/rag/rag/config/RagRetrievalPropertiesTest.java`
  - 新增 @Nested `EntityPropertiesTest` 测试组：
    - 默认值全部正确（enabled=false, matchThreshold=0.85, frontierBudget=50, ...）
    - α/β/γ < 0 抛 IllegalArgumentException
    - α+β+γ == 0 抛 IllegalArgumentException
    - expansionDecay 范围校验（<=0 或 >1 回退 0.7）
    - `withOverrides` 正确透传 `entity` 原值

- [ ] **1.4** 更新 `application.yml`
  - 文件：`src/main/resources/application.yml`
  - 在 `app.rag:` 下新增 `entity:` 嵌套块（15 个配置项，默认 `enabled: false`）
  - 参考 §7.2 完整 YAML

- [ ] **1.5** 验证配置层
  - 命令：`./mvnw test -Dtest="RagRetrievalPropertiesTest" -pl .`
  - 预期：既有 3 个 @Nested + 新增 EntityPropertiesTest 全绿

### Phase 2：Mapper 查询方法（依赖 extraction-pipeline 的 EntityMapper 接口已创建）

- [ ] **2.1** 在 `EntityMapper` 接口新增查询方法
  - 文件：`src/main/java/com/smart/rag/rag/mapper/EntityMapper.java`
  - **注意**：此接口归属 `ecr-extraction-pipeline`，本子任务仅追加方法，不修改已有方法签名
  - 新增方法：
    - `List<ScoredEntity> findFrontierEntities(@Param("seedEmbeddings") float[][] seedEmbeddings, @Param("matchThreshold") double matchThreshold, @Param("userId") long userId, @Param("teamId") Long teamId, @Param("frontierBudget") int frontierBudget, @Param("alpha") double alpha, @Param("beta") double beta, @Param("gamma") double gamma)`
    - `List<VotedChunk> voteBacklinkChunks(@Param("entityIds") List<Long> entityIds, @Param("chunkTopK") int chunkTopK, @Param("userIdStr") String userIdStr)`
    - `List<ExpandedChunk> expandChunks(@Param("entityIds") List<Long> entityIds, @Param("expansionDecay") double expansionDecay, @Param("expandChunkTopK") int expandChunkTopK, @Param("userId") long userId, @Param("teamId") Long teamId, @Param("userIdStr") String userIdStr)`

- [ ] **2.2** 新增 result DTO records
  - 文件：`src/main/java/com/smart/rag/rag/retrieval/entity/ScoredEntity.java`
  - 字段：`long id, String nameDisplay, double queryRelevance, double bridge, double weakTie, int degree, double queryRelNorm, double bridgeNorm, double weakTieNorm, double compositeScore`
  - 文件：`src/main/java/com/smart/rag/rag/retrieval/entity/VotedChunk.java`
  - 字段：`UUID chunkId, String content, String metadata, double chunkScore, String[] votedByEntities`
  - 文件：`src/main/java/com/smart/rag/rag/retrieval/entity/ExpandedChunk.java`
  - 字段：`UUID chunkId, String content, String metadata, double chunkScore, Long[] discoveredViaEntities`

- [ ] **2.3** 实现 `EntityMapper.xml` 查询 SQL
  - 文件：`src/main/resources/mapper/EntityMapper.xml`
  - **fusion SQL**（§6.2）：3 个 CTE（`seed_embeddings`/`entity_match`/`scored` → `frontier`），window-max 归一化
  - **vote SQL**（§6.3）：frontier JOIN rag_chunk_entity JOIN vector_store，GROUP BY chunk_id，max + array_agg
  - **expansion SQL**（§6.4）：3 个 CTE（`seed_events`/`expanded_entities`/`expanded_events`），衰减因子 δ

- [ ] **2.4** 验证 Mapper SQL 语法
  - 命令：`./mvnw compile -pl .`（MyBatis XML 语法校验）

### Phase 3：核心组件（依赖 Phase 1 + Phase 2）

- [ ] **3.1** `EntitySeedExtractor`
  - 文件：`src/main/java/com/smart/rag/rag/retrieval/entity/EntitySeedExtractor.java`
  - 职责：query → LLM ChatCapable 调用 → 解析返回 JSON → `List<String>` seed entities
  - 依赖：`LlmClientRegistry` + `RagRetrievalProperties.EntityRetrievalProperties`
  - 遵循 `backend/llm-spi.md`：通过 registry 获取 ChatCapable，不直接注入 ChatClient.Builder
  - Prompt 模板：复用 §4.2 抽取 prompt 的 entities 部分（只取 entities，不取 event）
  - 异常处理：LLM 调用失败返回空列表（不阻塞 query，Path A/B 仍可召回）

- [ ] **3.2** `EntityFrontierRanker`
  - 文件：`src/main/java/com/smart/rag/rag/retrieval/entity/EntityFrontierRanker.java`
  - 职责：seed entities → embed（复用 embedding 机制） → `EntityMapper.findFrontierEntities()`
  - 依赖：`EntityMapper`、`EmbeddingModel`（Spring AI @Primary）、`EntityRetrievalProperties`
  - **消融开关处理**：`weakTieEnabled=false` 时传入 `gamma=0`；`communityDetectionEnabled=false` 时传入 `beta=0`

- [ ] **3.3** `EntityVoteRetriever`
  - 文件：`src/main/java/com/smart/rag/rag/retrieval/entity/EntityVoteRetriever.java`
  - 职责：frontier entities → `EntityMapper.voteBacklinkChunks()` → `List<ScoredDocument>`
  - 依赖：`EntityMapper`
  - 结果转换：`VotedChunk` → `ScoredDocument(Document.from(content+metadata), rank, chunkScore)`

- [ ] **3.4** `EntityExpansionRetriever`
  - 文件：`src/main/java/com/smart/rag/rag/retrieval/entity/EntityExpansionRetriever.java`
  - 职责：frontier entities → `EntityMapper.expandChunks()` → `List<ScoredDocument>`
  - 依赖：`EntityMapper`
  - **expansionHops=0 禁用**：方法入口检查 `expansionHops <= 0` 时直接返回空列表，不执行 SQL
  - 结果转换：`ExpandedChunk` → `ScoredDocument(Document.from(content+metadata), rank, chunkScore)`

### Phase 4：编排与注册（依赖 Phase 3 全部组件）

- [ ] **4.1** `EntityRetrievalPath`
  - 文件：`src/main/java/com/smart/rag/rag/retrieval/path/EntityRetrievalPath.java`
  - `@Component @ConditionalOnProperty(prefix="app.rag.entity", name="enabled", havingValue="true")`
  - `implements RetrievalPath`
  - `name()` = "entity"，`rrfWeighting()` = `RrfWeighting.SCORE_WEIGHTED`
  - `search(query, userId, teamId)`：
    1. `EntitySeedExtractor.extract(query)` → seed entities
    2. `EntityFrontierRanker.rank(seedEntities, userId, teamId)` → frontier
    3. `ScopedTasks.fork("vote", ...)` → `EntityVoteRetriever.retrieve(frontier, userId)`
    4. `ScopedTasks.fork("expand", ...)` → `EntityExpansionRetriever.retrieve(frontier, userId, teamId)`
    5. `ScopedTasks.join()`
    6. 合并去重（按 chunkId，取 max chunkScore）+ 重新排序
    7. 输出 trace JSON（log.info，6 步 + totalDurationMs）
    8. 返回 `List<ScoredDocument>`

### Phase 5：测试

- [ ] **5.1** EntitySeedExtractor 单测
  - Mock `ChatCapable`，验证 JSON 解析正确
  - 验证 LLM 失败时返回空列表（不阻塞）

- [ ] **5.2** EntityFrontierRanker 单测（关键：window-max 归一化手算验证）
  - Fixture：3 个 entity，query_rel=[0.9, 0.5, 0.1], bridge=[0, 3, 6], weak_tie=[0.5, 0.8, 0.2]
  - 预期归一化：query_rel_norm=[1.0, 0.556, 0.111], bridge_norm=[0, 0.5, 1.0], weak_tie_norm=[0.625, 1.0, 0.25]
  - 预期 composite_score（α=0.5, β=0.3, γ=0.2）：
    - entity_1: 0.5×1.0 + 0.3×0 + 0.2×0.625 = 0.625
    - entity_2: 0.5×0.556 + 0.3×0.5 + 0.2×1.0 = 0.578
    - entity_3: 0.5×0.111 + 0.3×1.0 + 0.2×0.25 = 0.456
  - 消融测试：`weakTieEnabled=false` 时 entity_3 composite_score = 0.5×0.111 + 0.3×1.0 + 0 = 0.356

- [ ] **5.3** EntityExpansionRetriever H=0 禁用测试
  - 验证 `expansionHops=0` 时返回空列表、不调用 mapper

- [ ] **5.4** EntityRetrievalPath 编排集成测试
  - Mock 4 个组件，验证 PC4a/PC4b 并行 + 合并去重
  - 验证 trace JSON 结构

- [ ] **5.5** `entity.enabled=false` 回归测试
  - 验证 `List<RetrievalPath>` 不含 name="entity" 的实例
  - 验证 HybridSearchService 既有测试全绿
  - 命令：`./mvnw test -Dtest="*HybridSearch*" -Dspring.profiles.active=default`

## Review Gates

| Gate | 验证点 | 命令 |
|---|---|---|
| 配置编译 | `EntityRetrievalProperties` record 编译 + 测试全绿 | `./mvnw test -Dtest="RagRetrievalPropertiesTest"` |
| Mapper 编译 | XML + Java 接口匹配 | `./mvnw compile` |
| 融合归一化 | fixture 手算验证通过 | 单测 `EntityFrontierRankerTest` |
| Feature-flag off | Path C Bean 不存在，既有测试零回归 | `./mvnw test -Dtest="*HybridSearch*"` |
| 全量测试 | 无破坏 | `./mvnw test` |

## Rollback Points

- **代码层**：`entity.enabled=false` 即时关闭 Path C（Bean 不创建）
- **Phase 回滚**：各 Phase 独立提交，可按 commit revert
- **数据层**：无数据迁移（本子任务不修改 DB schema）

## Spec References

- `backend/llm-spi.md` — EntitySeedExtractor LLM 调用合规
- `backend/code-review-checklist.md` — withOverrides 参数完整性校验
- `guides/cross-layer-thinking-guide.md` — 配置层→组件层→mapper 层跨层数据流
- `guides/code-reuse-thinking-guide.md` — 复用 EmbeddingModel @Primary、复用 ScopedTasks fork/join
