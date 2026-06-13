package com.smart.rag.infrastructure.concurrent;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class DefaultSubtask<T> implements Subtask<T> {

    private final String name;
    private final CompletableFuture<DefaultSubtask<?>> completionSignal = new CompletableFuture<>();
    private final AtomicReference<TaskState> state = new AtomicReference<>(TaskState.NEW);
    private final AtomicReference<T> result = new AtomicReference<>();
    private final AtomicReference<Throwable> exception = new AtomicReference<>();
    private final AtomicReference<Future<?>> future = new AtomicReference<>();
    private final AtomicBoolean processedByOwner = new AtomicBoolean();
    private final AtomicReference<Duration> elapsed = new AtomicReference<>(Duration.ZERO);
    private final AtomicBoolean failureObserved = new AtomicBoolean();
    private final CountDownLatch terminated = new CountDownLatch(1);
    // P1-8: fork timestamp so cancel() can report real elapsed (fork→cancel wall time),
    // distinct from the execution elapsed reported by markSuccess/markFailed (task run time).
    private final long forkNanos;

    public DefaultSubtask(String name) {
        if (name == null || name.isBlank()) {
            throw new ScopeViolationException("subtask name must not be blank");
        }
        this.name = name;
        this.forkNanos = System.nanoTime();
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public TaskState state() {
        return state.get();
    }

    @Override
    public T result() {
        return switch (state()) {
            case SUCCESS -> result.get();
            case FAILED -> throw new SubtaskFailedException(name, exception());
            case CANCELLED -> throw new SubtaskCancelledException(name);
            case NEW, RUNNING -> throw new SubtaskNotCompletedException(name);
        };
    }

    @Override
    public Throwable exception() {
        Throwable error = exception.get();
        if (error != null) {
            failureObserved.set(true);
        }
        return error;
    }

    /**
     * Returns the raw failure without marking it as observed.
     * Unlike {@link #exception()}, this method does NOT set the failure-observed flag.
     * Use {@link #exception()} for public consumption (marks failure as observed, suppressing
     * the "unhandled failure" warning). Use this method for internal scope bookkeeping
     * where the observation flag should not be affected.
     */
    public Throwable failure() {
        return exception.get();
    }

    /**
     * Cancel this subtask (internal API, callable from package collaborators).
     * P1-3: the public entry point is now {@link TaskScope#cancel(Subtask)}
     * which enforces the owner-thread invariant before delegating here.
     *
     * <p>P1-8: the reported elapsed is the wall time from fork to cancel
     * (subtask lifetime), not the task execution time. Execution elapsed is
     * captured separately by {@link #markSuccess} / {@link #markFailed} via
     * {@link com.smart.rag.infrastructure.concurrent.ObservedCallable}, which
     * only fires after {@link #markRunning()} succeeds. A cancelled task that
     * never ran still has a meaningful fork→cancel elapsed.
     */
    public boolean cancel() {
        Future<?> submitted = future.get();
        TaskState beforeCancel = state.get();
        boolean cancelled = submitted == null || submitted.cancel(true);
        markCancelled(Duration.ofNanos(System.nanoTime() - forkNanos));
        if (beforeCancel == TaskState.NEW || submitted == null) {
            markTerminated();
        }
        return cancelled;
    }

    public CompletableFuture<DefaultSubtask<?>> completionSignal() {
        return completionSignal;
    }

    public boolean markRunning() {
        return state.compareAndSet(TaskState.NEW, TaskState.RUNNING);
    }

    // P1-14: removed dead NEW→SUCCESS/FAILED branches. ObservedCallable always calls
    // markRunning() first, and markRunning failing throws InterruptedException before
    // markSuccess/markFailed can run. The RUNNING→SUCCESS/FAILED transition is the
    // only reachable path. The CANCELLED branch in markFailed is preserved (P0-5:
    // teardown-error preservation for cancelled tasks).
    public void markSuccess(T value, Duration elapsed) {
        if (state.compareAndSet(TaskState.RUNNING, TaskState.SUCCESS)) {
            result.set(value);
            this.elapsed.set(elapsed);
            completionSignal.complete(this);
        }
    }

    public void markFailed(Throwable error, Duration elapsed) {
        if (state.compareAndSet(TaskState.RUNNING, TaskState.FAILED)) {
            exception.set(error);
            this.elapsed.set(elapsed);
            completionSignal.complete(this);
        } else if (state.get() == TaskState.CANCELLED) {
            // Task was cancelled externally but worker still threw during teardown.
            // Preserve the teardown error so callers can observe it via exception();
            // state stays CANCELLED (cancellation takes priority over later failure).
            if (error != null && exception.get() == null) {
                exception.set(error);
            }
            completionSignal.complete(this);
        }
    }

    public void markCancelled(Duration elapsed) {
        TaskState current;
        do {
            current = state.get();
            if (current == TaskState.SUCCESS || current == TaskState.FAILED || current == TaskState.CANCELLED) {
                completionSignal.complete(this);
                return;
            }
        } while (!state.compareAndSet(current, TaskState.CANCELLED));
        this.elapsed.set(elapsed);
        completionSignal.complete(this);
    }

    public boolean isTerminal() {
        TaskState current = state.get();
        return current == TaskState.SUCCESS || current == TaskState.FAILED || current == TaskState.CANCELLED;
    }

    public boolean markProcessedByOwner() {
        return processedByOwner.compareAndSet(false, true);
    }

    public void attachFuture(Future<?> future) {
        this.future.set(future);
        if (state.get() == TaskState.CANCELLED) {
            future.cancel(true);
            markTerminated();
        }
    }

    public Duration elapsed() {
        return elapsed.get();
    }

    public boolean failureObserved() {
        return failureObserved.get();
    }

    public void markTerminated() {
        terminated.countDown();
    }

    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        return terminated.await(timeout, unit);
    }
}
