# RAG 评估系统代码审查报告

**审查日期**: 2026-05-16（第二轮打磨：对照 README.md 技术栈）
**分支**: eval-rag-dev
**审查范围**: `src/main/java/com/demo/chat/rag/evaluation/` 全部 25 个文件 + SQL + yml
**对照标准**: design.md spec + `.trellis/spec/backend/` 全量 + `spec/guides/` + README.md 技术栈

---

## 审查总结

| 级别 | 数量 | 说明 |
|------|------|------|
| 🔴 P0 Blocker | 6 | 必须修复，影响正确性/安全性 |
| 🟠 P1 Important | 9 | 应该修复，影响可维护性/健壮性 |
| 🟡 P2 Minor | 7 | 建议改进 |

> **技术栈基线**（来自 README.md）：Spring Boot 3.5.14 / Spring AI 1.1.6 / MyBatis-Plus 3.5.16 / PostgreSQL 18 (pgvector) / Redis 8.2 / Flyway / Spring Security (RBAC) / Log4j 2 / 三厂商 Provider（DeepSeek + 智谱 + MiniMax）

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

**违反**: spec quality-guidelines > DTO Rules（`{动作}Request` + `@Valid`）
**对比项目标准**: 项目既有 Controller（AuthController、UserController、RoleController）均使用强类型 DTO + `@Valid @RequestBody`
**正确做法**: 创建 `GenerateDatasetRequest` / `StartRunRequest` DTO，加 `@Valid @RequestBody` + Bean Validation 注解

### P0-4: extractJson() 方法在 5 个类中重复实现

**文件**: `LlmJudgeImpl.java`, `DatasetGenerator.java`, `FaithfulnessScorer.java`, `ContextRecallScorer.java`, `ContextRelevanceScorer.java`

完全相同的 JSON 提取逻辑（三层容错：raw → ```json``` → 正则）复制粘贴了 5 次。部分版本还少了一层 `[...]` 提取逻辑（ContextRelevanceScorer 缺少 `[...]` 分支）。

**违反**: spec guides/code-reuse-thinking-guide > Pattern 1: Copy-Paste Functions
**对比项目标准**: 项目已有 `common/` 公共模块放工具类（雪花 ID 等），extractJson 应同样放 `common/`
**正确做法**: 抽取到 `com.demo.chat.common.util.JsonExtractor`，所有消费者引用同一个实现

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

### P2-1: 实体应使用 Lombok 或 MyBatis-Plus 注解对齐项目标准

**文件**: `EvaluationDataset.java`, `EvaluationDatasetItem.java`, `EvaluationRun.java`, `EvaluationResult.java`

手写了大量 getter/setter（100+ 行样板代码）。

**对比项目标准**: README 确认使用 MyBatis-Plus 3.5.16。项目既有实体（SysUser、SysRole 等）使用 `@TableName` + `@TableId` + Lombok `@Data`。评估模块实体是纯 POJO。

**注意**: 不推荐 record——MyBatis-Plus 需要 setter 进行结果映射。应使用 Lombok `@Data` + MyBatis-Plus 注解，或如果坚持 JdbcTemplate（见 P0-5），至少用 Lombok 消除样板代码。

### P2-2: V11 migration 缺少注释说明与设计文档关联

**文件**: `V11__rag_evaluation.sql`

设计文档中定义了 `updated_at` 的自动更新触发器，但 migration 中只有 `DEFAULT NOW()` 没有 `ON UPDATE` 触发器。`updateDatasetItemCount` 手动调了 `NOW()`，但其他 UPDATE 操作可能遗忘。

### P2-3 → 升级 P0: LlmJudgeImpl.judgeModel 未生效，Judge 用的不是配置模型

**文件**: `LlmJudgeImpl.java:43-45`

```java
this.judgeModel = props.getJudgeModel();
this.judgeClient = builder.build();  // ← 未使用 judgeModel 设置 model
```

`judgeModel` 赋值后从未在 ChatClient 调用中使用。ChatClient.Builder 会使用 Spring AI 默认模型。

**这是一个正确性 Bug**：Judge 实际用的不是配置中指定的 judge 模型。

**对比项目标准**: README 确认项目通过 Provider 抽象层（ChatClientFactory + ChatClientRegistry）管理多厂商模型路由。评估模块应复用此机制，通过 `ChatClientRegistry` 获取指定模型的 ChatClient。

### P2-4: DatasetGenerator.sampleChunks() 使用字符串拼接构建 JSON filter

**文件**: `DatasetGenerator.java:101-107`

```java
String filterJson = "{\"userId\": \"" + userId + "\"}";
```

如果 userId 包含特殊字符（如 `"`），JSON 注入风险。

**对比项目标准**: 项目已注入 `ObjectMapper`（LlmJudgeImpl、DatasetExporter、EvaluationRunner 均使用）。应复用 ObjectMapper 构建 filter JSON，保持一致。

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

### P1-7: Controller 无权限控制（可烧 Token，安全风险）

**文件**: `DatasetController.java`, `EvaluationRunController.java`

> 违反 spec quality-guidelines > Security Checklist：`@PreAuthorize` 注解保护接口

评估模块 REST API 完全无权限校验。任何人可以：
- 触发评估运行（消耗大量 LLM Token——100 条 × Judge + Generation 双模型调用）
- 导出数据集内容
- 删除数据集

**对比项目标准**: README 确认项目使用 Spring Security + RBAC 权限体系，其他模块（AuthController、UserController、RoleController）均使用 `@PreAuthorize` 保护。评估模块作为唯一无权限的 API 端点，是攻击面。
**建议**: 至少 `@PreAuthorize("hasRole('ADMIN')")` 限制管理員操作

### P1-8: EvaluationProperties 用 `@Component` 注册

**文件**: `EvaluationProperties.java`

> 项目既有模式：`@ConfigurationProperties` + `@EnableConfigurationProperties` 或 `@ConfigurationPropertiesScan`

`@Component` 注册 `@ConfigurationProperties` 虽然能工作，但不是 Spring Boot 推荐方式。且 `EvaluationConfig` 中已手动创建 Bean，容易遗忘。

### P1-9: evaluation_run.status 用 VARCHAR 无枚举约束

**文件**: `V11__rag_evaluation.sql`, `EvaluationRun.java`

> 违反 spec quality-guidelines > Security Checklist > 状态枚举：用枚举类约束，不接受裸 Integer/String

SQL 中 `status VARCHAR(20) DEFAULT 'pending'` 无 CHECK 约束，Java 侧也无对应枚举。可写入任意字符串。

**对比项目标准**: 项目既有实体（如 `UserStatus`）已使用枚举 + `@EnumValue` / `@JsonValue`。评估模块的 status 和 source 字段（`'hybrid'`/`'draft'` 等）均为裸 String。
**建议**:
- SQL 侧加 `CHECK (status IN ('pending','running','completed','failed'))`
- Java 侧定义 `EvaluationRunStatus` / `EvaluationItemStatus` 枚举，沿用项目 `@EnumValue` + `@JsonValue` 模式

### P1-10: EvaluationRunController.buildEvalConfig() 未考虑三厂商 Provider 差异

**文件**: `EvaluationRunController.java:205-225`

> 违反 spec guides/cross-layer-thinking-guide > Mistake 1: Implicit Format Assumptions

README 确认项目使用三家模型厂商（DeepSeek + 智谱 + MiniMax）通过 Provider 抽象层路由。但评估模块的 `generationModel` / `judgeModel` 配置为硬编码字符串（`zai/glm-5.1`、`deepseek/deepseek-v4-pro`），未走项目的 Provider 路由机制。

P2-3（judgeModel 未生效）的根因也在此——ChatClient.Builder 使用 Spring AI 默认模型，无法识别自定义 model ID。

**建议**: 通过项目的 `ChatClientRegistry` 或 Provider 路由获取指定模型的 ChatClient，而非手动 Builder。

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

## 修正优先级建议（最终）

> 按对齐 README.md 技术栈的影响程度排序

### 第一批：正确性 Bug（立即修）
1. **P2-3 → P0**: judgeModel 未生效 — Judge 跑的是默认模型而非配置模型。应通过 ChatClientRegistry 获取指定模型（README: Provider 抽象层）
2. **P0-2**: PreparedStatement/ResultSet 资源泄漏

### 第二批：与项目既有模式对齐（本迭代修）
3. **P0-5 + P0-6**: Repository 模式 — JdbcTemplate vs MyBatis-Plus 3.5.16 决策。建议：评估模块有意用 JdbcTemplate（零侵入+独立），但需文档化 + 至少用 Lombok 消除 POJO 样板
4. **P0-3 + P1-10**: DTO 规范 — 创建 Request/Response DTO + `@Valid`（对标 AuthController 模式）；model 配置走 Provider 路由
5. **P0-4**: extractJson 抽取到 `common/util/JsonExtractor`

### 第三批：安全 + 健壮性（本迭代修）
6. **P1-7**: 评估 API 加 `@PreAuthorize("hasRole('ADMIN')")` — 无权限 = 可烧 Token（对标 RBAC 体系）
7. **P1-9**: status 枚举 — Java `EvaluationRunStatus` 枚举 + SQL CHECK（对标 UserStatus 模式）
8. **P0-1**: 抽取 EvaluationExecutionService + 事务保护

### 第四批：改进（后续迭代）
9. **P1-6**: DatasetGenerator 并发优化
10. **P1-1**: Runner 依赖拆分
11. **P2-6**: 单元测试（RetrievalMetricsCalculator 优先）
12. **P2-5/P2-7**: RuntimeException + StageSnapshot 异常处理

| 检查项 | 结果 | 说明 |
|--------|------|------|
| 零侵入 | ✅ | 0 修改现有文件，完全符合 |
| Profile 隔离 | ✅ | `@Profile("evaluation")` + `@ConditionalOnProperty` |
| Spec 一致性 | ⚠️ | 大体符合，但缺少 JudgePrompt 模板类（spec 中定义了独立类）；Provider 路由未复用 |
| Flyway 命名 | ✅ | V11 连续编号 |
| REST API 路径 | ✅ | 与 spec 定义一致 |
| 数据模型 | ✅ | 4 表结构与 spec 完全对应 |
| 检索指标公式 | ✅ | 5 个指标公式与 spec 一致 |
| 生成指标方法 | ✅ | 两步 Faithfulness、embedding Answer Relevance 等 |
| LLM-as-Judge 分离 | ❌ | judgeModel 未生效 + 未走 Provider 抽象路由（P2-3→P0） |
| Provider 路由复用 | ❌ | 评估模块未复用 ChatClientFactory/ChatClientRegistry（README: 三厂商路由） |

---


