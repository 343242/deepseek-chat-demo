package com.smart.rag.common.concurrent;

import com.smart.rag.common.concurrent.context.ContextCarrier;
import com.smart.rag.common.concurrent.context.MdcContextCarrier;
import com.smart.rag.common.concurrent.context.RequestContextCarrier;
import com.smart.rag.common.concurrent.context.SecurityContextCarrier;
import com.smart.rag.common.concurrent.executor.DefaultScopeExecutorFactory;
import com.smart.rag.common.concurrent.executor.ScopeExecutorFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;

public final class DefaultScopedTasks implements ScopedTasks {

    private final ScopeExecutorFactory executorFactory;
    private final ScopedTaskProperties properties;
    private final ScopeObserver scopeObserver;

    public DefaultScopedTasks() {
        this(new ScopedTaskProperties());
    }

    public DefaultScopedTasks(ScopeExecutorFactory executorFactory) {
        this(executorFactory, new ScopedTaskProperties(), ScopeObserver.NOOP);
    }

    public DefaultScopedTasks(ScopedTaskProperties properties) {
        this(new DefaultScopeExecutorFactory(properties), properties, ScopeObserver.NOOP);
    }

    public DefaultScopedTasks(ScopeExecutorFactory executorFactory, ScopedTaskProperties properties) {
        this(executorFactory, properties, ScopeObserver.NOOP);
    }

    public DefaultScopedTasks(
            ScopeExecutorFactory executorFactory,
            ScopedTaskProperties properties,
            ScopeObserver scopeObserver
    ) {
        this.executorFactory = Objects.requireNonNull(executorFactory, "executorFactory must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.scopeObserver = Objects.requireNonNull(scopeObserver, "scopeObserver must not be null");
    }

    @Override
    public TaskScope open(String name) {
        return open(name, properties.toOptions(name));
    }

    @Override
    public TaskScope open(String name, ScopePolicy policy) {
        return open(name, properties.toOptions(name).withPolicy(policy));
    }

    @Override
    public TaskScope open(String name, ScopeOptions options) {
        return open(name, options, executorFactory.create(options));
    }

    @Override
    public TaskScope open(String name, ScopeOptions options, ExecutorService executor) {
        if (!name.equals(options.name())) {
            throw new ScopeViolationException("scope name and options.name must match");
        }
        ScopeNestingGuard.ensureOpenAllowed(name);
        return new DefaultTaskScope(options, executor, carriers(options), scopeObserver);
    }

    private List<ContextCarrier<?>> carriers(ScopeOptions options) {
        List<ContextCarrier<?>> carriers = new ArrayList<>();
        if (options.inheritMdc()) {
            carriers.add(new MdcContextCarrier());
        }
        if (options.inheritSecurityContext()) {
            carriers.add(new SecurityContextCarrier());
        }
        if (options.inheritRequestContext()) {
            carriers.add(new RequestContextCarrier());
        }
        return List.copyOf(carriers);
    }
}
