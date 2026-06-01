package com.smart.rag.infrastructure.concurrent;

import java.util.List;

@FunctionalInterface
public interface ScopeJoiner<R> {

    R collect(List<Subtask<?>> subtasks);

    static <T> ScopeJoiner<List<T>> successfulResults(Class<T> resultType) {
        return subtasks -> subtasks.stream()
                .filter(subtask -> subtask.state() == TaskState.SUCCESS)
                .map(Subtask::result)
                .map(resultType::cast)
                .toList();
    }
}
