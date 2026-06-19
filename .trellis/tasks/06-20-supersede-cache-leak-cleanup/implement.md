# Implement: pendingSupersede 内存泄漏清理（失败 + 删除联动）

## 前置（CLAUDE.md 强制）

- [x] `impact` 已跑（见 `design.md` §5）：`failDocument` HIGH（纯追加，安全）、`cascadeDelete` LOW、`EtlStatusManager` LOW。
- [ ] 每步编辑前若触及新 symbol，补跑 `impact`。

## 执行 checklist（有序，每步可独立编译/回滚）

### Step 1 — 新增事件 record
- [ ] 新建 `src/main/java/com/smart/rag/rag/event/EtlFailedEvent.java`：`record EtlFailedEvent(Long documentId, String errorMessage) {}`（参照 `EtlCompletedEvent` 风格 + javadoc）。
- [ ] 新建 `src/main/java/com/smart/rag/rag/event/DocumentDeletedEvent.java`：`record DocumentDeletedEvent(Long documentId) {}`。
- [ ] 编译：`./mvnw -q compile`。

### Step 2 — EtlStatusManager 发布 EtlFailedEvent
- [ ] 构造函数追加 `ApplicationEventPublisher eventPublisher` 参数并赋字段。
- [ ] `failDocument`：`publishEvent` 插在 `executeWithoutResult(...)` 调用**之后**、**仍在 try 块内**、`catch` **之前**（事务已同步提交；若 `executeWithoutResult` 抛 `txEx` 则跳过发布）。精确形态：

  ```java
  public void failDocument(Long documentId, Exception e) {
      log.error("ETL failed for document: id={}", documentId, e);
      try {
          transactionTemplate.executeWithoutResult(ts -> {
              // ... 现有 DB 更新（setStatus FAILED / errorMessage / updateTime）
          });
          // ✅ 事务已提交，DB 状态可见 → 发布事件
          eventPublisher.publishEvent(new EtlFailedEvent(documentId, truncate(e.getMessage(), 2000)));
      } catch (Exception txEx) {
          log.error("Failed to persist FAILED status for document: id={}", documentId, txEx);
          // ❌ 事务失败 → 不发事件（保持 DB-事件一致）
      }
  }
  ```

  - 注意：`publishEvent` **不可**放到 `catch` 之后（那样 txEx 时仍会发）或 try 外。
- [ ] grep 修复 `new EtlStatusManager(...)` 的测试调用（补 publisher 参数，可用 `ApplicationEventPublisher` 的 mock/`nop` 实现）。

### Step 3 — DocumentLifecycleService 发布 DocumentDeletedEvent
- [ ] 构造函数追加 `ApplicationEventPublisher eventPublisher` 参数并赋字段。
- [ ] `cascadeDelete`：`ragDocumentMapper.deleteById(id)` **之后**、`return true` 前，`eventPublisher.publishEvent(new DocumentDeletedEvent(id))`。
- [ ] grep 修复 `new DocumentLifecycleService(...)` 的测试调用。

### Step 4 — DocumentSupersedeService 监听清理
- [ ] 新增 `@EventListener @Async("etlIoExecutor") public void onEtlFailed(EtlFailedEvent event)`：`pendingSupersede.remove(event.documentId())`，try-catch 记日志（参照 `onEtlCompleted` 风格）。
- [ ] 新增 `@EventListener @Async("etlIoExecutor") public void onDocumentDeleted(DocumentDeletedEvent event)`：`pendingSupersede.remove(event.documentId())`，try-catch 记日志。
- [ ] 更新类 javadoc：说明三处清理入口（completed/failed/deleted）。

### Step 5 — 测试
- [ ] `EtlStatusManagerTest`（或等价）：failDocument 落库成功 → 发布 `EtlFailedEvent`；落库失败 → 不发。
- [ ] `DocumentLifecycleServiceTest`：cascadeDelete → 发布 `DocumentDeletedEvent`。
- [ ] `DocumentSupersedeServiceTest`：
  - put 后发 `EtlFailedEvent` → entry 被清。
  - put 后发 `DocumentDeletedEvent` → entry 被清。
  - 失败清后模拟 completed（DB 兜底）→ 仍 supersede 正确。
  - 对未 put 的 documentId 发事件 → 不报错（幂等）。
- [ ] 全量：`./mvnw test`。

### Step 6 — 复核
- [ ] `detect_changes({scope:"compare", base_ref:"main"})`：确认仅触及改动清单内 symbol，无意外回归。
- [ ] 人工核对：`failDocument` 6 调用者、`cascadeDelete` 1 调用者行为不变。

## 验证命令

```bash
./mvnw -q compile          # 增量编译
./mvnw test                # 全量测试
```

## Review Gate

实现完成后、提交前：
1. `detect_changes` compare main —— 仅预期 symbol。
2. 全量测试绿。
3. 向用户报告爆炸半径 + 结果，确认后进入 commit（step 3.4）。

## 回滚点

- 每个 Step 独立 commit-able；事件无持久化副作用，revert 对应 commit 即恢复。
- Step 2/3 的构造函数变更是唯一影响测试的破坏点，单独 revert 即可。
