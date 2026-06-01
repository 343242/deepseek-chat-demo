package com.smart.rag.infrastructure.concurrent;

import java.util.List;

public class ScopeExecutionException extends RuntimeException {

    private final List<Throwable> allFailures;

    public ScopeExecutionException(String scopeName, List<? extends Throwable> failures) {
        super("并发作用域执行失败: " + scopeName, requireFirstFailure(failures));
        this.allFailures = List.copyOf(requireNonEmpty(failures));
        this.allFailures.stream()
                .skip(1)
                .forEach(this::addSuppressed);
    }

    public List<Throwable> allFailures() {
        return allFailures;
    }

    private static List<? extends Throwable> requireNonEmpty(List<? extends Throwable> failures) {
        if (failures == null || failures.isEmpty()) {
            throw new ScopeViolationException("ScopeExecutionException requires at least one failure");
        }
        return failures;
    }

    private static Throwable requireFirstFailure(List<? extends Throwable> failures) {
        return requireNonEmpty(failures).getFirst();
    }
}
