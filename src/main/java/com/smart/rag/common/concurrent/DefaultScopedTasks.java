package com.smart.rag.common.concurrent;

import com.smart.rag.common.concurrent.context.ContextCarrier;
import com.smart.rag.common.concurrent.context.MdcContextCarrier;
import com.smart.rag.common.concurrent.executor.DefaultScopeExecutorFactory;
import com.smart.rag.common.concurrent.executor.ScopeExecutorFactory;

import java.util.List;

public final class DefaultScopedTasks implements ScopedTasks {

    private final ScopeExecutorFactory executorFactory;

    public DefaultScopedTasks() {
        this(new DefaultScopeExecutorFactory());
    }

    public DefaultScopedTasks(ScopeExecutorFactory executorFactory) {
        this.executorFactory = executorFactory;
    }

    @Override
    public TaskScope open(String name) {
        return open(name, ScopeOptions.shutdownOnFailure(name));
    }

    @Override
    public TaskScope open(String name, ScopePolicy policy) {
        return open(name, ScopeOptions.builder(name).policy(policy).build());
    }

    @Override
    public TaskScope open(String name, ScopeOptions options) {
        if (!name.equals(options.name())) {
            throw new ScopeViolationException("scope name and options.name must match");
        }
        List<ContextCarrier<?>> carriers = options.inheritMdc()
                ? List.of(new MdcContextCarrier())
                : List.of();
        return new DefaultTaskScope(options, executorFactory.create(options), carriers);
    }
}
