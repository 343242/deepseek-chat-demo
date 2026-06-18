package com.smart.rag.infrastructure.messaging;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OpenTelemetryTracePropagator 测试 — 验证 W3C traceparent 注入/提取 + MDC traceId 跨消息一致。
 */
class OpenTelemetryTracePropagatorTest {

    // 显式配 W3C 传播：OTel SDK 默认 propagator 是 noop（注入空）；生产侧由 Spring Boot tracing auto-config 默认配 W3C。
    private final OpenTelemetrySdk sdk = OpenTelemetrySdk.builder()
        .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
        .build();
    private final OpenTelemetryTracePropagator propagator = new OpenTelemetryTracePropagator(sdk);

    @AfterEach
    void cleanUp() {
        propagator.clear();
        MDC.clear();
    }

    @Test
    @DisplayName("inject→restore round trip：traceId 跨消息一致，MDC 填充，clear 清空")
    void roundTripPropagatesTraceId() {
        Tracer tracer = sdk.getTracer("test");
        Span producer = tracer.spanBuilder("producer").startSpan();
        try (Scope ignored = producer.makeCurrent()) {
            Map<String, String> headers = propagator.inject();
            assertThat(headers).containsKey("traceparent");

            propagator.restore(headers);
            assertThat(MDC.get("traceId")).isEqualTo(producer.getSpanContext().getTraceId());

            propagator.clear();
            assertThat(MDC.get("traceId")).isNull();
        } finally {
            producer.end();
        }
    }

    @Test
    @DisplayName("restore 无 headers：开新 root span，MDC 仍有 traceId")
    void restoreWithoutHeadersCreatesFreshTrace() {
        propagator.restore(Map.of());
        assertThat(MDC.get("traceId")).isNotBlank();
        propagator.clear();
        assertThat(MDC.get("traceId")).isNull();
    }

    @Test
    @DisplayName("无 current span 时 inject 不写 traceparent（不强制造 trace）")
    void injectWithoutCurrentSpanIsEmpty() {
        Map<String, String> headers = propagator.inject();
        assertThat(headers).doesNotContainKey("traceparent");
    }

    @Test
    @DisplayName("clear 未先 restore 时 no-op（不抛）")
    void clearWithoutRestoreIsNoOp() {
        propagator.clear();  // 不抛
    }
}
