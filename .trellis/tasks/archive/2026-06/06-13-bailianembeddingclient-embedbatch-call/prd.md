# 并发优化 BailianEmbeddingClient embedBatch/call 方法

## Goal

将 BailianEmbeddingClient 中 `embedBatch` 和 `call(EmbeddingRequest)` 的串行批次循环改为并发执行，利用项目已有的结构化并发基础设施 `ScopedTasks`，加速文档入库向量化。

## What I already know

### 调用链路
```
FastTrackStrategy.asyncVectorize
  → VectorStoreLoader.load(chunks)
    → PgVectorStore.add(documents)          // Spring AI
      → BailianEmbeddingClient.call(EmbeddingRequest)  // 实现 EmbeddingModel
```
- 单文档分块后可能产生几十到几百个 chunk
- PgVectorStore 将所有 chunk 文本一次性塞进 EmbeddingRequest
- call() 内部按 MAX_BATCH_SIZE=10 串行分批调用 DashScope API
- 200 chunk = 20 次串行 HTTP 请求，延迟线性叠加

### 弹性层
- `ResilientEmbeddingClient` 将 `embedBatch` 作为整体通过熔断器 + 重试
- 串行子批次失败 → 整体失败 → 重试整个 embedBatch

### 结构化并发基础设施
- `ScopedTasks` — Spring bean (via `ScopedTaskAutoConfiguration`)
- `TaskScope` — fork/join 结构化并发
- `ScopePolicy.COLLECT_ALL` — 收集所有结果，失败时抛异常
- `ScopeOptions.maxConcurrency` — 控制并发度上限
- `ScopeJoiner.successfulResults()` — 类型安全的结果收集
- 虚拟线程模式 `ExecutorMode.VIRTUAL_THREAD_PER_TASK`

### 约束
- DashScope API 有 QPS 限制（通常 5-10 QPS）
- embedBatch 和 call 均需保证结果顺序与输入一致
- Embedding 操作幂等（相同文本 → 相同向量），重试安全

## Requirements

* embedBatch 和 call(EmbeddingRequest) 的子批次并发执行
* 结果顺序与输入一致
* 并发度可控（默认 4，防 DashScope 限流）
* 复用 ScopedTasks 结构化并发基础设施
- 兼容外层 ResilientEmbeddingClient 的熔断器/重试语义

## Acceptance Criteria

- [ ] embedBatch 对 N 个子批次并发执行（maxConcurrency=4）
- [ ] call(EmbeddingRequest) 对 N 个子批次并发执行
- [ ] 返回结果顺序与输入一致
- [ ] ScopedTasks 作为依赖注入（不 new）
- [ ] 编译通过 + 现有测试不回归

## Technical Approach (proposed)

**在 BailianEmbeddingClient 内部使用 ScopedTasks 并发子批次**

- 注入 `ScopedTasks`，在 `embedBatch` 和 `call` 中将子批次 fork 到 scope
- `ScopePolicy.COLLECT_ALL` + 手动按 index 排序保证顺序
- `maxConcurrency=4` 控制 DashScope 限流
- ResilientEmbeddingClient 仍将整个 embedBatch 视为原子操作
  - 熔断器：整体成功/失败记录
  - 重试：任何子批次失败 → 重试整个 embedBatch（幂等，安全）
- 不修改 ResilientEmbeddingClient 和 AbstractEmbeddingClient

## Out of Scope

* 修改 ResilientEmbeddingClient 弹性层逻辑
* 修改 AbstractEmbeddingClient 默认实现
* 修改 ETL 管道或 VectorStoreLoader
* GenericEmbeddingClient 的并发优化

## Technical Notes

### 关键文件
- `BailianEmbeddingClient.java` — 主要改动目标
- `ScopedTasks.java` / `TaskScope.java` / `ScopePolicy.java` — 并发基础设施
- `ResilientEmbeddingClient.java` — 外层弹性装饰器（不改）
- `EmbeddingCapabilityStrategy.java` — 工厂创建链路（需传入 ScopedTasks）

## Decision (ADR-lite)

**Context**: embedBatch/call 串行循环导致入库向量化延迟线性叠加，需并发化
**Decision**: 在 BailianEmbeddingClient 内部使用 ScopedTasks 并发子批次；ScopedTasks 通过 BailianEmbeddingClientFactory（@Component）构造器注入存为字段，create() 内部传递
**Consequences**: ProviderClientFactory 接口不变；ResilientEmbeddingClient 语义不变；仅 BailianEmbeddingClient + BailianEmbeddingClientFactory 改动

### 构造器变更
BailianEmbeddingClient: `(String baseUrl, String endpoint, String apiKey, ModelCandidate candidate, ScopedTasks scopedTasks)`

### 工厂变更
BailianEmbeddingClientFactory 构造器注入 `ScopedTasks`，存为字段，`create()` 内部传递给 BailianEmbeddingClient
