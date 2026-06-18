# PRD — D-6: TracePropagator 真实实现（替换 NO_OP）

## Goal

实现 `TracePropagator` 的 `inject` / `restore` / `clear`，基于 Spring Micrometer Tracing + W3C TraceContext，
让 traceId 跨 producer/consumer 传播到 MDC，使一条消息的 send → consume 链路可用 traceId 串联。

## Background

现状：

- `TracePropagator.java:14` 只有 `NO_OP`（`inject()` 返回空 Map，`restore()` 空操作）
- `RocketMQMessageBus.java:98` fallback 到 `NO_OP`；producer 侧 `:503` 已接线 `propagator.inject().forEach(builder::addProperty)`
- `MessagingAutoConfiguration.java:38` `@Autowired(required=false) @Nullable TracePropagator`——**无任何实现 bean**，运行期恒为 NO_OP
- consumer 侧 `PushConsumerListener` 已接线 `propagator.restore(properties)` → listener → `propagator.clear()`

即接线都在，缺的就是那个真实实现 bean。

## Requirements

- **R1**：`inject()` 从当前 span 上下文提取 traceId/spanId（W3C `traceparent` 头格式）。
- **R2**：`restore(headers)` 将 traceId/spanId 写入当前线程 MDC + 开启子 span。
- **R3**：`clear()` 清除 MDC + 关闭 span。
- **R4**：与项目现有 tracing 配置集成（确认 classpath 有 `micrometer-tracing` + tracer实现 OTel/Brave）。

## Open Questions（design.md 解决）

- **Q1**：项目当前是否已引入 `micrometer-tracing` + tracer（OTel/Brave）？`grep pom.xml` 确认。若无，本任务是否包含引入依赖？（影响范围）
- **Q2**：MDC key 命名（`traceId`/`spanId`）是否与项目现有 log pattern 一致？

## Acceptance Criteria

- [ ] `TracePropagator` 有真实实现 bean（不再 fallback NO_OP）
- [ ] 端到端：producer send 时 MDC traceId → 写入 message properties → consumer `restore` 后 MDC traceId 一致
- [ ] 集成测试验证 traceId 跨消息传播
- [ ] classpath 依赖确认（或按 Q1 决策引入）

## Notes

- 详细 `design.md` / `implement.md` 在激活时补齐（需先 grep pom.xml 回答 Q1）。
- 接线点已就绪，本任务核心是"补一个实现 bean + 端到端验证"，风险低。
- 设计依据：`docs/design/messaging-bus.md` §4.7 + §9 Phase D Step 6。
