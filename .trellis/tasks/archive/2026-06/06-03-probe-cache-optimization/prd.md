# 首包探测优化：共享探测去重 + Redis 健康缓存

## Goal

在当前已有的 Reactive 探测 + 熔断降级架构基础上，增加共享探测去重和 Redis 健康缓存两层优化，减少重复探测调用次数，降低首包延迟和模型服务端压力。

## Background

当前首包探测链路已经是合理的混合架构：
- `ProbeStreamHandler`：纯 Reactive（`Flux.create` + `Mono.delay`），零线程阻塞
- `StreamRetryHandler`：纯 Reactive（递归 `Flux.defer` + `onErrorResume`）
- `OkHttpSseModelStreamClient`：阻塞 I/O 隔离在 `Schedulers.boundedElastic()`

500 QPS 下无线程瓶颈问题。但每次请求都会发起独立探测，存在两个优化空间：
1. 同一模型的并发请求各自探测 → 重复探测浪费
2. 已知健康的模型每次仍需探测 → 不必要的延迟

## Requirements

### 策略 A：共享探测去重

- 引入 `SharedProbeRegistry`，使用 `ConcurrentHashMap<String, CompletableFuture<ProbeResult>>` 管理进行中的探测。
- 同一 modelId 的并发请求共享同一个探测 Future，避免重复探测。
- 探测完成（无论成功失败）后自动从 map 中移除。
- 预期效果：500 QPS 中 80% 命中同一模型时，实际探测次数从 500/s 降至 1-2/s。

### 策略 B：Redis/Redisson 健康缓存

- 使用 Redisson `RMapCache<String, HealthEntry>` 存储模型健康状态，带 per-entry TTL。
- `HealthEntry` record 包含：modelId、status（HEALTHY/UNHEALTHY/UNKNOWN）、探测时间戳、延迟毫秒数。
- 请求路径：查 Redis 缓存 → 命中且 HEALTHY → 跳过探测直接调用。
- 探测成功：写入缓存，TTL 30s。
- 探测失败：标记 UNHEALTHY，TTL 15s（更短，允许更快重试）。
- 后台定时任务（`@Scheduled`）预探测所有启用候选，刷新即将过期的缓存条目。

### 集成方式

- 优化集成在 `StreamRetryHandler` 的探测流程中，不改变现有 Reactive 管道结构。
- `ProbeStreamHandler` 保持不变，仅在探测前增加缓存查询和共享去重逻辑。
- 通过 `@ConditionalOnProperty` 控制启用/禁用，默认禁用，按需开启。

## Acceptance Criteria

- [ ] 同 modelId 并发请求共享探测 Future，无重复探测。
- [ ] Redis 缓存命中且 HEALTHY 时跳过探测，直接发起流式调用。
- [ ] 探测成功/失败后正确更新 Redis 缓存。
- [ ] 后台预探测定时任务定期刷新健康缓存。
- [ ] 功能通过 `@ConditionalOnProperty` 控制开关，默认禁用。
- [ ] `mvn test` 通过。
- [ ] GitNexus detect changes 影响面仅限 fallback 探测相关组件。

## Definition of Done

- 新增类有对应单元测试。
- Lint / typecheck / tests green。
- Trellis/spec impact reviewed。
- Changes committed and pushed。

## Technical Approach

### 新增组件

| 类 | 职责 | 包路径 |
|---|---|---|
| `SharedProbeRegistry` | 共享探测去重，管理 in-flight CF | `infrastructure.fallback.probe` |
| `ModelHealthCache` | Redis/Redisson 健康缓存读写 | `infrastructure.fallback.cache` |
| `ModelHealthPreProber` | 后台定时预探测任务 | `infrastructure.fallback.cache` |
| `HealthEntry` | 缓存条目 record | `infrastructure.fallback.cache` |

### 修改组件

| 类 | 修改内容 |
|---|---|
| `StreamRetryHandler` | 探测前查询 Redis 缓存 + SharedProbeRegistry |
| `FallbackAutoConfiguration` | 注册新 Bean（条件装配） |
| `ChatCandidatesProperties` | 新增 `probe-cache-ttl-seconds`、`pre-probe-interval-ms` 配置项 |

### 请求流

```
StreamRetryHandler.execute(chain, ...)
    │
    ├─ 对每个候选 modelId:
    │   │
    │   ├─ (B) Redis 缓存查询
    │   │   命中且 HEALTHY → 直接 doStream()，跳过探测
    │   │   未命中 ↓
    │   │
    │   ├─ (A) SharedProbeRegistry 去重
    │   │   已有同 modelId 探测在飞 → 共享等待其结果
    │   │   没有 → 发起新探测
    │   │
    │   ├─ ProbeStreamHandler.wrapWithProbe()（现有逻辑不变）
    │   │
    │   ├─ 成功 → (B) 写 Redis 缓存 → doStream()
    │   └─ 失败 → (B) 标记 UNHEALTHY → 熔断 → 下一候选
    │
    └─ 全部失败 → RemoteException
```

### Redis 数据结构

使用 Redisson `RMapCache`（带 per-entry TTL 的 Map）：
- Key: model compositeId（如 `bailian/qwen-plus-latest`）
- Value: `HealthEntry`（status + timestamp + latencyMs）
- TTL: HEALTHY=30s, UNHEALTHY=15s

## Decision (ADR-lite)

**Context**: 当前探测链路无线程瓶颈，但每次请求独立探测造成不必要的重复调用和延迟。

**Decision**: 在现有 Reactive 管道前端增加共享探测去重 + Redis 健康缓存两层短路，不改变底层探测机制。

**Consequences**: 探测调用次数大幅减少，首包延迟降低（缓存命中时）。新增 Redis 依赖用于缓存（项目已有 Redisson）。

## Out of Scope

* 修改 `ProbeStreamHandler` 的 Reactive 探测实现。
* 修改 `ModelCircuitBreakerRegistry` 的三态熔断逻辑。
* 修改 `FallbackChainResolver` 的静态降级链解析。
* 修改 `FallbackEligibility` 的异常过滤规则。
* 修改 REST endpoint 或 Controller 层。
* 引入新的外部依赖（Redisson 已存在）。
