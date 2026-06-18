# PRD — D-1: DocumentSupersedeService 迁移到消息总线

> **⚠️ DECISION (2026-06-18): DEFERRED — 不迁移。** 评估后认定与本设计 §2.2 非目标冲突，详见下方「Decision」段。本子任务不实施。

## Goal

将 `DocumentSupersedeService`（`rag/service/impl/DocumentSupersedeService.java`）的
`@EventListener` + `@Async("etlIoExecutor")` 异步路径迁移到消息总线，统一异步模型（持久化 + 消费组 + 重试）。

## Background

现状（`DocumentSupersedeService.java:72-73, 118-119, 157`）：

- 3 个 `@EventListener` 方法，其中 2 个带 `@Async("etlIoExecutor")`（文档覆盖/重建场景）
- 走 Spring `ApplicationEventPublisher` + 线程池，**不持久化、进程崩溃即丢失**（§1.1 列出的局限）

Phase B 已有 `EtlDocumentConsumer`（SimpleConsumer，`TOPIC=rag_index_document`，处理 `EtlCandidate` 索引）。
`DocumentSupersedeService` 是**文档覆盖**（同一文档新版本替换索引），与 `EtlDocumentConsumer` 的关系
必须在 design 阶段厘清：复用同 Topic + 不同 Tag/消费组，还是独立 Topic。

## Requirements

- **R1**：`DocumentSupersedeService` 的异步覆盖/重建路径经 `MessageBus` 投递（持久化、消费组、重试）。
- **R2**：与 `EtlDocumentConsumer` 边界清晰，无重复索引 / 无消费组争抢。
- **R3**：事件 payload 化（record），`deduplicationKey` 稳定（documentId 维度）。
- **R4**：迁移后进程崩溃不丢任务（at-least-once + 业务幂等）。

## Open Questions（design.md 解决）

- **Q1**：复用 `rag_index_document` Topic + Tag 区分（save vs supersede），还是新建 `rag_document_supersede` Topic？
- **Q2**：消费组与 `EtlDocumentConsumer` 是否共享？（共享 = 负载均衡；独立 = 各处理一份）
- **Q3**：FIFO 有序性需求（同 documentId 的 supersede 是否需严格有序 → messageGroup）？

## Acceptance Criteria

- [ ] `DocumentSupersedeService` 无 `@Async`（事件触发改为 publish 到 bus）
- [ ] 覆盖事件 → bus → consumer → 索引替换，端到端跑通
- [ ] 与 `EtlDocumentConsumer` 无重复索引（集成测试验证）
- [ ] 进程崩溃重启后未处理任务被重新拉取（at-least-once 验证）
- [ ] `design.md` 厘清 Topic/Tag/消费组决策并落地

## Decision: DEFERRED（2026-06-18，不迁移）

经研究 `DocumentSupersedeService` 实际语义后，**决定不执行本迁移**：

- **与 §2.2 非目标冲突**：§2.2 明确「不替代 Spring `ApplicationEventPublisher`——进程内事件仍使用 Spring 原生机制」。
  `DocumentSupersedeService` 的 3 个 `@EventListener`（`onDocumentCreated` / `onEtlCompleted` / `recoverPendingSupersede`）
  正是进程内**领域事件**（驱动文档版本状态机），属 §2.2 排除范围。§9 Phase D Step 1 与 §2.2 自相矛盾，以 §2.2 为准。
- **现状已崩溃安全**：版本关系事务内持久化 `superseded_by`（PENDING）+ CAS 防并发 + `DuplicateKeyException` 重试 +
  `onEtlCompleted` 双重查找（内存→DB）+ `recoverPendingSupersede` 启动补偿。`@Async` 非持久化的"缺陷"已被设计弥补。
- **迁移收益边际、成本高**：需丢弃跨实例失效的内存 `pendingSupersede`（全靠 DB）、publishers 在事务中需改
  `sendAfterCommit`（DC-01）、新增 2 Topic/Consumer、版本状态机顺序复杂化——不值得。
- **真正的持久化异步（ETL 索引）Phase B 已上总线**（`EtlDispatchServiceImpl.dispatchAsync` → `rag_index_document`）。

> 若未来确实需要（如多实例部署导致进程内事件不可靠），可考虑降级方案 X：
> `EtlDocumentConsumer`（已在总线）dispatch 成功后直接在 consumer 内做旧版本清理，省掉 `EtlCompletedEvent` 绕行。

## Notes

- 设计依据：`docs/design/messaging-bus.md` §2.2（非目标）+ §7.3（RAG 索引削峰）+ §9 Phase D Step 1。
