package com.smart.rag.infrastructure.concurrent;

import com.smart.rag.infrastructure.concurrent.context.ContextAwareCallable;
import com.smart.rag.infrastructure.concurrent.context.ContextSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Fork submission and join loop driver.
 *
 * <p>P0-3 fix: {@link #fork(String, Callable)} submits to the executor BEFORE
 * registering the subtask on {@link ScopeContext#state}. If {@code submit}
 * throws {@link RejectedExecutionException}, the subtask is marked cancelled
 * and terminated, then added to state so {@code close()} can still account
 * for it. The exception is rethrown wrapped as {@link ScopeExecutionException}.
 */
final class ScopeJoinEngine {

    private static final Logger log = LoggerFactory.getLogger(ScopeJoinEngine.class);

    private final ScopeContext ctx;
    private final ScopeLifecycle lifecycle;
    private final ScopeTimeoutHandler timeoutHandler;
    private final ScopeExecutorLifecycle executorLifecycle;

    ScopeJoinEngine(
            ScopeContext ctx,
            ScopeLifecycle lifecycle,
            ScopeTimeoutHandler timeoutHandler,
            ScopeExecutorLifecycle executorLifecycle
    ) {
        this.ctx = ctx;
        this.lifecycle = lifecycle;
        this.timeoutHandler = timeoutHandler;
        this.executorLifecycle = executorLifecycle;
    }

    <T> Subtask<T> fork(String name, Callable<T> task) {
        lifecycle.ensureOwner("fork");
        if (lifecycle.isClosed()) {
            throw new ScopeClosedException(
                    "TaskScope '" + ctx.options.name() + "' is already closed");
        }
        if (lifecycle.isJoined()) {
            throw new ScopeClosedException(
                    "TaskScope '" + ctx.options.name() + "' has already joined");
        }

        DefaultSubtask<T> subtask = new DefaultSubtask<>(name);
        ContextSnapshot snapshot = ContextSnapshot.capture(ctx.contextCarriers);
        Callable<T> contextAware = new ContextAwareCallable<>(task, snapshot);
        Callable<T> observed = new ObservedCallable<>(
                ctx.options.name(), withConcurrencyLimit(contextAware), subtask);

        Future<T> future;
        try {
            future = ctx.executor.submit(ScopeNestingGuard.scopedSubtask(observed));
        } catch (RejectedExecutionException ex) {
            // P0-3: executor rejected the task. Cancel the subtask bookkeeping,
            // register it on state so close() can still account for it, then
            // surface as a ScopeExecutionException (not a raw REE).
            subtask.cancel();
            ctx.state.add(subtask);
            throw new ScopeExecutionException(ctx.options.name(), List.of(ex));
        }
        subtask.attachFuture(future);
        ctx.state.add(subtask);
        return subtask;
    }

    void joinInternal(Duration timeout) {
        lifecycle.ensureOwner("join");
        if (lifecycle.isClosed()) {
            throw new ScopeClosedException(
                    "TaskScope '" + ctx.options.name() + "' is already closed");
        }
        lifecycle.markJoined();

        long deadlineNanos = timeout == null
                ? Long.MAX_VALUE
                : System.nanoTime() + timeout.toNanos();
        try {
            while (!ctx.state.allTerminal()) {
                drainCompletedSignalsOnOwnerThread();
                if (ctx.policyHandler.shouldStop(ctx.state)) {
                    executorLifecycle.cancelUnfinished();
                    executorLifecycle.waitForTermination(ctx.options.closeTimeout(), false);
                    drainCompletedSignalsOnOwnerThread();
                    break;
                }

                List<CompletableFuture<DefaultSubtask<?>>> activeSignals = activeCompletionSignals();
                if (activeSignals.isEmpty()) {
                    break;
                }

                CompletableFuture<?> any = CompletableFuture.anyOf(
                        activeSignals.toArray(CompletableFuture[]::new));
                try {
                    if (timeout == null) {
                        any.get();
                    } else {
                        long remaining = deadlineNanos - System.nanoTime();
                        if (remaining <= 0) {
                            timeoutHandler.onTimeout(timeout);
                            return;
                        }
                        any.get(remaining, TimeUnit.NANOSECONDS);
                    }
                } catch (TimeoutException ex) {
                    timeoutHandler.onTimeout(timeout);
                    return;
                }
            }
            drainCompletedSignalsOnOwnerThread();
        } catch (InterruptedException ex) {
            executorLifecycle.cancelUnfinished();
            executorLifecycle.waitForTermination(ctx.options.closeTimeout(), true);
            Thread.currentThread().interrupt();
            throw new ScopeExecutionException(ctx.options.name(), List.of(ex));
        } catch (ExecutionException ex) {
            // completionSignal always completes normally -- this should not happen
            log.warn("Unexpected ExecutionException in joinInternal for scope '{}'",
                    ctx.options.name(), ex);
            drainCompletedSignalsOnOwnerThread();
        } catch (ScopeTimeoutException ex) {
            // P1-7: onTimeout threw — the join did not complete successfully.
            // Roll back the joined flag so a subsequent join() retry is allowed
            // (markJoined was set early to prevent concurrent re-entry).
            lifecycle.rollbackJoined();
            throw ex;
        }
    }

    void drainCompletedSignalsOnOwnerThread() {
        for (DefaultSubtask<?> subtask : ctx.state.internalSubtasks()) {
            if (!subtask.isTerminal() || !subtask.markProcessedByOwner()) {
                continue;
            }
            if (subtask.state() == TaskState.SUCCESS) {
                ctx.policyHandler.onSuccess(subtask, ctx.state);
            } else if (subtask.state() == TaskState.FAILED) {
                ctx.policyHandler.onFailure(subtask, subtask.failure(), ctx.state);
            }
        }
    }

    private List<CompletableFuture<DefaultSubtask<?>>> activeCompletionSignals() {
        return ctx.state.internalSubtasks().stream()
                .filter(task -> !task.isTerminal())
                .map(DefaultSubtask::completionSignal)
                .toList();
    }

    private <T> Callable<T> withConcurrencyLimit(Callable<T> delegate) {
        if (ctx.concurrencyLimit == null) {
            return delegate;
        }
        return () -> {
            boolean acquired = false;
            try {
                ctx.concurrencyLimit.acquire();
                acquired = true;
                return delegate.call();
            } finally {
                if (acquired) {
                    ctx.concurrencyLimit.release();
                }
            }
        };
    }
}
