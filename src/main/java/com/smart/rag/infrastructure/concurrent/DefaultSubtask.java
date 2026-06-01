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

    public DefaultSubtask(String name) {
        if (name == null || name.isBlank()) {
            throw new ScopeViolationException("subtask name must not be blank");
        }
        this.name = name;
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
            case FAILED -> throw new SubtaskFailedException(name, exception.get());
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

    @Override
    public boolean cancel() {
        Future<?> submitted = future.get();
        TaskState beforeCancel = state.get();
        boolean cancelled = submitted == null || submitted.cancel(true);
        markCancelled(Duration.ZERO);
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

    public void markSuccess(T value, Duration elapsed) {
        if (state.compareAndSet(TaskState.RUNNING, TaskState.SUCCESS)
                || state.compareAndSet(TaskState.NEW, TaskState.SUCCESS)) {
            result.set(value);
            this.elapsed.set(elapsed);
            completionSignal.complete(this);
        }
    }

    public void markFailed(Throwable error, Duration elapsed) {
        if (state.compareAndSet(TaskState.RUNNING, TaskState.FAILED)
                || state.compareAndSet(TaskState.NEW, TaskState.FAILED)) {
            exception.set(error);
            this.elapsed.set(elapsed);
            completionSignal.complete(this);
        } else if (state.get() == TaskState.CANCELLED) {
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
