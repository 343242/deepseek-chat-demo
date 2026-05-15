# RAG 评估系统代码审查报告

**审查日期**: 2026-05-16
**分支**: eval-rag-dev
**审查范围**: `src/main/java/com/demo/chat/rag/evaluation/` 全部 20 个文件
**对照标准**: design.md spec + ECC java-coding-standards + springboot-patterns + springboot-verification

---

## 审查总结

| 级别 | 数量 | 说明 |
|------|------|------|
| 🔴 P0 Blocker | 6 | 必须修复，影响正确性/安全性 |
| 🟠 P1 Important | 9 | 应该修复，影响可维护性/健壮性 |
| 🟡 P2 Minor | 7 | 建议改进 |

---

## 🔴 P0 Blocker（必须修复）

### P0-1: EvaluationRunController.startRun() 串行执行无事务保护

**文件**: `EvaluationRunController.java:68-105`

Controller 直接在 HTTP 请求线程中串行执行所有评估项，无事务保护：
1. Run 记录创建和评估项执行不是原子操作——如果中途失败，Run 记录留在 `running` 状态永不结束
2. 单条评估失败只 `failCount++`，但 Run 状态最终都标记 `completed`——部分失败应该是 `partial` 或 `failed`
3. 如果 50 条数据项评估到第 30 条时 OOM 或超时，前 29 条结果已写入但 Run 永远停在 `running`

**违反**: springboot-patterns > Service Layer with Transactions（Controller 不应包含业务逻辑）
**正确做法**: 抽取 `EvaluationExecutionService`，run 记录创建 + 评估执行 + 状态更新放在 Service 层，评估失败时 Run 标记为 `failed` 并记录已完成的条目数

### P0-2: DatasetRepository.insertItems() 未关闭 PreparedStatement 和 ResultSet

**文件**: `DatasetRepository.java:138-168`

```java
public List<EvaluationDatasetItem> insertItems(List<EvaluationDatasetItem> items) {
    jdbc.execute((Connection conn) -> {
        PreparedStatement ps = conn.prepareStatement(...);
        for (EvaluationDatasetItem item : items) {
            // ... set parameters ...
            var rs = ps.executeQuery();  // ← 每个 item 创建新的 ResultSet，从未关闭
            if (rs.next()) {
                item.setId(rs.getLong("id"));
            }
            // rs.close() 从未调用!
        }
        return null;
    });
```

循环内 `ps.executeQuery()` 产生多个 ResultSet 但从未 `close()`。对于 50+ 条数据项，PostgreSQL 连接会累积未关闭的 ResultSet 资源。

**违反**: java-coding-standards > Resource Management
**正确做法**: 每次 executeQuery 后 `rs.close()`，或使用 try-with-resources

### P0-3: Controller 使用 `Map<String, Object>` 作为请求体，无类型安全

**文件**: `DatasetController.java:41`, `EvaluationRunController.java:45`

```java
@PostMapping("/generate")
public ResponseEntity<Map<String, Object>> generateDataset(
        @RequestBody Map<String, Object> request) {
    Long datasetId = ((Number) request.get("datasetId")).longValue();  // NPE if missing
```

- 无 Bean Validation（`@NotNull`, `@NotBlank`）
- `datasetId` 缺失时直接 NPE（`((Number) null).longValue()`）
- 强制类型转换不安全

**违反**: springboot-patterns > DTOs and Validation
**正确做法**: 创建 DTO 类（如 `GenerateDatasetRequest`），加 `@Valid @RequestBody` + Bean Validation 注解

### P0-4: extractJson() 方法在 5 个类中重复实现

**文件**: `LlmJudgeImpl.java`, `DatasetGenerator.java`, `FaithfulnessScorer.java`, `ContextRecallScorer.java`, `ContextRelevanceScorer.java`

完全相同的 JSON 提取逻辑（三层容错：raw → ```json``` → 正则）复制粘贴了 5 次。部分版本还少了一层 `[...]` 提取逻辑（ContextRelevanceScorer 缺少 `[...]` 分支）。

**违反**: java-coding-standards > Code Smells to Avoid > DRY
**正确做法**: 抽取到 `JsonExtractor` 工具类，所有消费者引用同一个实现

---

## 🟠 P1 Important（应该修复）

### P1-1: EvaluationRunner 构造器注入 12 个依赖

**文件**: `EvaluationRunner.java:59-76`

```java
public EvaluationRunner(VectorStore vectorStore,
                        JdbcTemplate jdbcTemplate,
                        RagRetrievalProperties properties,
                        ParentDocumentPostProcessor parentProcessor,
                        QueryNormalizer queryNormalizer,
                        QueryTransformer queryTransformer,
                        ChatClient.Builder chatClientBuilder,
                        EvaluationProperties evalProps,
                        RetrievalMetricsCalculator metricsCalculator,
                        GenerationMetricsCalculator generationMetricsCalculator,
                        ObjectMapper objectMapper,
                        DatasetRepository datasetRepo) { ... }
```

12 个构造器参数，明显违反 SRP。EvaluationRunner 同时承担了：Pipeline 编排、检索指标计算、生成指标计算、LLM 调用。

**违反**: springboot-patterns > REST API Structure（单一职责）
**正确做法**: 将依赖分组为辅助类，如 `PipelineExecutor`（负责 Pipeline 编排）、`EvaluationContext`（负责配置快照）

### P1-2: EvaluationRunner.copyWithOverride() 使用手写属性拷贝

**文件**: `EvaluationRunner.java:211-240`

手动复制 `RagRetrievalProperties` 的每个字段（14 行 `copy.setXxx(original.getXxx())`）。新增字段时极易遗漏。

**违反**: java-coding-standards > Code Smells > Fragile code
**正确做法**: 使用 BeanUtils.copyProperties() 或在 RagRetrievalProperties 上添加 `copy()` 方法

### P1-3: EvaluationRunController.startRun() 缺少 datasetId 为 null 的防御

**文件**: `EvaluationRunController.java:47`

```java
Long datasetId = ((Number) request.get("datasetId")).longValue();
```

如果请求体中没有 `datasetId` 字段，`request.get("datasetId")` 返回 null，`((Number) null).longValue()` 抛 NPE。

### P1-4: DatasetRepository.insertItem() PreparedStatement 未关闭

**文件**: `DatasetRepository.java:107-131`

与 P0-2 类似，`insertItem()` 中的 PreparedStatement 和 ResultSet 也未关闭。虽然单条操作影响较小，但不一致。

### P1-5: EvaluationRunController.buildEvalConfig() 缺少 Boolean 安全转换

**文件**: `EvaluationRunController.java:205-225`

```java
if (override.containsKey("rerankEnabled"))
    config.setRerankEnabled((Boolean) override.get("rerankEnabled"));
```

Jackson 反序列化 `Map<String, Object>` 时，Boolean 值是安全的，但如果客户端传 `"true"` (String)，这里会 ClassCastException。

---

## 🟡 P2 Minor（建议改进）

### P2-1: EvaluationDataset 和 EvaluationDatasetItem 应使用 record 或 @Data

**文件**: `EvaluationDataset.java`, `EvaluationDatasetItem.java`

手写了大量 getter/setter（50+ 行样板代码），与项目其他实体风格不一致。Jackson ObjectMapper 的 record 支持已经成熟。

### P2-2: V11 migration 缺少注释说明与设计文档关联

**文件**: `V11__rag_evaluation.sql`

设计文档中定义了 `updated_at` 的自动更新触发器，但 migration 中只有 `DEFAULT NOW()` 没有 `ON UPDATE` 触发器。`updateDatasetItemCount` 手动调了 `NOW()`，但其他 UPDATE 操作可能遗忘。

### P2-3: LlmJudgeImpl.judgeModel 字段赋值后未使用

**文件**: `LlmJudgeImpl.java:43-45`

```java
this.judgeModel = props.getJudgeModel();
this.judgeClient = builder.build();  // ← 未使用 judgeModel 设置 model
```

`judgeModel` 赋值后从未在 ChatClient 调用中使用。ChatClient.Builder 会使用 Spring AI 默认模型，不会自动切换到 judgeModel。

**这是一个正确性 Bug**：Judge 实际用的不是配置中指定的 judge 模型！

### P2-4: DatasetGenerator.sampleChunks() 使用字符串拼接构建 JSON filter

**文件**: `DatasetGenerator.java:101-107`

```java
String filterJson = "{\"userId\": \"" + userId + "\"}";
```

如果 userId 包含特殊字符（如 `"`），JSON 注入风险。应使用 ObjectMapper 构建。

---

---

## 📌 Spec 增量审查（2026-05-16 第二轮，按 `.trellis/spec/` 全量 spec）

> 对照 spec/backend（database-guidelines、error-handling、quality-guidelines、logging-guidelines、directory-structure）+ spec/guides（code-reuse、cross-layer）逐项审查，去除与上方已有的重复项。

### P0-5: Repository 用 JdbcTemplate 而非项目标准 MyBatis-Plus

**文件**: `DatasetRepository.java`, `EvaluationResultRepository.java`

> 违反 spec database-guidelines > MyBatis-Plus 使用

项目已有 `SysUserMapper` 等 BaseMapper 规范。评估模块的 Repository 全部用 `JdbcTemplate` 手写 SQL，与项目既有模式不一致。

- 如果评估模块有意用 JdbcTemplate（设计文档提到零侵入、独立模块），应在 spec 里补充说明
- 如果不是有意为之，应迁移到 MyBatis-Plus Mapper

### P0-6: 实体类无 MyBatis-Plus 注解

**文件**: `EvaluationDataset.java`, `EvaluationDatasetItem.java`, `EvaluationRun.java`, `EvaluationResult.java`

> 违反 spec database-guidelines > Entity：需要 `@TableName`、`@TableId`、`@TableField`

四个实体都是纯 POJO（getter/setter），没有 `@TableName`/`@TableId` 注解。与 P0-5 关联——如果坚持 JdbcTemplate，需文档化决策；否则迁移时需要补注解。

---

### P1-6: DatasetGenerator.generate() 串行逐条 LLM 调用

**文件**: `DatasetGenerator.java:55-100`

> 违反 spec quality-guidelines > Design Principles > 性能考虑

50 个采样 chunk × `questionsPerChunk`(2) = 100 次 LLM 调用，全部串行执行。建议：
- 使用 `CompletableFuture` + `Semaphore` 控制并发
- 或先批量生成再入库

### P1-7: Controller 无权限控制

**文件**: `DatasetController.java`, `EvaluationRunController.java`

> 违反 spec quality-guidelines > Security Checklist：`@PreAuthorize` 注解保护接口

评估模块 REST API 完全无权限校验。任何人可以：
- 触发评估运行（消耗大量 LLM Token）
- 导出数据集内容
- 删除数据集

### P1-8: EvaluationProperties 用 `@Component` 注册

**文件**: `EvaluationProperties.java`

> 项目既有模式：`@ConfigurationProperties` + `@EnableConfigurationProperties` 或 `@ConfigurationPropertiesScan`

`@Component` 注册 `@ConfigurationProperties` 虽然能工作，但不是 Spring Boot 推荐方式。且 `EvaluationConfig` 中已手动创建 Bean，容易遗忘。

### P1-9: evaluation_run.status 用 VARCHAR 无枚举约束

**文件**: `V11__rag_evaluation.sql`, `EvaluationRun.java`

> 违反 spec quality-guidelines > Security Checklist > 状态枚举：用枚举类约束，不接受裸 Integer/String

SQL 中 `status VARCHAR(20) DEFAULT 'pending'` 无 CHECK 约束，Java 侧也无对应枚举。可写入任意字符串。建议：
- SQL 侧加 `CHECK (status IN ('pending','running','completed','failed'))`
- Java 侧定义 `EvaluationRunStatus` 枚举 + `@EnumValue` / `@JsonValue`

### P1-10: P0-3 的 datasetId 缺失场景同样存在于 EvaluationRunController

**文件**: `EvaluationRunController.java:45`

与 P1-3 同源问题，但 `startRun()` 的 `datasetId` 缺失时直接 NPE，且该接口会触发大量 LLM 调用和 Token 消耗，影响比 DatasetController 更严重。

---

### P2-5: DatasetExporter.exportAsJson() 抛 RuntimeException

**文件**: `DatasetExporter.java:28`

> 违反 spec error-handling > Rules > ❌ DON'T：不要抛 RuntimeException

```java
throw new RuntimeException("Failed to export dataset", e);
```

应使用项目已有的 `BusinessException` 或自定义 `EvaluationException`。

### P2-6: 零单元测试

> 违反 spec quality-guidelines > Quality Check

25 个 Java 文件、0 个测试。至少应对以下写单元测试：
- `RetrievalMetricsCalculator` — 纯计算逻辑，无外部依赖
- `GenerationMetricsCalculator` — 编排逻辑
- `extractJson()` 抽取后的 `JsonExtractor` 工具类

### P2-7: PipelineInstrumenter 中 StageSnapshot JSON 序列化失败静默吞异常

**文件**: `PipelineInstrumenter.java`

`StageSnapshot` 的 JSON 序列化如果失败，catch 后继续执行（`debug` 级别日志），可能丢失重要的中间状态调试信息。对于评估系统，中间状态的完整性至关重要。

---

## Spec 符合性补充检查

| 检查项 | 结果 | 说明 |
|--------|------|------|
| MyBatis-Plus 统一 | ❌ | Repository 用 JdbcTemplate，需文档化决策或迁移 |
| Entity 注解规范 | ❌ | 无 @TableName/@TableId，与项目不一致 |
| 权限控制 | ❌ | 评估 API 无 @PreAuthorize（P1-7） |
| 状态枚举 | ❌ | status VARCHAR 无枚举约束（P1-9） |
| 异常规范 | ❌ | DatasetExporter 抛 RuntimeException（P2-5） |
| 单元测试 | ❌ | 0 测试（P2-6） |
| 日志级别 | ✅ | SLF4J debug/warn/info 分层正确 |
| 时间类型 | ✅ | TIMESTAMPTZ + OffsetDateTime |
| Flyway 命名 | ✅ | V11 连续编号 |
| SQL 列命名 | ✅ | snake_case |
| ISP/OCP | ✅ | LlmJudge 接口分离、Scorer 独立扩展 |

## 修正优先级建议（更新）

1. **P2-3 → 升级为 P0**: judgeModel 未生效是正确性 Bug（已有）
2. **P0-5 + P0-6**: Repository 模式决策——文档化或迁移 MyBatis-Plus
3. **P0-2 + P0-3 + P0-4**: 一起修——资源管理 + DTO + 工具类抽取（已有）
4. **P0-1 + P0-新无事务**: 抽取 Service 层 + 事务保护（已有）
5. **P1-7**: 评估 API 权限控制（新增）
6. **P1-6**: DatasetGenerator 并发优化（新增）
7. **P1-9**: status 枚举约束（新增）
8. **P1-1**: Runner 拆分可在后续迭代（已有）

| 检查项 | 结果 | 说明 |
|--------|------|------|
| 零侵入 | ✅ | 0 修改现有文件，完全符合 |
| Profile 隔离 | ✅ | `@Profile("evaluation")` + `@ConditionalOnProperty` |
| Spec 一致性 | ⚠️ | 大体符合，但缺少 JudgePrompt 模板类（spec 中定义了独立类） |
| Flyway 命名 | ✅ | V11 连续编号 |
| REST API 路径 | ✅ | 与 spec 定义一致 |
| 数据模型 | ✅ | 4 表结构与 spec 完全对应 |
| 检索指标公式 | ✅ | 5 个指标公式与 spec 一致 |
| 生成指标方法 | ✅ | 两步 Faithfulness、embedding Answer Relevance 等 |
| LLM-as-Judge 分离 | ❌ | judgeModel 配置了但未实际使用（P2-3，实际是 P0 级别的正确性问题）|

---


