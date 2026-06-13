package com.smart.rag.infrastructure.concurrent;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;

public interface TaskScope extends AutoCloseable {

    /**
     * Fork a new subtask within this scope.
     * <p><b>Warning:</b> Avoid capturing large objects (e.g. full collections, byte arrays,
     * or heavy domain objects) in the {@code Callable} closure. The closure is captured at fork
     * time and held in memory until the subtask completes. Prefer passing identifiers or
     * lightweight references and fetching the data inside the callable body.
     */
    <T> Subtask<T> fork(String name, Callable<T> task);

    default Subtask<Void> fork(String name, Runnable task) {
        return fork(name, () -> {
            task.run();
            return null;
        });
    }

    void join();

    void joinUntil(Duration timeout);

    /**
     * Join then collect results via {@code joiner}. This default method does NOT
     * call {@link #throwIfFailed()} — failures are silently skipped by typical
     * joiners (e.g. {@link ScopeJoiner#successfulResults}). Callers that need
     * fail-fast behavior must invoke {@code throwIfFailed()} explicitly after
     * this method returns.
     */
    default <R> R join(ScopeJoiner<R> joiner) {
        join();
        return joiner.collect(subtasks());
    }

    /**
     * Cancel a subtask (owner-only). Attempts to interrupt the underlying
     * future and transition the subtask to {@link TaskState#CANCELLED}.
     * Returns {@code true} if the cancel actually interrupted the running
     * task; {@code false} if the task had already completed.
     *
     * <p>P1-3: previously {@code Subtask.cancel()} was public and callable
     * from any thread, bypassing the owner-thread invariant. This method
     * restores the invariant — calling it from a non-owner thread throws
     * {@link ScopeViolationException}.
     */
    boolean cancel(Subtask<?> subtask);

    void throwIfFailed();

    List<Subtask<?>> subtasks();

    @Override
    void close();
}
