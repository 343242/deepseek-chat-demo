# PRD — D-1: DocumentSupersedeService 迁移到消息总线

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

## Notes

- 详细 `design.md` / `implement.md` 在本子任务激活时补齐（需先回答 Open Questions，可能需 gitnexus 追 `DocumentSupersedeService` 的 event 发布方/消费语义）。
- 设计依据：`docs/design/messaging-bus.md` §7.3（RAG 索引削峰）+ §9 Phase D Step 1。
