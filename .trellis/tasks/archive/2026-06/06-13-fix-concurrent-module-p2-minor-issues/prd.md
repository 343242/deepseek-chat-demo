# fix-concurrent-module-p2-minor-issues

## Goal

评估并修复 `infrastructure/concurrent` 模块 code review 发现的 10 个 P2 次要问题。P0（10 个）和 P1（16 个）已修复并归档，模块质量已大幅提升（93 测试全绿）。P2 多为可读性/性能微优化，需先评估是否值得继续（收益递减）。

## What I already know

### 累计成果（P0 + P1）

- **P0 task** (`5fd6f30` + `6f1d16a`)：10 个严重问题修复
- **P1 task** (`76139ce` + `2f9c3c0` + `ca5f4f6`)：16 个主要问题修复
- **当前状态**：93 测试全绿，DefaultTaskScope 173 行 facade + 5 协作类 + ScopeContext
- **API**：TaskScope/Subtask/ScopeOptions 公开签名稳定

### 10 个 P2 摘要（来自 P0 review 报告）

| 编号 | 问题 | 类别 |
|------|------|------|
| P2-1 | `DefaultSubtask` result/exception/elapsed `AtomicReference` 多余（单写者） | 性能 |
| P2-2 | `ScopeState.internalSubtasks` 每次创建 unmodifiableList | 性能 |
| P2-3 | `DefaultTaskScope.scopeReport`（现 `ScopeReporter`）NOOP observer 时仍构建 | 性能 |
| P2-4 | `TaskScope.fork(Runnable)` 默认方法无 null 校验 | 边界 |
| P2-5 | `DefaultSubtask.cancel()` 返回值语义模糊 | 命名/语义 |
| P2-6 | `DefaultScopeExecutorFactory.createPool` `allowCoreThreadTimeOut` 仅根据 corePoolSize==0 | 配置 |
| P2-7 | `DefaultSubtask.markCancelled` 自旋 CAS 进入点唯一 | KISS |
| P2-8 | `MdcContextCarrier.capture` 大对象内存开销 | 性能 |
| P2-9 | `DefaultScopedTasks` 构造器重载过多（5 个） | API 设计 |
| P2-10 | `ScopeOptions.withPolicy` 无其他 withXxx 链式方法 | API 设计 |

## Assumptions (temporary)

* 部分 P2 可能被 P0/P1 间接修复（需核对）
* P2 多为低风险微优化，无线上事故风险
* 当前 93 测试已覆盖核心行为，P2 修复不应破坏现有测试

## Open Questions

* **核心决策**：是否值得继续修 P2（收益递减），还是先验证现有改动稳定性（GitNexus 更新 / 端到端调用方回归）
* **范围**：全 10 个 vs 只修值得修的几个 vs 全部跳过

## Requirements (evolving)

* 待范围决策后填充

## Acceptance Criteria (evolving)

* [ ] `mvn test` 全绿（93 测试 + 任何新增 P2 回归测试）
* [ ] 公开 API 不变（除非用户同意调整）
* [ ] 现有调用方不需要改动

## Out of Scope (explicit)

* 进一步重构（如 reactive 解耦、scope 嵌套跨 executor）
* 新功能
* 性能基准测试（除非用户要求）

## Technical Notes

### 受影响调用方（参考前两个 task）

* 8 个 main 调用方都使用 partial-success 模式 + successfulResults joiner
* API 兼容性是硬约束

### P2 与 P0/P1 修复可能重叠

* P2-1（AtomicReference）与 P1-2（AtomicBoolean）类似——P1 已修 ScopeState，DefaultSubtask 可能仍待修
* P2-7（markCancelled CAS）与 P1-14（markSuccess/markFailed 死代码）类似——P1 修了 markSuccess/markFailed，markCancelled 可能仍待简化
* 需在 brainstorm 中核对代码现状
