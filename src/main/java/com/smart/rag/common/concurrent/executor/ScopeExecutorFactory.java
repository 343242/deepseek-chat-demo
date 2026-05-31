package com.smart.rag.common.concurrent.executor;

import com.smart.rag.common.concurrent.ScopeOptions;

import java.util.concurrent.ExecutorService;

public interface ScopeExecutorFactory extends AutoCloseable {

    ExecutorService create(ScopeOptions options);

    @Override
    default void close() {}
}
