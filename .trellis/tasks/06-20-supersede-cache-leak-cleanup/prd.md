# 修复 pendingSupersede 内存泄漏：ETL 失败与文档删除联动清理

## Goal

消除 `DocumentSupersedeService.pendingSupersede`（`ConcurrentHashMap<Long,Long>` 内存加速层）的缓慢泄漏。当前移除仅在 `EtlCompletedEvent` 触发，ETL 失败或文档被删除时 entry 永驻。通过新增 `EtlFailedEvent`（失败终态联动）+ `DocumentDeletedEvent`（删除联动），让 supersede 服务在文档不再走向 completed 时主动清理缓存。

## Background

- `pendingSupersede` 记录 `newDocId → oldDocId`，仅用于 `onEtlCompleted` 快速查找旧版本（正常路径加速）。
- 现有 `onEtlCompleted` 已有 DB 兜底（策略 2：查 `superseded_by`），保证崩溃/时序竞争后仍能正确 supersede —— 这意味着「失败时清缓存」**不影响功能正确性**，仅释放内存。
- OOM 量级低（每 entry ~几十字节），但属于真实内存泄漏 + 代码异味，且违反「本地缓存须有清理路径」原则。

## Requirements

### R1：ETL 失败终态联动清理
- ETL 进入 FAILED 终态（`EtlStatusManager.failDocument` 成功落库后），发布 `EtlFailedEvent`。
- `DocumentSupersedeService` 监听 `EtlFailedEvent`，移除 `pendingSupersede` 中 `documentId` 对应 entry。

### R2：文档删除联动清理
- 文档级联删除（`DocumentLifecycleService.cascadeDelete`）DB 删除后，发布 `DocumentDeletedEvent`。
- `DocumentSupersedeService` 监听 `DocumentDeletedEvent`，移除 `pendingSupersede` 中 `documentId` 对应 entry。

### R3：不破坏现有 supersede 流程
- 正常 completed 路径（`onEtlCompleted` 策略 1 内存 + 策略 2 DB 兜底）行为不变。
- 失败后用户重试 → 重新 completed → 仍能通过 DB 兜底正确 supersede。
- 清理操作幂等（`remove` 找不到返回 null，不报错、不抛异常）。

## Constraints

- 事件发布必须在 DB 事务提交之后（监听器读 DB 时状态已持久化）；事务失败（FAILED 未落库）时**不发**事件，保持 DB-事件一致。
- 遵循现有事件驱动风格：record 事件 + `@EventListener` + `@Async("etlIoExecutor")`。
- 清理仅 `pendingSupersede.remove(newDocId)`，不扫描 value（oldDocId 不会作为 key）。
- 不改 `failDocument` / `cascadeDelete` 的方法签名与返回值（纯追加发布）。
- 构造函数注入新依赖（`ApplicationEventPublisher`）须同步更新受影响单测。

## Out of Scope

- `markVectorFailed` 不发 `EtlFailedEvent`（理由见 design §2.2）。
- 孤儿 PARSING 状态（异常未走到 failDocument）的定时兜底扫描 —— 本 task 不做，留待后续。
- 其他本地缓存（`activeAsyncTasks` 等）—— 已评估无泄漏，不动。

## Acceptance Criteria

- [ ] 新增 `EtlFailedEvent`、`DocumentDeletedEvent` record。
- [ ] `failDocument` 在 FAILED 落库成功后发布 `EtlFailedEvent`；落库失败时不发。
- [ ] `cascadeDelete` 在 DB 删除后发布 `DocumentDeletedEvent`。
- [ ] `DocumentSupersedeService` 新增 `onEtlFailed` / `onDocumentDeleted` 监听器，幂等移除 `pendingSupersede`。
- [ ] 测试覆盖：ETL 失败 → entry 被清；文档删除 → entry 被清；失败后重试成功 → supersede 仍正确（DB 兜底）；重复事件不报错。
- [ ] `./mvnw test` 全绿。
- [ ] `detect_changes`（compare main）复核：`failDocument` 6 调用者、`cascadeDelete` 1 调用者行为无回归。
