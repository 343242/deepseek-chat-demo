package com.smart.rag.common.concurrent;

public enum ExecutorMode {
    VIRTUAL_THREAD_PER_TASK,
    PLATFORM_THREAD_POOL,
    SHARED_EXECUTOR
}
