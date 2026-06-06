package com.smart.rag.infrastructure.messaging;

import java.util.Map;

/**
 * Trace propagation — propagates traceId/spanId between send and consume.
 * <p>
 * Producer: inject() extracts current trace context into Message.headers.
 * Consumer: restore() recovers trace context before calling listener.
 */
public interface TracePropagator {

    /** No-op implementation — used when no tracing infrastructure is available */
    TracePropagator NO_OP = new TracePropagator() {
        @Override public Map<String, String> inject() { return Map.of(); }
        @Override public void restore(Map<String, String> headers) { }
        @Override public void clear() { }
    };

    /** Extract trace info from current context for injection into Message.headers */
    Map<String, String> inject();

    /** Restore trace info from Message.headers to current thread context */
    void restore(Map<String, String> headers);

    /** Clear current thread's trace context (called after consume completes) */
    void clear();
}
