package com.smart.rag.common.concurrent;

import com.smart.rag.common.concurrent.context.ContextAwareCallable;
import com.smart.rag.common.concurrent.context.ContextCarrier;
import com.smart.rag.common.concurrent.context.ContextSnapshot;
import com.smart.rag.common.concurrent.policy.CollectAllPolicy;
import com.smart.rag.common.concurrent.policy.PartialSuccessOrThrowPolicy;
import com.smart.rag.common.concurrent.policy.QuorumSuccessPolicy;
import com.smart.rag.common.concurrent.policy.ScopePolicyHandler;
import com.smart.rag.common.concurrent.policy.ShutdownOnFailurePolicy;
import com.smart.rag.common.concurrent.policy.ShutdownOnSuccessPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.ref.Cleaner;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

public final class DefaultTaskScope implements TaskScope {

    private static final Logger log = LoggerFactory.getLogger(DefaultTaskScope.class);
    private static final Cleaner CLEANER = Cleaner.create();

    private final ScopeOptions options;
    private final ExecutorService executor;
    private final List<ContextCarrier<?>> contextCarriers;
    private final ScopePolicyHandler policyHandler;
    private final Thread ownerThread = Thread.currentThread();
    private final ScopeState state = new ScopeState();
    private final long startNanos = System.nanoTime();
    private final Semaphore concurrencyLimit;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean joined = new AtomicBoolean();
    private final AtomicBoolean failuresHandled = new AtomicBoolean();
    private final Cleaner.Cleanable cleanable;
    private final ScopeObserver scopeObserver;

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
        this.options = Objects.requireNonNull(options, "options must not be null");
        this.executor = Objects.requireNonNull(executor, "executor must not be null");
        this.contextCarriers = List.copyOf(contextCarriers);
        this.scopeObserver = Objects.requireNonNull(scopeObserver, "scopeObserver must not be null");
        this.policyHandler = switch (options.policy()) {
            case SHUTDOWN_ON_FAILURE -> new ShutdownOnFailurePolicy();
            case SHUTDOWN_ON_SUCCESS -> new ShutdownOnSuccessPolicy();
            case COLLECT_ALL -> new CollectAllPolicy();
            case PARTIAL_SUCCESS_OR_THROW -> new PartialSuccessOrThrowPolicy();
            case QUORUM_SUCCESS -> new QuorumSuccessPolicy(options.quorumSuccessCount());
        };
        this.concurrencyLimit = options.maxConcurrency() > 0 ? new Semaphore(options.maxConcurrency()) : null;
        this.cleanable = CLEANER.register(this, new ScopeCleanup(options.name(), closed));
        ScopeNestingGuard.scopeOpened();
    }

    @Override
    public <T> Subtask<T> fork(String name, Callable<T> task) {
        ensureOwner("fork");
        if (closed.get()) {
            throw new ScopeClosedException("TaskScope '" + options.name() + "' is already closed");
        }
        if (joined.get()) {
            throw new ScopeClosedException("TaskScope '" + options.name() + "' has already joined");
        }

        DefaultSubtask<T> subtask = new DefaultSubtask<>(name);
        ContextSnapshot snapshot = ContextSnapshot.capture(contextCarriers);
        Callable<T> contextAware = new ContextAwareCallable<>(task, snapshot);
        Callable<T> observed = new ObservedCallable<>(options.name(), withConcurrencyLimit(contextAware), subtask);
        state.add(subtask);
        Future<T> future = executor.submit(ScopeNestingGuard.scopedSubtask(observed));
        subtask.attachFuture(future);
        return subtask;
    }

    @Override
    public void join() {
        Duration timeout = options.defaultTimeout();
        if (timeout.isZero()) {
            log.debug("TaskScope '{}' joining without timeout (defaultTimeout=ZERO)", options.name());
            joinInternal(null);
        } else {
            joinInternal(timeout);
        }
    }

    @Override
    public void joinUntil(Duration timeout) {
        if (timeout == null || timeout.isNegative() || timeout.isZero()) {
            throw new ScopeViolationException("joinUntil timeout must be positive");
        }
        joinInternal(timeout);
    }

    @Override
    public void throwIfFailed() {
        ensureOwner("throwIfFailed");
        failuresHandled.set(true);
        List<Throwable> failures = unacceptableFailures();
        if (!failures.isEmpty()) {
            throw new ScopeExecutionException(options.name(), failures);
        }
    }

    @Override
    public List<Subtask<?>> subtasks() {
        return state.publicSubtasks();
    }

    @Override
    public void close() {
        ensureOwner("close");
        if (!closed.compareAndSet(false, true)) {
            return;
        }

        long closeDeadlineNanos = System.nanoTime() + options.closeTimeout().toNanos();
        try {
            cancelUnfinished();

            // M3: Save and clear interrupt flag so waitForTermination is not short-circuited
            boolean wasInterrupted = Thread.interrupted();
            try {
                waitForTerminationRemaining(closeDeadlineNanos, false);
            } finally {
                if (wasInterrupted) {
                    Thread.currentThread().interrupt();
                }
            }

            warnAboutUnhandledCollectAllFailures();
            logScopeSummary();
        } finally {
            // H1 + H2: executor.shutdown in finally, with awaitTermination
            if (options.executorOwnedByScope()) {
                shutdownOwnedExecutor(closeDeadlineNanos);
            }
            ScopeNestingGuard.scopeClosed();
            cleanable.clean();
        }
    }

    private void joinInternal(Duration timeout) {
        ensureOwner("join");
        if (closed.get()) {
            throw new ScopeClosedException("TaskScope '" + options.name() + "' is already closed");
        }
        if (!joined.compareAndSet(false, true)) {
            throw new ScopeClosedException("TaskScope '" + options.name() + "' has already joined");
        }

        long deadlineNanos = timeout == null ? Long.MAX_VALUE : System.nanoTime() + timeout.toNanos();
        try {
            while (!state.allTerminal()) {
                drainCompletedSignalsOnOwnerThread();
                if (policyHandler.shouldStop(state)) {
                    cancelUnfinished();
                    waitForTermination(options.closeTimeout(), false);
                    drainCompletedSignalsOnOwnerThread();
                    break;
                }

                List<CompletableFuture<DefaultSubtask<?>>> activeSignals = activeCompletionSignals();
                if (activeSignals.isEmpty()) {
                    break;
                }

                CompletableFuture<?> any = CompletableFuture.anyOf(activeSignals.toArray(CompletableFuture[]::new));
                try {
                    if (timeout == null) {
                        any.get();
                    } else {
                        long remaining = deadlineNanos - System.nanoTime();
                        if (remaining <= 0) {
                            onTimeout(timeout);
                            return;
                        }
                        any.get(remaining, TimeUnit.NANOSECONDS);
                    }
                } catch (TimeoutException ex) {
                    onTimeout(timeout);
                    return;
                }
            }
            drainCompletedSignalsOnOwnerThread();
        } catch (InterruptedException ex) {
            cancelUnfinished();
            waitForTermination(options.closeTimeout(), true);
            Thread.currentThread().interrupt();
            throw new ScopeExecutionException(options.name(), List.of(ex));
        } catch (ExecutionException ex) {
            // completionSignal always completes normally -- this should not happen
            log.warn("Unexpected ExecutionException in joinInternal for scope '{}'", options.name(), ex);
            drainCompletedSignalsOnOwnerThread();
        }
    }

    private void onTimeout(Duration timeout) {
        policyHandler.onTimeout(state);
        cancelUnfinished();
        waitForTermination(options.closeTimeout(), false);
        drainCompletedSignalsOnOwnerThread();
        log.warn("TaskScope '{}' timed out after {}", options.name(), timeout);
        if (shouldTimeoutThrow()) {
            throw new ScopeTimeoutException(options.name(), timeout, state.failures());
        }
    }

    private boolean shouldTimeoutThrow() {
        return switch (options.policy()) {
            case SHUTDOWN_ON_FAILURE -> true;
            case SHUTDOWN_ON_SUCCESS, PARTIAL_SUCCESS_OR_THROW -> state.successCount() == 0;
            case QUORUM_SUCCESS -> state.successCount() < options.quorumSuccessCount();
            case COLLECT_ALL -> false;
        };
    }

    private List<Throwable> unacceptableFailures() {
        List<Throwable> failures = state.failures();
        if (failures.isEmpty()) {
            return List.of();
        }
        return switch (options.policy()) {
            case SHUTDOWN_ON_FAILURE, COLLECT_ALL -> failures;
            case SHUTDOWN_ON_SUCCESS, PARTIAL_SUCCESS_OR_THROW ->
                    state.successCount() > 0 ? List.of() : failures;
            case QUORUM_SUCCESS ->
                    state.successCount() >= options.quorumSuccessCount() ? List.of() : failures;
        };
    }

    private <T> Callable<T> withConcurrencyLimit(Callable<T> delegate) {
        if (concurrencyLimit == null) {
            return delegate;
        }
        return () -> {
            boolean acquired = false;
            try {
                concurrencyLimit.acquire();
                acquired = true;
                return delegate.call();
            } finally {
                if (acquired) {
                    concurrencyLimit.release();
                }
            }
        };
    }

    private void drainCompletedSignalsOnOwnerThread() {
        for (DefaultSubtask<?> subtask : state.internalSubtasks()) {
            if (!subtask.isTerminal() || !subtask.markProcessedByOwner()) {
                continue;
            }
            if (subtask.state() == TaskState.SUCCESS) {
                policyHandler.onSuccess(subtask, state);
            } else if (subtask.state() == TaskState.FAILED) {
                policyHandler.onFailure(subtask, subtask.failure(), state);
            }
        }
    }

    private List<CompletableFuture<DefaultSubtask<?>>> activeCompletionSignals() {
        return state.internalSubtasks().stream()
                .filter(task -> !task.isTerminal())
                .map(DefaultSubtask::completionSignal)
                .toList();
    }

    private void cancelUnfinished() {
        state.internalSubtasks().stream()
                .filter(task -> !task.isTerminal())
                .forEach(DefaultSubtask::cancel);
    }

    private void waitForTermination(Duration timeout, boolean preserveInterrupt) {
        long deadlineNanos = System.nanoTime() + timeout.toNanos();
        waitForTerminationRemaining(deadlineNanos, preserveInterrupt);
    }

    private void waitForTerminationRemaining(long deadlineNanos, boolean preserveInterrupt) {
        for (DefaultSubtask<?> subtask : state.internalSubtasks()) {
            long remaining = deadlineNanos - System.nanoTime();
            if (remaining <= 0) {
                log.warn("TaskScope '{}' close timed out before subtask '{}' terminated",
                        options.name(), subtask.name());
                return;
            }
            try {
                boolean terminated = subtask.awaitTermination(remaining, TimeUnit.NANOSECONDS);
                if (!terminated) {
                    log.warn("TaskScope '{}' subtask '{}' did not terminate within closeTimeout={}",
                            options.name(), subtask.name(), options.closeTimeout());
                    return;
                }
            } catch (InterruptedException ex) {
                if (preserveInterrupt) {
                    Thread.currentThread().interrupt();
                }
                log.warn("TaskScope '{}' interrupted while waiting for subtask '{}' termination",
                        options.name(), subtask.name());
                return;
            }
        }
    }

    private void shutdownOwnedExecutor(long closeDeadlineNanos) {
        executor.shutdown();
        long remaining = closeDeadlineNanos - System.nanoTime();
        if (remaining <= 0) {
            log.warn("TaskScope '{}' closeTimeout elapsed before executor termination wait, forcing shutdownNow",
                    options.name());
            executor.shutdownNow();
            return;
        }
        try {
            if (!executor.awaitTermination(remaining, TimeUnit.NANOSECONDS)) {
                log.warn("TaskScope '{}' executor did not terminate within closeTimeout={}, forcing shutdownNow",
                        options.name(), options.closeTimeout());
                executor.shutdownNow();
            }
        } catch (InterruptedException ex) {
            log.warn("TaskScope '{}' interrupted while awaiting executor termination", options.name());
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private void warnAboutUnhandledCollectAllFailures() {
        if (options.policy() != ScopePolicy.COLLECT_ALL || failuresHandled.get()) {
            return;
        }
        long unhandledFailureCount = state.internalSubtasks().stream()
                .filter(task -> task.state() == TaskState.FAILED)
                .filter(task -> !task.failureObserved())
                .count();
        if (unhandledFailureCount > 0) {
            log.warn("TaskScope '{}' closed with {} unhandled failure(s). "
                            + "Call throwIfFailed() or inspect subtask.exception() explicitly.",
                    options.name(), unhandledFailureCount);
        }
    }

    private void logScopeSummary() {
        ScopeReport report = scopeReport();
        notifyScopeObserver(report);
        log.debug("TaskScope '{}' completed: total={}ms, tasks={}, success={}, failed={}, cancelled={}, slowestTask={}",
                report.scopeName(), report.elapsed().toMillis(), report.taskCount(), report.successCount(),
                report.failedCount(), report.cancelledCount(), report.slowestTaskName());
    }

    private void notifyScopeObserver(ScopeReport report) {
        try {
            scopeObserver.onScopeClosed(report);
        } catch (RuntimeException ex) {
            log.warn("TaskScope '{}' observer failed while handling close report", options.name(), ex);
        }
    }

    private ScopeReport scopeReport() {
        List<DefaultSubtask<?>> tasks = state.internalSubtasks();
        long success = tasks.stream().filter(task -> task.state() == TaskState.SUCCESS).count();
        long failed = tasks.stream().filter(task -> task.state() == TaskState.FAILED).count();
        long cancelled = tasks.stream().filter(task -> task.state() == TaskState.CANCELLED).count();
        String slowestTaskName = tasks.stream()
                .max(Comparator.comparing(DefaultSubtask::elapsed))
                .map(DefaultSubtask::name)
                .orElse("-");
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startNanos);

        return new ScopeReport(options.name(), elapsed, tasks.size(), success, failed, cancelled, slowestTaskName);
    }

    private void ensureOwner(String operation) {
        if (Thread.currentThread() != ownerThread) {
            throw new ScopeViolationException(
                    operation + " must be called from the scope owner thread");
        }
    }

    /**
     * Cleaner action that logs a warning if the scope was never explicitly closed.
     * This is a safety net -- scopes should always be used with try-with-resources.
     */
    private static final class ScopeCleanup implements Runnable {

        private final String scopeName;
        private final AtomicBoolean closed;

        ScopeCleanup(String scopeName, AtomicBoolean closed) {
            this.scopeName = scopeName;
            this.closed = closed;
        }

        @Override
        public void run() {
            if (!closed.get()) {
                LoggerFactory.getLogger(DefaultTaskScope.class)
                        .warn("TaskScope '{}' was never explicitly closed. "
                                + "Always use try-with-resources: try (var scope = ...) {{ ... }}",
                                scopeName);
            }
        }
    }
}
