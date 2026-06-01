package com.smart.rag.infrastructure.concurrent;

import java.util.concurrent.ExecutorService;

public interface ScopedTasks {

    TaskScope open(String name);

    TaskScope open(String name, ScopePolicy policy);

    TaskScope open(String name, ScopeOptions options);

    default TaskScope open(String name, ScopeOptions options, ExecutorService executor) {
        throw new UnsupportedOperationException("external executor scopes are not supported");
    }
}
