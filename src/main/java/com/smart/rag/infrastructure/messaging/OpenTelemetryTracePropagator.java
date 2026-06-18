package com.smart.rag.infrastructure.messaging;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.context.propagation.TextMapSetter;
import org.slf4j.MDC;

import java.util.HashMap;
import java.util.Map;

/**
 * OpenTelemetry 实现（Phase D D-6 方案 b）：基于 Spring Boot 自动装配的 {@link OpenTelemetry}，
 * 用 W3C TraceContext 传播 traceId，消费端开 consumer span 并手动填充 MDC（traceId/spanId）。
 * <p>
 * <b>不带 exporter</b>——span 仅在内存，价值是 traceId 跨 send↔consume 串联日志。未来升级到方案 a
 * （接 OTLP collector）只需加 exporter + {@code management.otlp.tracing.endpoint}，本类不改。
 * <p>
 * <b>为何手动填 MDC</b>：micrometer 的 {@code Slf4jEventListener} 只监听 micrometer {@code Tracer}
 * 事件，而此处用 OTel 原生 span（取标准 W3C 传播），故 MDC 必须显式 {@code put}，
 * 才能保证 Log4j2 {@code %X{traceId}/%X{spanId}} 在消费日志中生效。
 */
public class OpenTelemetryTracePropagator implements TracePropagator {

    private static final String INSTRUMENTATION = "smart-rag-messaging";
    private static final String MDC_TRACE_ID = "traceId";
    private static final String MDC_SPAN_ID = "spanId";

    private final OpenTelemetry openTelemetry;
    private final Tracer tracer;

    /** 消费侧 hold 当前 scope/span，供 {@link #clear()} 释放。 */
    private static final ThreadLocal<ScopeState> CURRENT = new ThreadLocal<>();

    private static final TextMapSetter<Map<String, String>> SETTER = Map::put;
    private static final TextMapGetter<Map<String, String>> GETTER = new TextMapGetter<>() {
        @Override
        public Iterable<String> keys(Map<String, String> carrier) {
            return carrier.keySet();
        }

        @Override
        public String get(Map<String, String> carrier, String key) {
            return carrier == null ? null : carrier.get(key);
        }
    };

    public OpenTelemetryTracePropagator(OpenTelemetry openTelemetry) {
        this.openTelemetry = openTelemetry;
        this.tracer = openTelemetry.getTracer(INSTRUMENTATION);
    }

    /** 生产端：把当前 trace context（W3C {@code traceparent}）注入 headers。无 current span 时返回空 Map。 */
    @Override
    public Map<String, String> inject() {
        Map<String, String> carrier = new HashMap<>();
        openTelemetry.getPropagators().getTextMapPropagator()
            .inject(Context.current(), carrier, SETTER);
        return carrier;
    }

    /** 消费端：从 headers 提取 parent context，开 consumer span 并填 MDC。 */
    @Override
    public void restore(Map<String, String> headers) {
        Context extracted = openTelemetry.getPropagators().getTextMapPropagator()
            .extract(Context.current(), headers != null ? headers : Map.of(), GETTER);
        Span span = tracer.spanBuilder("message-consume")
            .setParent(extracted)
            .startSpan();
        Scope scope = span.makeCurrent();
        MDC.put(MDC_TRACE_ID, span.getSpanContext().getTraceId());
        MDC.put(MDC_SPAN_ID, span.getSpanContext().getSpanId());
        CURRENT.set(new ScopeState(scope, span));
    }

    /** 消费完成：关 scope、end span、清 MDC。未先 {@link #restore} 时为 no-op（幂等）。 */
    @Override
    public void clear() {
        ScopeState state = CURRENT.get();
        if (state == null) {
            return;
        }
        try {
            state.scope().close();
            state.span().end();
        } finally {
            MDC.remove(MDC_TRACE_ID);
            MDC.remove(MDC_SPAN_ID);
            CURRENT.remove();
        }
    }

    private record ScopeState(Scope scope, Span span) {}
}
