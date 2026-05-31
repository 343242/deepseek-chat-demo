package com.smart.rag.common.concurrent.executor;

import com.smart.rag.common.concurrent.ExecutorMode;
import com.smart.rag.common.concurrent.ScopeOptions;
import com.smart.rag.common.concurrent.ScopeViolationException;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class DefaultScopeExecutorFactory implements ScopeExecutorFactory {

    @Override
    public ExecutorService create(ScopeOptions options) {
        if (options.executorMode() == ExecutorMode.VIRTUAL_THREAD_PER_TASK) {
            return Executors.newVirtualThreadPerTaskExecutor();
        }
        throw new ScopeViolationException("Executor mode is not enabled in Phase 1: " + options.executorMode());
    }
}
