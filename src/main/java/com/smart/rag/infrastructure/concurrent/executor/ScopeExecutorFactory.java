package com.smart.rag.infrastructure.concurrent.executor;

import com.smart.rag.infrastructure.concurrent.ScopeOptions;

import java.util.concurrent.ExecutorService;

public interface ScopeExecutorFactory extends AutoCloseable {

    ExecutorService create(ScopeOptions options);

    @Override
    default void close() {}
}
