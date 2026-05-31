package com.smart.rag.common.concurrent;

import java.time.Duration;

public record ScopeReport(
        String scopeName,
        Duration elapsed,
        int taskCount,
        long successCount,
        long failedCount,
        long cancelledCount,
        String slowestTaskName
) {}
