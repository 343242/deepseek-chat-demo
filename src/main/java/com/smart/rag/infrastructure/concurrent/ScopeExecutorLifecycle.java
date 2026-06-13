package com.smart.rag.infrastructure.concurrent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;

/**
 * Subtask cancellation, termination waiting, and owned-executor shutdown.
 *
 * <p>This is the lowest-level collaborator: it does not depend on
 * {@link ScopeJoinEngine}, {@link ScopeTimeoutHandler}, or {@link ScopeReporter}.
 */
final class ScopeExecutorLifecycle {

    private static final Logger log = LoggerFactory.getLogger(ScopeExecutorLifecycle.class);

    private final ScopeContext ctx;

    ScopeExecutorLifecycle(ScopeContext ctx) {
        this.ctx = ctx;
    }

    void cancelUnfinished() {
        ctx.state.internalSubtasks().stream()
                .filter(task -> !task.isTerminal())
                .forEach(DefaultSubtask::cancel);
    }

    void waitForTermination(java.time.Duration timeout, boolean preserveInterrupt) {
        long deadlineNanos = System.nanoTime() + timeout.toNanos();
        waitForTerminationRemaining(deadlineNanos, preserveInterrupt);
    }

    void waitForTerminationRemaining(long deadlineNanos, boolean preserveInterrupt) {
        for (DefaultSubtask<?> subtask : ctx.state.internalSubtasks()) {
            long remaining = deadlineNanos - System.nanoTime();
            if (remaining <= 0) {
                log.warn("TaskScope '{}' close timed out before subtask '{}' terminated",
                        ctx.options.name(), subtask.name());
                return;
            }
            try {
                boolean terminated = subtask.awaitTermination(remaining, TimeUnit.NANOSECONDS);
                if (!terminated) {
                    log.warn("TaskScope '{}' subtask '{}' did not terminate within closeTimeout={}",
                            ctx.options.name(), subtask.name(), ctx.options.closeTimeout());
                    return;
                }
            } catch (InterruptedException ex) {
                if (preserveInterrupt) {
                    Thread.currentThread().interrupt();
                }
                log.warn("TaskScope '{}' interrupted while waiting for subtask '{}' termination",
                        ctx.options.name(), subtask.name());
                return;
            }
        }
    }

    void shutdownOwnedExecutor(long closeDeadlineNanos) {
        ctx.executor.shutdown();
        long remaining = closeDeadlineNanos - System.nanoTime();
        if (remaining <= 0) {
            log.warn("TaskScope '{}' closeTimeout elapsed before executor termination wait, forcing shutdownNow",
                    ctx.options.name());
            ctx.executor.shutdownNow();
            return;
        }
        try {
            if (!ctx.executor.awaitTermination(remaining, TimeUnit.NANOSECONDS)) {
                log.warn("TaskScope '{}' executor did not terminate within closeTimeout={}, forcing shutdownNow",
                        ctx.options.name(), ctx.options.closeTimeout());
                ctx.executor.shutdownNow();
            }
        } catch (InterruptedException ex) {
            log.warn("TaskScope '{}' interrupted while awaiting executor termination", ctx.options.name());
            ctx.executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
