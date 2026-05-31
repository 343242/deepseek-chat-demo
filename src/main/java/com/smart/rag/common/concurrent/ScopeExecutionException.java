package com.smart.rag.common.concurrent;

import java.util.List;

public class ScopeExecutionException extends RuntimeException {

    private final List<Throwable> allFailures;

    public ScopeExecutionException(String scopeName, List<? extends Throwable> failures) {
        super("并发作用域执行失败: " + scopeName, firstFailure(failures));
        if (failures == null || failures.isEmpty()) {
            throw new ScopeViolationException("ScopeExecutionException requires at least one failure");
        }
        this.allFailures = List.copyOf(failures);
        this.allFailures.stream()
                .skip(1)
                .forEach(this::addSuppressed);
    }

    public List<Throwable> allFailures() {
        return allFailures;
    }

    private static Throwable firstFailure(List<? extends Throwable> failures) {
        if (failures == null || failures.isEmpty()) {
            return null;
        }
        return failures.getFirst();
    }
}
