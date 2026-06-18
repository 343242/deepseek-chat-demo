# Design — D-6 方案 b: TracePropagator（micrometer-tracing-bridge-otel，无 exporter）

> 选定方案 **b**：忠于设计文档 §4.7「Spring Micrometer Tracing + W3C TraceContext」，最小增量、
> 可平滑演进到方案 a（加 OTLP exporter + collector 即可，本类不改）。

## 现状（已核实）

- classpath **零 tracing**：仅有 `spring-boot-starter-actuator`，无 `micrometer-tracing`/OTel/Brave。
- `TracePropagator` 接口只有 `NO_OP`；`MessagingAutoConfiguration` 未提供 propagator bean →
  `RocketMQMessageBus`/`PushConsumerListener`/`SimpleConsumerReceiveLoop` 运行期恒为 NO_OP。
- 接线已就位（不改调用点）：
  - producer: `RocketMQMessageBus:503` `propagator.inject().forEach(builder::addProperty)`
  - consumer: `PushConsumerListener:44` restore / `:81` clear(finally)；`SimpleConsumerReceiveLoop:212` restore / `:237` clear
- 日志框架 = **log4j2.xml**（非 logback）。

## 依赖

- `io.micrometer:micrometer-tracing-bridge-otel`（版本由 spring-boot-starter-parent BOM 管，不写版本）
- `io.opentelemetry:opentelemetry-sdk`（同上）。**必须显式加**——bridge 只带 `opentelemetry-api`，
  没有 SDK 则 Spring Boot 的 `OpenTelemetryAutoConfiguration` 不产 `OpenTelemetry` bean，propagator 又掉回 NO_OP（重蹈 Phase 0 覆辙）。
- 不加 exporter → span 仅在内存、不外发（方案 b 的范围）。

## 实现：`OpenTelemetryTracePropagator`

用 OTel 原生 API（`OpenTelemetry` / `Tracer` / `TextMapPropagator`）+ 手动 MDC：

- **`inject()`**：`openTelemetry.getPropagators().getTextMapPropagator().inject(Context.current(), carrier, setter)`
  → 产出 `{"traceparent": "00-<traceId>-<spanId>-<flags>"}`（无 current span 时为空 Map）。
- **`restore(headers)`**：extract 出 parent Context → `tracer.spanBuilder("message-consume").setParent(extracted).startSpan()`
  → `makeCurrent()` → 手动 `MDC.put("traceId"/"spanId")`（因 micrometer `Slf4jEventListener` 只听 micrometer Tracer 事件，
  这里用 OTel 原生 span，MDC 必须手动填才能保证日志串联）。span+scope 存 ThreadLocal。
- **`clear()`**：`scope.close()` + `span.end()` + `MDC.remove` + 清 ThreadLocal。

> producer 侧不主动开 span（只传播 current context）——符合 §4.7「inject() 提取当前 trace context」。
> HTTP 发起的 chat 请求有 server span → inject 捕获 → consumer 继续 → 日志可串联；
> 后台任务无 current span → inject 空 → consumer 开新 root span（自身日志仍有 traceId）。

## bean 装配（MessagingAutoConfiguration）

```java
@Bean
TracePropagator tracePropagator(@Autowired(required = false) OpenTelemetry openTelemetry) {
    if (openTelemetry == null) {
        log.warn("OpenTelemetry bean absent (tracing auto-config 未生效) — TracePropagator 回退 NO_OP");
        return TracePropagator.NO_OP;
    }
    return new OpenTelemetryTracePropagator(openTelemetry);
}
```
- `OpenTelemetry` 存在（Spring Boot tracing auto-config 生效）→ 真 propagator；否则 NO_OP + WARN（**可见、非静默**）。

## log4j2.xml

PatternLayout 加 `%X{traceId} %X{spanId}`（占位为空时显示空串，无 span 的日志不受影响）。

## 行为变更（非破坏）

- 加 bridge + SDK 后，Spring Boot 自动给 **HTTP 请求开 server span**（in-memory，无 exporter）→ 所有请求日志开始带 traceId。这是方案 b 的预期 observability 收益，低风险。
- 单元测试直接 `new RocketMQMessageBus(..., NO_OP, ...)` 不受影响；Spring 上下文测试获得真 propagator（行为良好）。

## 升级到方案 a（未来）

仅加 `opentelemetry-exporter-otlp` + `management.otlp.tracing.endpoint` + collector。`OpenTelemetryTracePropagator` 一行不改。
