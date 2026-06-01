package com.smart.rag.common.concurrent;

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

    default <R> R join(ScopeJoiner<R> joiner) {
        join();
        return joiner.collect(subtasks());
    }

    void throwIfFailed();

    List<Subtask<?>> subtasks();

    @Override
    void close();
}
