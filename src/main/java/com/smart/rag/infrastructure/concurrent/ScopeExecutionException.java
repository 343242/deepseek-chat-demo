package com.smart.rag.infrastructure.concurrent;

import java.util.List;

public class ScopeExecutionException extends RuntimeException {

    private final List<Throwable> unacceptableFailures;

    public ScopeExecutionException(String scopeName, List<? extends Throwable> failures) {
        super("并发作用域执行失败: " + scopeName, requireFirstFailure(failures));
        this.unacceptableFailures = List.copyOf(requireNonEmpty(failures));
        this.unacceptableFailures.stream()
                .skip(1)
                .forEach(this::addSuppressed);
    }

    /**
     * The failures that the active {@link com.smart.rag.infrastructure.concurrent.ScopePolicy}
     * deems unacceptable. Depending on policy this may be a subset of all
     * failures (e.g. for {@code PARTIAL_SUCCESS_OR_THROW} / {@code SHUTDOWN_ON_SUCCESS},
     * if at least one branch succeeded no failure is unacceptable).
     *
     * <p>P1-5: previously named {@code allFailures()} which was misleading — the
     * returned list is not always the full failure set.
     */
    public List<Throwable> unacceptableFailures() {
        return unacceptableFailures;
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
