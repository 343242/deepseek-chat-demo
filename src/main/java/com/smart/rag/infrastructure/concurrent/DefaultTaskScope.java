package com.smart.rag.infrastructure.concurrent;

import com.smart.rag.infrastructure.concurrent.context.ContextCarrier;
import com.smart.rag.infrastructure.concurrent.policy.CollectAllPolicy;
import com.smart.rag.infrastructure.concurrent.policy.PartialSuccessOrThrowPolicy;
import com.smart.rag.infrastructure.concurrent.policy.QuorumSuccessPolicy;
import com.smart.rag.infrastructure.concurrent.policy.ScopePolicyHandler;
import com.smart.rag.infrastructure.concurrent.policy.ShutdownOnFailurePolicy;
import com.smart.rag.infrastructure.concurrent.policy.ShutdownOnSuccessPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.ref.Cleaner;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Facade for a structured-concurrency scope. Delegates lifecycle, join loop,
 * timeout decision, executor management, and reporting to five package-private
 * collaborators ({@link ScopeLifecycle}, {@link ScopeJoinEngine},
 * {@link ScopeTimeoutHandler}, {@link ScopeExecutorLifecycle}, {@link ScopeReporter}).
 *
 * <p>Public API ({@link #fork}, {@link #join}, {@link #joinUntil},
 * {@link #throwIfFailed}, {@link #subtasks}, {@link #close}) preserves
 * signature compatibility — existing callers are unaffected.
 */
public final class DefaultTaskScope implements TaskScope {

    private static final Logger log = LoggerFactory.getLogger(DefaultTaskScope.class);
    private static final Cleaner CLEANER = Cleaner.create();

    private final ScopeContext ctx;
    private final ScopeLifecycle lifecycle;
    private final ScopeExecutorLifecycle executorLifecycle;
    private final ScopeTimeoutHandler timeoutHandler;
    private final ScopeJoinEngine joinEngine;
    private final ScopeReporter reporter;
    private final Cleaner.Cleanable cleanable;

    public DefaultTaskScope(
            ScopeOptions options,
            ExecutorService executor,
            List<ContextCarrier<?>> contextCarriers
    ) {
        this(options, executor, contextCarriers, ScopeObserver.NOOP);
    }

    public DefaultTaskScope(
            ScopeOptions options,
            ExecutorService executor,
            List<ContextCarrier<?>> contextCarriers,
            ScopeObserver scopeObserver
    ) {
        Objects.requireNonNull(options, "options must not be null");
        Objects.requireNonNull(executor, "executor must not be null");
        Objects.requireNonNull(scopeObserver, "scopeObserver must not be null");
        List<ContextCarrier<?>> carriersCopy = List.copyOf(contextCarriers);

        ScopePolicyHandler policyHandler = policyHandlerFor(options);
        Semaphore concurrencyLimit = options.maxConcurrency() > 0
                ? new Semaphore(options.maxConcurrency())
                : null;
        long startNanos = System.nanoTime();
        long scopeId = ScopeNestingGuard.scopeOpened();

        ScopeState state = new ScopeState();
        AtomicBoolean closed = new AtomicBoolean();
        AtomicBoolean joined = new AtomicBoolean();
        AtomicBoolean failuresHandled = new AtomicBoolean();

        this.ctx = new ScopeContext(
                options, executor, state, scopeId, startNanos, Thread.currentThread(),
                closed, joined, failuresHandled, concurrencyLimit,
                policyHandler, scopeObserver, carriersCopy);
        this.lifecycle = new ScopeLifecycle(ctx);
        this.executorLifecycle = new ScopeExecutorLifecycle(ctx);
        this.timeoutHandler = new ScopeTimeoutHandler(ctx, executorLifecycle);
        this.joinEngine = new ScopeJoinEngine(ctx, lifecycle, timeoutHandler, executorLifecycle);
        this.reporter = new ScopeReporter(ctx, lifecycle);

        // P0-8: ScopeCleanupState is a static class holding only what cleanup needs;
        // it does NOT reference DefaultTaskScope, so the scope remains phantom-reachable.
        this.cleanable = CLEANER.register(this, new ScopeCleanupState(
                options.name(), state, executor, options.executorOwnedByScope(), closed));
    }

    private static ScopePolicyHandler policyHandlerFor(ScopeOptions options) {
        return switch (options.policy()) {
            case SHUTDOWN_ON_FAILURE -> new ShutdownOnFailurePolicy();
            case SHUTDOWN_ON_SUCCESS -> new ShutdownOnSuccessPolicy();
            case COLLECT_ALL -> new CollectAllPolicy();
            case PARTIAL_SUCCESS_OR_THROW -> new PartialSuccessOrThrowPolicy();
            case QUORUM_SUCCESS -> new QuorumSuccessPolicy(options.quorumSuccessCount());
        };
    }

    @Override
    public <T> Subtask<T> fork(String name, Callable<T> task) {
        return joinEngine.fork(name, task);
    }

    @Override
    public void join() {
        Duration timeout = ctx.options.defaultTimeout();
        if (timeout == ScopeOptions.NO_TIMEOUT) {
            log.debug("TaskScope '{}' joining without timeout (defaultTimeout=NO_TIMEOUT)", ctx.options.name());
            joinEngine.joinInternal(null);
        } else {
            joinEngine.joinInternal(timeout);
        }
    }

    @Override
    public void joinUntil(Duration timeout) {
        if (timeout == null || timeout.isNegative() || timeout.isZero()) {
            throw new ScopeViolationException("joinUntil timeout must be positive");
        }
        joinEngine.joinInternal(timeout);
    }

    @Override
    public void throwIfFailed() {
        lifecycle.ensureOwner("throwIfFailed");
        lifecycle.markFailuresHandled();
        List<Throwable> failures = timeoutHandler.unacceptableFailures();
        if (!failures.isEmpty()) {
            throw new ScopeExecutionException(ctx.options.name(), failures);
        }
    }

    @Override
    public List<Subtask<?>> subtasks() {
        return ctx.state.publicSubtasks();
    }

    @Override
    public void close() {
        lifecycle.ensureOwner("close");
        if (!lifecycle.markClosed()) {
            return;
        }

        long closeDeadlineNanos = System.nanoTime() + ctx.options.closeTimeout().toNanos();
        try {
            executorLifecycle.cancelUnfinished();

            // M3: Save and clear interrupt flag so waitForTermination is not short-circuited
            boolean wasInterrupted = Thread.interrupted();
            try {
                executorLifecycle.waitForTerminationRemaining(closeDeadlineNanos, false);
            } finally {
                if (wasInterrupted) {
                    Thread.currentThread().interrupt();
                }
            }

            reporter.warnAboutUnhandledCollectAllFailures();
            reporter.logScopeSummary();
        } finally {
            // H1 + H2: executor.shutdown in finally, with awaitTermination
            if (ctx.options.executorOwnedByScope()) {
                executorLifecycle.shutdownOwnedExecutor(closeDeadlineNanos);
            }
            ScopeNestingGuard.scopeClosed(ctx.scopeId);
            cleanable.clean();
        }
    }
}
