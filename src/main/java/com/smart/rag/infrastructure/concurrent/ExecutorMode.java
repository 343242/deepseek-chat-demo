package com.smart.rag.infrastructure.concurrent;

public enum ExecutorMode {
    VIRTUAL_THREAD_PER_TASK {
        @Override public boolean ownsExecutor() { return true; }
    },
    PLATFORM_THREAD_POOL {
        @Override public boolean ownsExecutor() { return true; }
    },
    SHARED_EXECUTOR {
        @Override public boolean ownsExecutor() { return false; }
    };

    public abstract boolean ownsExecutor();
}
