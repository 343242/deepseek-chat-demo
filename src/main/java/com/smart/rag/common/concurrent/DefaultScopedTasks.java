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
        ScopeOptions defaults = properties.toOptions(name);
        ScopeOptions options = ScopeOptions.builder(name)
                .policy(policy)
                .executorMode(defaults.executorMode())
                .maxConcurrency(defaults.maxConcurrency())
                .defaultTimeout(defaults.defaultTimeout())
                .closeTimeout(defaults.closeTimeout())
                .executorOwnedByScope(defaults.executorOwnedByScope())
                .inheritMdc(defaults.inheritMdc())
                .inheritSecurityContext(defaults.inheritSecurityContext())
                .inheritRequestContext(defaults.inheritRequestContext())
                .build();
        return open(name, options);
    }

    @Override
    public TaskScope open(String name, ScopeOptions options) {
        if (!name.equals(options.name())) {
            throw new ScopeViolationException("scope name and options.name must match");
        }
        return new DefaultTaskScope(options, executorFactory.create(options), carriers(options), scopeObserver);
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
