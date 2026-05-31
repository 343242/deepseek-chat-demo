package com.smart.rag.common.concurrent.context;

import java.util.concurrent.Callable;

public final class ContextAwareCallable<T> implements Callable<T> {

    private final Callable<T> delegate;
    private final ContextSnapshot snapshot;

    public ContextAwareCallable(Callable<T> delegate, ContextSnapshot snapshot) {
        this.delegate = delegate;
        this.snapshot = snapshot;
    }

    @Override
    public T call() throws Exception {
        try (ContextRestorer ignored = snapshot.restore()) {
            return delegate.call();
        }
    }
}
