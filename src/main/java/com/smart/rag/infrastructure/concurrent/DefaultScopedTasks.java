package com.smart.rag.infrastructure.concurrent;

import com.smart.rag.infrastructure.concurrent.context.ContextCarrier;
import com.smart.rag.infrastructure.concurrent.context.MdcContextCarrier;
import com.smart.rag.infrastructure.concurrent.context.SecurityContextCarrier;
import com.smart.rag.infrastructure.concurrent.executor.DefaultScopeExecutorFactory;
import com.smart.rag.infrastructure.concurrent.executor.ScopeExecutorFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;

public final class DefaultScopedTasks implements ScopedTasks {

    private final ScopeExecutorFactory executorFactory;
    private final ScopedTaskProperties properties;
    private final ScopeObserver scopeObserver;
    private final List<ContextCarrier<?>> requestContextCarriers;

    /**
     * Default constructor — sensible defaults for tests and simple use cases.
     * Production code should prefer {@link #builder()} for explicit dependency injection.
     */
    public DefaultScopedTasks() {
        this(new DefaultScopeExecutorFactory(new ScopedTaskProperties()),
                new ScopedTaskProperties(), ScopeObserver.NOOP, List.of());
    }

    /**
     * Two-argument convenience constructor retained for callers that inject both
     * a custom executor factory and properties.
     */
    public DefaultScopedTasks(ScopeExecutorFactory executorFactory, ScopedTaskProperties properties) {
        this(executorFactory, properties, ScopeObserver.NOOP, List.of());
    }

    /**
     * Full constructor — preferred for production wiring (e.g. Spring auto-config).
     */
    public DefaultScopedTasks(
            ScopeExecutorFactory executorFactory,
            ScopedTaskProperties properties,
            ScopeObserver scopeObserver,
            List<ContextCarrier<?>> requestContextCarriers
    ) {
        this.executorFactory = Objects.requireNonNull(executorFactory, "executorFactory must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.scopeObserver = Objects.requireNonNull(scopeObserver, "scopeObserver must not be null");
        this.requestContextCarriers = List.copyOf(
                Objects.requireNonNull(requestContextCarriers, "requestContextCarriers must not be null"));
    }

    /**
     * Fluent builder for {@link DefaultScopedTasks}. Prefer this over the multiple
     * constructor overloads when wiring custom dependencies — it makes the intent
     * explicit and avoids ambiguity between similarly-typed arguments.
     *
     * <pre>{@code
     * ScopedTasks tasks = DefaultScopedTasks.builder()
     *         .executorFactory(factory)
     *         .properties(props)
     *         .scopeObserver(observer)
     *         .addContextCarrier(new MdcContextCarrier())
     *         .build();
     * }</pre>
     */
    public static Builder builder() {
        return new Builder();
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
            carriers.addAll(requestContextCarriers);
        }
        return List.copyOf(carriers);
    }

    public static final class Builder {

        private ScopeExecutorFactory executorFactory;
        private ScopedTaskProperties properties;
        private ScopeObserver scopeObserver = ScopeObserver.NOOP;
        private final List<ContextCarrier<?>> contextCarriers = new ArrayList<>();

        public Builder executorFactory(ScopeExecutorFactory executorFactory) {
            this.executorFactory = executorFactory;
            return this;
        }

        public Builder properties(ScopedTaskProperties properties) {
            this.properties = properties;
            return this;
        }

        public Builder scopeObserver(ScopeObserver scopeObserver) {
            this.scopeObserver = scopeObserver;
            return this;
        }

        public Builder addContextCarrier(ContextCarrier<?> carrier) {
            this.contextCarriers.add(carrier);
            return this;
        }

        public Builder contextCarriers(List<ContextCarrier<?>> carriers) {
            this.contextCarriers.clear();
            this.contextCarriers.addAll(carriers);
            return this;
        }

        public DefaultScopedTasks build() {
            ScopedTaskProperties props = Objects.requireNonNullElseGet(properties, ScopedTaskProperties::new);
            ScopeExecutorFactory factory = Objects.requireNonNullElseGet(executorFactory,
                    () -> new DefaultScopeExecutorFactory(props));
            return new DefaultScopedTasks(factory, props, scopeObserver, List.copyOf(contextCarriers));
        }
    }
}
