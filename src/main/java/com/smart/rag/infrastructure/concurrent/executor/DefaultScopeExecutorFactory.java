package com.smart.rag.infrastructure.concurrent.executor;

import com.smart.rag.infrastructure.concurrent.ScopedTaskProperties;
import com.smart.rag.infrastructure.concurrent.ScopeOptions;
import com.smart.rag.infrastructure.concurrent.ScopeViolationException;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public final class DefaultScopeExecutorFactory implements ScopeExecutorFactory {

    private final ScopedTaskProperties properties;
    // P1-15: lazy-create the shared executor only when SHARED_EXECUTOR is first used.
    // Constructing the factory no longer eagerly spawns threads.
    private volatile ExecutorService sharedExecutor;

    public DefaultScopeExecutorFactory() {
        this(new ScopedTaskProperties());
    }

    public DefaultScopeExecutorFactory(ScopedTaskProperties properties) {
        this.properties = properties;
    }

    @Override
    public ExecutorService create(ScopeOptions options) {
        return switch (options.executorMode()) {
            case VIRTUAL_THREAD_PER_TASK -> Executors.newVirtualThreadPerTaskExecutor();
            case PLATFORM_THREAD_POOL -> createPool(properties.getPlatformThreadPool());
            case SHARED_EXECUTOR -> {
                if (options.executorOwnedByScope()) {
                    throw new ScopeViolationException("SHARED_EXECUTOR requires executorOwnedByScope=false");
                }
                yield getOrCreateSharedExecutor();
            }
        };
    }

    private synchronized ExecutorService getOrCreateSharedExecutor() {
        if (sharedExecutor == null) {
            sharedExecutor = createPool(properties.getSharedExecutor());
        }
        return sharedExecutor;
    }

    @Override
    public void close() {
        // P1-15: close is a no-op if the shared executor was never created.
        ExecutorService snapshot = sharedExecutor;
        if (snapshot == null) {
            return;
        }
        snapshot.shutdown();
        try {
            // P1-16: factory-level close uses the wider factoryCloseTimeout (30s default),
            // distinct from per-scope closeTimeout (5s default).
            if (!snapshot.awaitTermination(properties.getFactoryCloseTimeout().toNanos(), TimeUnit.NANOSECONDS)) {
                snapshot.shutdownNow();
            }
        } catch (InterruptedException ex) {
            snapshot.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private ExecutorService createPool(ScopedTaskProperties.PoolConfig config) {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                config.getCorePoolSize(),
                config.getMaxPoolSize(),
                config.getKeepAliveSeconds(),
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(config.getQueueCapacity()),
                threadFactory(config.getThreadNamePrefix()),
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
        executor.allowCoreThreadTimeOut(config.getCorePoolSize() == 0);
        return executor;
    }

    private ThreadFactory threadFactory(String prefix) {
        ThreadFactory delegate = Thread.ofPlatform().factory();
        return runnable -> {
            Thread thread = delegate.newThread(runnable);
            thread.setName(prefix + thread.threadId());
            return thread;
        };
    }
}
