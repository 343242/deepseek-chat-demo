# RAG 评估系统审查修复 PRD

**任务 ID**: 05-16-eval-review-fixes
**父任务**: 05-16-rag-evaluation
**分支**: eval-rag-dev
**审查报告**: `.trellis/tasks/05-16-rag-evaluation/CODE-REVIEW.md`
**创建日期**: 2026-05-16

---

## 背景

RAG 评估系统（05-16-rag-evaluation）已完成 5 个 Phase 的开发和初步审查。经过两轮审查（ECC 技能 + trellis spec 全量 + README 技术栈对齐），发现 **6 个 P0 + 9 个 P1 + 7 个 P2** 问题。

本任务按修正优先级分为 4 个 Phase，逐步修复所有 P0/P1 和关键 P2。

---

## Phase 1: 正确性 Bug 修复

> 对应审查报告修正优先级第一批

### 1.1 P2-3→P0: judgeModel 未生效

**问题**: `LlmJudgeImpl` 的 `judgeModel` 赋值后从未使用，`ChatClient.Builder` 使用 Spring AI 默认模型。Judge 实际跑的不是配置中指定的模型。

**修复方案**:
- 通过项目的 `ChatClientRegistry` 获取指定 provider/model 的 ChatClient
- 如果 `ChatClientRegistry` 不支持按 model ID 查找，则在 `LlmJudgeImpl` 中使用 `ChatClient.builder().defaultModel(judgeModel).build()` 显式指定模型
- 确保 `evaluation-evaluation.yml` 中的 `judge-model` 配置值与项目 Provider 注册的 model ID 一致

**涉及文件**: `LlmJudgeImpl.java`、可能涉及 `EvaluationConfig.java`

### 1.2 P0-2: PreparedStatement/ResultSet 资源泄漏

**问题**: `DatasetRepository.insertItems()` 循环内 `ps.executeQuery()` 产生的 ResultSet 从未关闭。`insertItem()` 同理。

**修复方案**:
- 所有 `PreparedStatement.executeQuery()` 用 try-with-resources 包裹
- `PreparedStatement` 本身也用 try-with-resources

**涉及文件**: `DatasetRepository.java`（`insertItems()`、`insertItem()`）、`EvaluationResultRepository.java`（如有类似问题）

**验证**: 编译通过 + 手动检查无资源泄漏

---

## Phase 2: 项目模式对齐

> 对应审查报告修正优先级第二批

### 2.1 P0-5 + P0-6: Repository 模式决策 + 实体改 record

**问题**: Repository 用 JdbcTemplate 而非项目标准 MyBatis-Plus；实体是纯 POJO 而非项目常用的 record。

**决策**: 评估模块有意使用 JdbcTemplate（零侵入策略 + 独立模块），文档化此决策到 spec。

**修复方案**:
- 四个实体类（`EvaluationDataset`、`EvaluationDatasetItem`、`EvaluationRun`、`EvaluationResult`）改为 Java record
- 在 `.trellis/spec/backend/database-guidelines.md` 补充说明：评估模块使用 JdbcTemplate + record，不走 MyBatis-Plus
- Repository 的 RowMapper 适配 record（构造器映射）

**涉及文件**: 4 个实体类、`DatasetRepository.java`（RowMapper）、`EvaluationResultRepository.java`（RowMapper）、spec 文档

### 2.2 P0-3 + P1-3 + P1-5: DTO 规范 + 类型安全

**问题**: Controller 用 `Map<String, Object>` 做请求体，无 Bean Validation、NPE 风险、强制类型转换不安全。

**修复方案**:
- 创建 `GenerateDatasetRequest` record（`@NotBlank name`、`@NotNull Long userId`）
- 创建 `StartRunRequest` record（`@NotNull Long datasetId`、可选 `String name`、可选 `Map<String, Object> configOverrides`）
- Controller 方法签名改为 `@Valid @RequestBody GenerateDatasetRequest request`
- `buildEvalConfig()` 的 Boolean 转换加安全处理（先检查 instanceof）

**涉及文件**: 新增 2 个 DTO、`DatasetController.java`、`EvaluationRunController.java`

### 2.3 P1-10: Provider 路由

**问题**: 评估模块硬编码 model ID，未走项目的 Provider 抽象路由。

**修复方案**:
- 查阅 `ChatClientRegistry` 的 API，确认是否支持按 model ID 获取 ChatClient
- 如果支持：`LlmJudgeImpl` 和 `EvaluationRunner` 通过 registry 获取
- 如果不支持：在 `EvaluationConfig` 中创建带指定 model 的 ChatClient Bean

**涉及文件**: `EvaluationConfig.java`、`LlmJudgeImpl.java`、`EvaluationRunner.java`

### 2.4 P0-4: extractJson 抽取公共工具类

**问题**: `extractJson()` 方法在 5 个类中重复实现。

**修复方案**:
- 创建 `com.demo.chat.common.util.JsonExtractor` 工具类
- 统一实现（含三层容错：raw → markdown code block → `[...]` → 正则）
- 所有消费者（`LlmJudgeImpl`、`DatasetGenerator`、4 个 Scorer）改为调用工具类

**涉及文件**: 新增 `JsonExtractor.java`，修改 5 个消费者

---

## Phase 3: 安全与健壮性

> 对应审查报告修正优先级第三批

### 3.1 P1-7: Controller 权限控制

**问题**: 评估模块 REST API 无权限校验，可被未授权用户调用（消耗 LLM Token）。

**修复方案**:
- `DatasetController` 加 `@PreAuthorize("hasRole('ADMIN')")` 类级别
- `EvaluationRunController` 加 `@PreAuthorize("hasRole('ADMIN')")` 类级别
- 确认 `@Profile("evaluation")` 与 `@PreAuthorize` 不冲突

**涉及文件**: `DatasetController.java`、`EvaluationRunController.java`

### 3.2 P1-9: status 枚举约束

**问题**: `evaluation_run.status` 和 `evaluation_dataset_item.status` 是裸 VARCHAR，无 CHECK 约束和 Java 枚举。

**修复方案**:
- 创建 `EvaluationRunStatus` 枚举（`PENDING`、`RUNNING`、`COMPLETED`、`FAILED`）+ `@JsonValue`
- 创建 `EvaluationItemStatus` 枚举（`DRAFT`、`APPROVED`、`REJECTED`）+ `@JsonValue`
- 新增 Flyway 迁移 `V12__eval_status_check.sql`：加 `CHECK (status IN (...))` 约束
- Repository 的 status 相关硬编码字符串改为枚举引用

**涉及文件**: 新增 2 个枚举、`V12__eval_status_check.sql`、Repository 文件、实体已改 record 后的 status 字段类型

### 3.3 P0-1: 抽取 EvaluationExecutionService + 事务

**问题**: `EvaluationRunController.startRun()` 包含业务逻辑且无事务保护，中途失败 Run 永远卡在 `running`。

**修复方案**:
- 创建 `EvaluationExecutionService`（`@Service`）
- `startRun()` 的业务逻辑（创建 Run → 遍历评估 → 更新状态）移入 Service
- 使用 `TransactionTemplate` 编程式事务（项目规范）
- 部分失败时 Run 状态标记 `failed`（而非 `completed`）
- Controller 只做参数校验和调用 Service

**涉及文件**: 新增 `EvaluationExecutionService.java`、修改 `EvaluationRunController.java`

---

## Phase 4: 改进与测试

> 对应审查报告修正优先级第四批

### 4.1 P1-6: DatasetGenerator 并发优化（可选）

**问题**: 100 次 LLM 调用串行执行。

**修复方案**: 使用 `CompletableFuture` + `Semaphore(3)` 控制并发。如果 Phase 2 工作量已经很大，可推到下个迭代。

### 4.2 P1-2: copyWithOverride 重构

**问题**: 手写 14 行属性拷贝，新增字段易遗漏。

**修复方案**: 在 `RagRetrievalProperties` 上添加 `copy()` 方法，或用 Spring `BeanUtils.copyProperties()`。

### 4.3 P2-6: 单元测试

**问题**: 0 测试。

**修复方案**:
- `RetrievalMetricsCalculator` — 纯计算，最高 ROI
- `JsonExtractor` — 抽取后的工具类
- `EvaluationRunStatus` / `EvaluationItemStatus` — 枚举转换

### 4.4 P2-5 + P2-7: 异常处理

**问题**: `DatasetExporter` 抛 `RuntimeException`；`PipelineInstrumenter` 吞异常。

**修复方案**:
- `DatasetExporter` 改抛项目 `BusinessException`
- `PipelineInstrumenter` 序列化失败时 warn 级别（而非 debug），保留失败原因

---

## 约束

- **零侵入原则不变**：不修改现有 RAG 模块的任何代码
- **每 Phase 一个 commit**：commit message 清晰描述改了什么
- **编译验证**：每个 Phase 完成后 `mvn compile` 确认通过
- **git commit + push**：每 Phase 完成后 push

## 不做的事

- P1-1 Runner 依赖拆分（工作量大，推到下个迭代）
- P1-8 EvaluationProperties @Component 注册方式（影响小，非阻塞）
- P2-2 V11 migration updated_at 触发器（已有手动 NOW()，非阻塞）
- P2-4 DatasetGenerator JSON 拼接（Phase 2.2 DTO 化时可顺手修）
