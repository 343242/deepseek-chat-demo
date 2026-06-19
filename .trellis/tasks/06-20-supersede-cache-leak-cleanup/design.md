# Design: pendingSupersede 内存泄漏清理（失败 + 删除联动）

## 1. 根因与现状

`DocumentSupersedeService.pendingSupersede`（`ConcurrentHashMap<Long,Long>`，`newDocId → oldDocId`）是纯内存加速层：

| 操作 | 位置 | 时机 |
|---|---|---|
| put | `linkVersion` L225 | 事务提交成功后（DocumentCreatedEvent 触发） |
| remove | `onEtlCompleted` L123 | **仅** `EtlCompletedEvent` |

**泄漏场景**（remove 永不触发）：
1. ETL 失败（FAILED/VECTOR_FAILED/抛异常）→ 不发 `EtlCompletedEvent`。
2. 文档被删除（`cascadeDelete`）→ newDocId 不复存在，ETL 即使后续完成也无意义。
3. 无定时兜底清理（`recoverPendingSupersede` L157 只做 DB 层 `superseded_by` 补偿，不重建/清理内存 Map）。

**OOM 量级**：每 entry ≈ `Long→Long` + Map 节点（几十字节），增长速率 = 「上传新版本且 ETL 未 completed」次数。单独不至于 OOM，但属真实泄漏。

## 2. 设计决策

### 2.1 EtlFailedEvent 发布点 = `EtlStatusManager.failDocument`（chokepoint）

**候选对比**：
- ❌ 在 `EtlDocumentConsumer` / `dispatchViaThreadPool` 发布：发布点分散（2 处），且 `dispatch` 抛异常时 handler 无 catch（consumer 靠 RetryPolicy 重试，executor 只 log），**会漏掉「抛异常」失败**。
- ✅ 在 `EtlStatusManager.failDocument` 发布：所有显式失败（FastTrack L132/L223、Standard L98/L136/L163）**必经此处**，单一阻塞点，不漏。

`failDocument` 是 FAILED 终态的权威记录点（DB status → FAILED），在此发事件语义最清晰。

### 2.2 `markVectorFailed` 不发 `EtlFailedEvent`（决策）

FastTrack 时序：BM25 写成功 → `completeDocument(COMPLETED)` (L121) → `EtlResult.success` → consumer 发 `EtlCompletedEvent` → `onEtlCompleted` 清 `pendingSupersede` → **之后**异步向量化失败 → `markVectorFailed` (L203)。

即 `markVectorFailed` 必定发生在 `EtlCompletedEvent` 之后，此时 `pendingSupersede` 已被清，发 `EtlFailedEvent` 恒为 no-op。不发可避免向量化失败高频场景下的事件 + 线程池开销。

> 若未来出现 completed 之前的 `markVectorFailed` 路径（如 StandardStrategy 引入），届时再补发。review 时可推翻此决策改为「都发」。

### 2.3 删除联动 = 发 `DocumentDeletedEvent`，不直接注入 supersede 服务

**候选对比**：
- ❌ `DocumentLifecycleService` 直接注入 `DocumentSupersedeService` 调清理方法：lifecycle 须感知 supersede 内部缓存，耦合。
- ✅ 发 `DocumentDeletedEvent`，`DocumentSupersedeService` 监听清理：符合现有事件驱动风格（`onDocumentCreated` / `onEtlCompleted` 均事件监听），解耦，且删除事件未来可复用于其他清理。

**无循环依赖**：`DocumentLifecycleService` 依赖 `Loader/FileStorage/Mapper`；`DocumentSupersedeService` 依赖 `Mapper/VectorStoreMapper/Loader/FileStorage/TransactionTemplate`。两者互不依赖。引入 `ApplicationEventPublisher`（Spring 内置）到 lifecycle 不产生循环。

### 2.4 事务后发布，失败不发

`failDocument` 用 `transactionTemplate.executeWithoutResult`（同步提交）。事件发布置于 try 块内、`executeWithoutResult` **之后**：此时事务已提交。若事务本身失败（`txEx` 被 catch），**不发**事件 —— 保持「DB 标记 FAILED ⟺ 发 EtlFailedEvent」一致，避免监听器读到未落库状态。

监听器 `@Async("etlIoExecutor")` 异步执行，到达时事务必然已提交，无可见性问题。

### 2.5 清理语义

- `onEtlFailed` / `onDocumentDeleted` 均执行 `pendingSupersede.remove(documentId)`（documentId = newDocId）。
- 幂等：`remove` 返回 null 不报错。
- 不扫 value：oldDocId 永远不会作为 key（key 恒为 newDocId）；用户删 oldDocId 时 entry 不受影响，后续 completed 走 DB 兜底容错。

## 3. 改动清单

| # | 文件 | symbol | 改动 |
|---|---|---|---|
| 1 | `event/EtlFailedEvent.java` | new record | `record EtlFailedEvent(Long documentId, String errorMessage)` |
| 2 | `event/DocumentDeletedEvent.java` | new record | `record DocumentDeletedEvent(Long documentId)` |
| 3 | `etl/EtlStatusManager.java` | ctor + `failDocument` | 注入 `ApplicationEventPublisher`；`failDocument` 落库成功后 `publishEvent(new EtlFailedEvent(...))` |
| 4 | `service/impl/DocumentLifecycleService.java` | ctor + `cascadeDelete` | 注入 `ApplicationEventPublisher`；`cascadeDelete` DB 删除后 `publishEvent(new DocumentDeletedEvent(id))` |
| 5 | `service/impl/DocumentSupersedeService.java` | new methods | `onEtlFailed` / `onDocumentDeleted`（`@EventListener @Async("etlIoExecutor")`），`remove` 清理 |
| 6 | 测试 | new + fix | 新增失败/删除清理测试；修复 `new EtlStatusManager(...)` / `new DocumentLifecycleService(...)` 构造调用 |

## 4. 时序

### 4.1 正常 supersede（不变）
```
upload新版 → DocumentCreatedEvent → onDocumentCreated → linkVersion → pendingSupersede.put
→ dispatchAsync → ... → COMPLETED → EtlCompletedEvent → onEtlCompleted
→ pendingSupersede.remove(newDocId) → supersedeOldVersion
```

### 4.2 ETL 失败（新增清理）
```
... → linkVersion → pendingSupersede.put
→ dispatchAsync → strategy.execute → failDocument → DB=FAILED + EtlFailedEvent（新增）
→ onEtlFailed（新增）→ pendingSupersede.remove(newDocId)  ✓ 释放
```

### 4.3 文档删除（新增清理）
```
DocumentController.delete → DocumentApplicationServiceImpl.delete
→ cascadeDelete → DB deleteById + DocumentDeletedEvent（新增）
→ onDocumentDeleted（新增）→ pendingSupersede.remove(id)  ✓ 释放
```

### 4.4 失败后重试（DB 兜底保证正确）
```
[4.2 失败，entry 已清] → 用户 retry → dispatchAsync → COMPLETED → EtlCompletedEvent
→ onEtlCompleted：策略1 remove 找不到（已清）→ 策略2 查 superseded_by → supersedeOldVersion  ✓
```

## 5. Impact 分析（GitNexus，edit 前）

| symbol | 风险 | 直接调用者 | 论证 |
|---|---|---|---|
| `failDocument` | **HIGH** | 6（FastTrack L132/L223、Standard L98/L136/L163 等）；影响 `executeWithUserId` 流 9 hits | ⚠️ **已警告**。改动为**纯追加 `publishEvent`**，签名/返回值/现有行为不变，6 调用者零改动。HIGH 仅因调用面广。 |
| `cascadeDelete` | LOW | 1（`DocumentApplicationServiceImpl.delete`）；影响 `delete` 流 | 纯追加发布，调用者无感。 |
| `EtlStatusManager` | LOW | 4 依赖者 | 构造函数 +1 参数（`ApplicationEventPublisher`）：Spring 自动注入生产无感；`new` 该类的单测须补参数。 |

## 6. 风险与回滚

- **非破坏性**：所有改动为「新增事件 + 追加发布 + 新增幂等监听器」，无现有逻辑分支变更。
- **幂等**：`remove` 与事件重复均安全。
- **回滚**：逐条 revert 改动清单即可，事件无持久化副作用。
- **残余缺口**（接受）：异常未走到 `failDocument`（孤儿 PARSING）+ 删除前已完成 supersede 的边界 —— 靠 R3 DB 兜底保证功能正确，内存残余留给后续定时扫描 task。
