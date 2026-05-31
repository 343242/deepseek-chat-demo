package com.smart.rag.common.concurrent;

@FunctionalInterface
public interface ScopeObserver {

    ScopeObserver NOOP = report -> {};

    void onScopeClosed(ScopeReport report);
}
