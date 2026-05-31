package com.smart.rag.common.concurrent.context;

import org.slf4j.MDC;

import java.util.Map;

public final class MdcContextCarrier implements ContextCarrier<Map<String, String>> {

    @Override
    public Map<String, String> capture() {
        return MDC.getCopyOfContextMap();
    }

    @Override
    public Map<String, String> restore(Map<String, String> snapshot) {
        Map<String, String> previous = MDC.getCopyOfContextMap();
        if (snapshot == null || snapshot.isEmpty()) {
            MDC.clear();
        } else {
            MDC.setContextMap(snapshot);
        }
        return previous;
    }

    @Override
    public void clear(Map<String, String> previous) {
        if (previous == null || previous.isEmpty()) {
            MDC.clear();
        } else {
            MDC.setContextMap(previous);
        }
    }
}
