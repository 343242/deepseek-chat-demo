package com.smart.rag.infrastructure.concurrent;

@FunctionalInterface
public interface ScopeObserver {

    ScopeObserver NOOP = report -> {};

    void onScopeClosed(ScopeReport report);
}
