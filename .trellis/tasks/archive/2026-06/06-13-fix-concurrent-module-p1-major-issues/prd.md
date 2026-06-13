# fix-concurrent-module-p1-major-issues

## Goal

延续 `06-13-fix-concurrent-module-p0-critical-issues`（已归档于 `archive/2026-06/`），修复同一轮 code review 发现的 17 个 P1 主要问题。让 `infrastructure/concurrent` 模块进一步符合 spec（SOLID、KISS、命名一致性、行为正确性）。

## What I already know

### P0 task 已完成（参考）

- 10 个 P0 全部修复，71 测试全绿
- DefaultTaskScope 拆分为 facade + 5 协作类 + ScopeContext
- spec 同步更新（virtual thread 豁免 / NO_TIMEOUT / Cleaner / LIFO / cross-field）
- 提交：`5fd6f30 fix(concurrent): resolve 10 P0 issues from code review`

### 17 个 P1 摘要（来自 P0 review 报告）

| 编号 | 问题 | 类别 |
|------|------|------|
| P1-1 | `PoolConfig` core=`lightCore()` + max=`ioMax()` 混搭 | 配置一致性 |
| P1-2 | `ScopeState.stopRequested` 用 `AtomicBoolean` 多余（仅 owner 线程访问） | 性能/KISS |
| P1-3 | `Subtask.cancel()` 公开但无 owner 校验 | 不变量/契约 |
| P1-4 | `TaskScope.join(ScopeJoiner)` 默认方法不调 `throwIfFailed()` | 行为安全 |
| P1-5 | `ScopeExecutionException.allFailures()` 命名误导（实为"不可接受失败"） | 命名 |
| P1-6 | `QuorumSuccessPolicy.onFailure` race（`successCount` + `pending` 两次扫描） | 并发安全 |
| P1-7 | `joined.compareAndSet` 早于 join 工作（timeout 异常后无法重试） | 状态机一致性 |
| P1-8 | `cancel()` 调用 `markCancelled(Duration.ZERO)` 丢失真实 elapsed | 监控准确性 |
| P1-9 | `SecurityContextCarrier.capture()` 共享 `Authentication` 引用（非防御拷贝） | 并发安全 |
| P1-10 | `ScopeNestingGuard` 同时使用 ThreadLocal + InheritableThreadLocal（worker stale） | 并发安全 |
| P1-11 | `waitForTerminationRemaining` 顺序 await，单 subtask 超时即 return 丢状态 | 资源管理 |
| P1-12 | `PartialSuccessOrThrowPolicy.shouldStop` 总是 false（首个成功后仍等所有） | 性能 |
| P1-13 | `join()` ZERO 走 null vs `joinUntil(ZERO)` 抛异常 API 不一致 | API 一致性 |
| P1-14 | `DefaultSubtask` 死代码 `NEW→SUCCESS/FAILED`（markRunning 先行则永不触发） | KISS |
| P1-15 | `DefaultScopeExecutorFactory.sharedExecutor` 急切创建（即使不用 SHARED_EXECUTOR） | 资源 |
| P1-16 | `awaitTermination` 用 `properties.getCloseTimeout()` 工厂级语义混用 | 配置语义 |
| P1-17 | `ScopeCleanup` 不持有 state/executor 引用无法清理 — **P0-8 已修复**（`ScopeCleanupState` 持有引用） | ✅ 已完成 |

## Assumptions (temporary)

* P1-17 已被 P0-8 间接修复，本轮跳过 → 实际修复 16 个
* 每个修复影响范围小，独立性强
* 部分修复需要权衡（如 P1-4 改默认方法可能破坏现有调用方）

## Open Questions

（已全部收敛，见 Decisions Resolved）

## Decisions Resolved

* **范围切分**（2026-06-13）：16 个 P1（P1-17 已被 P0-8 修复跳过），分 4 个 commit：
  - **Commit A 行为/不变量（7 个）**：P1-3、P1-4、P1-5、P1-6、P1-7、P1-12、P1-13
  - **Commit B 资源/性能（3 个）**：P1-1、P1-15、P1-16
  - **Commit C 数据准确（3 个）**：P1-8、P1-9、P1-11
  - **Commit D 代码质量（3 个）**：P1-2、P1-10、P1-14
* **P1-4 处理**：只改 javadoc（保留 6 个调用方 partial-success 语义，零行为变化）
* **P1-5 处理**：重命名 `allFailures()` → `unacceptableFailures()`，spec 错误矩阵同步
* **P1-9 处理**：javadoc 警告 Authentication 必须 immutable（Spring Security 内置实现都满足）
* **P1-10 处理**：保留 `LOCAL_SCOPE_DEPTH`/`LOCAL_SCOPE_IDS`/`SCOPED_SUBTASK` 三个 `ThreadLocal`（嵌套跟踪必需），移除 `INHERITED_SCOPE_DEPTH`/`INHERITED_SCOPE_IDS` 两个 `InheritableThreadLocal`（scopedSubtask 已显式传递，ITL 冗余且在平台 worker 上产生 stale 值）

## Requirements (evolving)

* 修复 16 个 P1（P1-17 已完成跳过）
* `TaskScope`、`Subtask`、`ScopedTasks`、`ScopeOptions` 公开 API 兼容（P1-5 重命名是 source-compatible 调整，调用方需同步改但语义不变；P1-3 把 cancel 从 Subtask 移到 TaskScope，是 API 调整）
* 每个 P1 至少 1 个回归测试
* 现有 71 测试保持绿色（除调用 `allFailures()` 的测试需改名）

## Acceptance Criteria (evolving)

* [ ] `mvn test` 全绿
* [ ] 16 个 P1 各有对应回归测试
* [ ] spec 文档如有变化同步更新
* [ ] GitNexus `detect_changes` 验证只影响预期 symbols

## Definition of Done

* 单元测试新增覆盖
* `mvn test` 通过
* PR 描述包含 16 个 P1 的 before/after 对照
* 提交按 Conventional Commits：`fix(concurrent): ...` 或 `refactor(concurrent): ...`

## Out of Scope (explicit)

* P2 问题（10 个）—— 拆下下轮 task
* reactive 与 owner-thread 解耦重设计（仍推迟）
* 新增 scope 策略
* 重写 ScopeNestingGuard（P1-10 只做最小修复）

## Technical Notes

### 受影响调用方（参考 P0 task，需在修复后回归）

* `evaluation/dataset/DatasetGenerator.java`
* `agent/service/HybridSearchService.java`
* `rag/etl/FastTrackStrategy.java` / `StandardStrategy.java`
* `infrastructure/llm/client/bailian/BailianEmbeddingClient.java`
* `infrastructure/llm/strategy/provider/BailianEmbeddingClientFactory.java`
* `infrastructure/llm/registry/LlmClientRegistry.java`

### 关键约束

* Java 21 + Spring Boot 3.5
* 不引入新依赖
* P0 task 已建立的 5 协作类架构（ScopeLifecycle/ScopeJoinEngine/ScopeTimeoutHandler/ScopeExecutorLifecycle/ScopeReporter）保持稳定，本轮只做局部修复

### spec 修改点（潜在）

* 若 P1-5 重命名 `allFailures()` → `unacceptableFailures()`，spec 中错误矩阵条目需同步
* 若 P1-9 改为深拷贝，需在 quality-guidelines.md Structured Concurrency 契约补充
* 若 P1-13 统一 ZERO 语义，需更新 join/joinUntil 文档
