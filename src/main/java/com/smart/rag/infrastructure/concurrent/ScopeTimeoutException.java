package com.smart.rag.infrastructure.concurrent;

import java.time.Duration;
import java.util.List;

public class ScopeTimeoutException extends ScopeExecutionException {

    private final Duration timeout;

    public ScopeTimeoutException(String scopeName, Duration timeout, List<? extends Throwable> knownFailures) {
        super(scopeName, failures(scopeName, timeout, knownFailures));
        this.timeout = timeout;
    }

    public Duration timeout() {
        return timeout;
    }

    private static List<Throwable> failures(
            String scopeName,
            Duration timeout,
            List<? extends Throwable> knownFailures
    ) {
        ScopeTimedOutCause timeoutCause = new ScopeTimedOutCause(scopeName, timeout);
        if (knownFailures == null || knownFailures.isEmpty()) {
            return List.of(timeoutCause);
        }
        return java.util.stream.Stream.concat(
                java.util.stream.Stream.of(timeoutCause),
                knownFailures.stream()
        ).map(Throwable.class::cast).toList();
    }

    private static final class ScopeTimedOutCause extends RuntimeException {

        private ScopeTimedOutCause(String scopeName, Duration timeout) {
            super("并发作用域等待超时: " + scopeName + ", timeout=" + timeout);
        }
    }
}
