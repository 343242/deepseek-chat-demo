package com.smart.rag.infrastructure.concurrent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Cleaner action invoked when a {@link DefaultTaskScope} is garbage-collected
 * without an explicit {@code close()} call (the try-with-resources safety net).
 *
 * <p><b>P0-8 fix:</b> previously the cleanup action only logged a warning — subtasks
 * kept running and owned executors leaked forever. This implementation cancels
 * all non-terminal subtasks; for owned executors it additionally forces
 * {@code shutdownNow()}. For {@link ExecutorMode#SHARED_EXECUTOR} (where the
 * scope does not own the executor) it only cancels subtasks and logs a warning
 * to avoid disrupting other scopes that may still be using the shared pool.
 *
 * <p>This class MUST NOT hold a strong reference to {@link DefaultTaskScope}
 * itself, otherwise the scope can never become phantom-reachable and the
 * cleaner never fires. It only holds the collaborators it needs to clean up.
 */
final class ScopeCleanupState implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(ScopeCleanupState.class);

    private final String scopeName;
    private final ScopeState state;
    private final java.util.concurrent.ExecutorService executor;
    private final boolean executorOwnedByScope;
    private final AtomicBoolean closed;

    ScopeCleanupState(
            String scopeName,
            ScopeState state,
            java.util.concurrent.ExecutorService executor,
            boolean executorOwnedByScope,
            AtomicBoolean closed
    ) {
        this.scopeName = scopeName;
        this.state = state;
        this.executor = executor;
        this.executorOwnedByScope = executorOwnedByScope;
        this.closed = closed;
    }

    @Override
    public void run() {
        if (closed.get()) {
            // Scope was closed explicitly; nothing to clean up.
            return;
        }
        log.warn("TaskScope '{}' was never explicitly closed. Cleaning up resources. "
                + "Always use try-with-resources: try (var scope = ...) {{ ... }}", scopeName);

        // Cancel all non-terminal subtasks (interrupts workers if responsive).
        try {
            state.internalSubtasks().stream()
                    .filter(task -> !task.isTerminal())
                    .forEach(DefaultSubtask::cancel);
        } catch (RuntimeException ex) {
            log.warn("TaskScope '{}' cleanup: cancel subtasks threw", scopeName, ex);
        }

        if (executorOwnedByScope) {
            // Owned executor: force shutdown so worker threads terminate.
            try {
                executor.shutdownNow();
            } catch (RuntimeException ex) {
                log.warn("TaskScope '{}' cleanup: executor.shutdownNow threw", scopeName, ex);
            }
        } else {
            // SHARED_EXECUTOR: do NOT shut down the shared pool — other scopes
            // may still be using it. Only the subtask cancel above runs.
            log.debug("TaskScope '{}' uses SHARED_EXECUTOR; leaving shared pool intact", scopeName);
        }
    }
}
