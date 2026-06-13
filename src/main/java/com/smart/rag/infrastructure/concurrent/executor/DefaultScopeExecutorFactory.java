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
    private final ExecutorService sharedExecutor;

    public DefaultScopeExecutorFactory() {
        this(new ScopedTaskProperties());
    }

    public DefaultScopeExecutorFactory(ScopedTaskProperties properties) {
        this.properties = properties;
        this.sharedExecutor = createPool(properties.getSharedExecutor());
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
                yield sharedExecutor;
            }
        };
    }

    @Override
    public void close() {
        sharedExecutor.shutdown();
        try {
            if (!sharedExecutor.awaitTermination(properties.getCloseTimeout().toNanos(), TimeUnit.NANOSECONDS)) {
                sharedExecutor.shutdownNow();
            }
        } catch (InterruptedException ex) {
            sharedExecutor.shutdownNow();
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
