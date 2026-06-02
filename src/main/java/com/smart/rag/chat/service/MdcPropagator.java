package com.smart.rag.chat.service;

import org.slf4j.MDC;

import java.util.Map;

/**
 * MDC 上下文传播工具 — 用于 Reactive 流中的 traceId 等上下文传播。
 */
public final class MdcPropagator {

    private MdcPropagator() {}

    /**
     * 捕获当前线程的 MDC 上下文快照
     */
    public static Map<String, String> capture() {
        return MDC.getCopyOfContextMap();
    }

    /**
     * 恢复 MDC 上下文到当前线程
     */
    public static void restore(Map<String, String> context) {
        if (context != null) {
            MDC.setContextMap(context);
        } else {
            MDC.clear();
        }
    }

    /**
     * 清除当前线程的 MDC 上下文
     */
    public static void clear() {
        MDC.clear();
    }
}
