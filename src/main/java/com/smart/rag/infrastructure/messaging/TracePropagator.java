package com.smart.rag.infrastructure.messaging;

import java.util.Map;

/**
 * Trace propagation — propagates traceId/spanId between send and consume.
 * <p>
 * Producer: inject() extracts current trace context into Message.headers.
 * Consumer: restore() recovers trace context before calling listener.
 */
public interface TracePropagator {
    /** Extract trace info from current context for injection into Message.headers */
    Map<String, String> inject();

    /** Restore trace info from Message.headers to current thread context */
    void restore(Map<String, String> headers);

    /** Clear current thread's trace context (called after consume completes) */
    void clear();
}
