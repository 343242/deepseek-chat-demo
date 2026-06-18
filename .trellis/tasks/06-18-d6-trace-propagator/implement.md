# Implement — D-6 方案 b

## 步骤

1. **pom.xml**：加依赖（版本由 parent BOM 管）
   - `io.micrometer:micrometer-tracing-bridge-otel`
   - `io.opentelemetry:opentelemetry-sdk`
2. **OpenTelemetryTracePropagator.java**（新，`infrastructure/messaging/`）：OTel propagator inject/extract + consumer span + 手动 MDC + ThreadLocal 存 scope/span。
3. **MessagingAutoConfiguration.java**：加 `@Bean TracePropagator tracePropagator(@Autowired(required=false) OpenTelemetry)` → 真 impl 或 NO_OP+WARN。
4. **log4j2.xml**：PatternLayout 加 `%X{traceId} %X{spanId}`。
5. **OpenTelemetryTracePropagatorTest.java**（新）：
   - inject→restore round trip：建 producer span → inject 含 traceparent → restore 后 MDC traceId == producer traceId → clear 后 MDC 清空
   - restore 空 headers：仍有 traceId（新 root span）→ clear 清空
   - clear 幂等（无 restore 直接 clear 不抛）
6. **验证**：`./mvnw test -Dtest=OpenTelemetryTracePropagatorTest` + `./mvnw compile`；确认加依赖后既有 messaging 测试不破（PushConsumerListenerTest / SimpleConsumerReceiveLoopTest / RocketMQMessageBusTest 仍 NO_OP 直构）。
7. grep 核验 + commit（不 push）。

## 风险/回滚
- 依赖加错（OpenTelemetry bean 缺失）→ WARN 日志可见、回退 NO_OP，不静默；test 用 `OpenTelemetrySdk.builder()` 验证逻辑。
- 回滚：单 commit，revert 即恢复 NO_OP + 无依赖。
