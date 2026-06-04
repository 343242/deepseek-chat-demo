package com.smart.rag.rag.etl;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.function.Supplier;

/**
 * ETL 任务执行器门面
 * <p>
 * 持有 IO 密集型和 CPU 密集型两个线程池引用，
 * 提供 {@code submitIo} / {@code submitCpu} 便捷方法。
 * <p>
 * 所有异步任务返回 {@link CompletableFuture}，异常通过 exceptionally 链捕获。
 */
public class EtlTaskExecutorBridge {

    private final ExecutorService ioExecutor;
    private final ExecutorService cpuExecutor;

    public EtlTaskExecutorBridge(ExecutorService ioExecutor,
                                  ExecutorService cpuExecutor) {
        this.ioExecutor = ioExecutor;
        this.cpuExecutor = cpuExecutor;
    }

    /**
     * 提交任务到 IO 线程池
     */
    public <T> CompletableFuture<T> submitIo(Supplier<T> task) {
        return CompletableFuture.supplyAsync(task, ioExecutor);
    }

    /**
     * 提交任务到 CPU 线程池
     */
    public <T> CompletableFuture<T> submitCpu(Supplier<T> task) {
        return CompletableFuture.supplyAsync(task, cpuExecutor);
    }

    /**
     * 获取 IO 线程池
     */
    public ExecutorService getIoExecutor() {
        return ioExecutor;
    }

    /**
     * 获取 CPU 线程池
     */
    public ExecutorService getCpuExecutor() {
        return cpuExecutor;
    }
}
