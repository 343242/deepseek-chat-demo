package com.smart.rag.common.concurrent;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;

public interface TaskScope extends AutoCloseable {

    <T> Subtask<T> fork(String name, Callable<T> task);

    default Subtask<Void> fork(String name, Runnable task) {
        return fork(name, () -> {
            task.run();
            return null;
        });
    }

    void join();

    void joinUntil(Duration timeout);

    void throwIfFailed();

    List<Subtask<?>> subtasks();

    @Override
    void close();
}
