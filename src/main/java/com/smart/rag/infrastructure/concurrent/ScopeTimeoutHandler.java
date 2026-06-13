package com.smart.rag.infrastructure.concurrent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;

/**
 * Timeout decision and failure classification per {@link ScopePolicy}.
 *
 * <p>{@link #onTimeout(Duration)} is invoked by {@link ScopeJoinEngine} when the
 * join deadline elapses. It triggers executor cancellation, then throws
 * {@link ScopeTimeoutException} if the policy demands it.
 */
final class ScopeTimeoutHandler {

    private static final Logger log = LoggerFactory.getLogger(ScopeTimeoutHandler.class);

    private final ScopeContext ctx;
    private final ScopeExecutorLifecycle executorLifecycle;

    ScopeTimeoutHandler(ScopeContext ctx, ScopeExecutorLifecycle executorLifecycle) {
        this.ctx = ctx;
        this.executorLifecycle = executorLifecycle;
    }

    void onTimeout(Duration timeout) {
        ctx.policyHandler.onTimeout(ctx.state);
        executorLifecycle.cancelUnfinished();
        executorLifecycle.waitForTermination(ctx.options.closeTimeout(), false);
        // Drain any completion signals that fired during cancel/wait so the
        // ScopeReporter summary reflects post-cancel state.
        for (DefaultSubtask<?> subtask : ctx.state.internalSubtasks()) {
            subtask.markProcessedByOwner();
        }
        log.warn("TaskScope '{}' timed out after {}", ctx.options.name(), timeout);
        if (shouldTimeoutThrow()) {
            throw new ScopeTimeoutException(ctx.options.name(), timeout, ctx.state.failures());
        }
    }

    private boolean shouldTimeoutThrow() {
        return switch (ctx.options.policy()) {
            case SHUTDOWN_ON_FAILURE -> true;
            case SHUTDOWN_ON_SUCCESS, PARTIAL_SUCCESS_OR_THROW -> ctx.state.successCount() == 0;
            case QUORUM_SUCCESS -> ctx.state.successCount() < ctx.options.quorumSuccessCount();
            case COLLECT_ALL -> false;
        };
    }

    List<Throwable> unacceptableFailures() {
        List<Throwable> failures = ctx.state.failures();
        if (failures.isEmpty()) {
            return List.of();
        }
        return switch (ctx.options.policy()) {
            case SHUTDOWN_ON_FAILURE, COLLECT_ALL -> failures;
            case SHUTDOWN_ON_SUCCESS, PARTIAL_SUCCESS_OR_THROW ->
                    ctx.state.successCount() > 0 ? List.of() : failures;
            case QUORUM_SUCCESS ->
                    ctx.state.successCount() >= ctx.options.quorumSuccessCount() ? List.of() : failures;
        };
    }
}
