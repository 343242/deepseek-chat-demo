package com.smart.rag.infrastructure.concurrent;

import com.smart.rag.infrastructure.concurrent.context.ContextCarrier;
import com.smart.rag.infrastructure.concurrent.policy.ScopePolicyHandler;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Shared state for the scope collaborators. All fields are assigned once during
 * {@link DefaultTaskScope} construction; mutation happens only on the mutable
 * holders themselves ({@link ScopeState}, the {@link AtomicBoolean} flags).
 *
 * <p>This record exists so that {@link ScopeLifecycle}, {@link ScopeJoinEngine},
 * {@link ScopeTimeoutHandler}, {@link ScopeExecutorLifecycle}, and {@link ScopeReporter}
 * can cooperate without circular references — each collaborator holds a reference
 * to this context.
 */
final class ScopeContext {

    final ScopeOptions options;
    final ExecutorService executor;
    final ScopeState state;
    final long scopeId;
    final long startNanos;
    final Thread ownerThread;
    final AtomicBoolean closed;
    final AtomicBoolean joined;
    final AtomicBoolean failuresHandled;
    final Semaphore concurrencyLimit;
    final ScopePolicyHandler policyHandler;
    final ScopeObserver scopeObserver;
    final List<ContextCarrier<?>> contextCarriers;

    ScopeContext(
            ScopeOptions options,
            ExecutorService executor,
            ScopeState state,
            long scopeId,
            long startNanos,
            Thread ownerThread,
            AtomicBoolean closed,
            AtomicBoolean joined,
            AtomicBoolean failuresHandled,
            Semaphore concurrencyLimit,
            ScopePolicyHandler policyHandler,
            ScopeObserver scopeObserver,
            List<ContextCarrier<?>> contextCarriers
    ) {
        this.options = options;
        this.executor = executor;
        this.state = state;
        this.scopeId = scopeId;
        this.startNanos = startNanos;
        this.ownerThread = ownerThread;
        this.closed = closed;
        this.joined = joined;
        this.failuresHandled = failuresHandled;
        this.concurrencyLimit = concurrencyLimit;
        this.policyHandler = policyHandler;
        this.scopeObserver = scopeObserver;
        this.contextCarriers = contextCarriers;
    }
}
