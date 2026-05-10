package com.demo.chat.rag.etl;

import com.demo.chat.rag.config.EtlExecutorProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * ETL 任务执行器门面
 * <p>
 * 持有 IO 密集型和 CPU 密集型两个线程池引用，
 * 提供 {@code submitIo} / {@code submitCpu} / {@code submitIoAll} 等便捷方法。
 * <p>
 * 所有异步任务返回 {@link CompletableFuture}，异常通过 exceptionally 链捕获。
 */
public class EtlTaskExecutorBridge {

    private static final Logger log = LoggerFactory.getLogger(EtlTaskExecutorBridge.class);

    private final ThreadPoolTaskExecutor ioExecutor;
    private final ThreadPoolTaskExecutor cpuExecutor;

    public EtlTaskExecutorBridge(ThreadPoolTaskExecutor ioExecutor,
                                  ThreadPoolTaskExecutor cpuExecutor) {
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
     * 批量提交任务到 IO 线程池，返回所有 Future 的列表
     */
    public <T> List<CompletableFuture<T>> submitIoAll(List<Supplier<T>> tasks) {
        return tasks.stream()
                .map(this::submitIo)
                .collect(Collectors.toList());
    }

    /**
     * 批量提交任务到 CPU 线程池，返回所有 Future 的列表
     */
    public <T> List<CompletableFuture<T>> submitCpuAll(List<Supplier<T>> tasks) {
        return tasks.stream()
                .map(this::submitCpu)
                .collect(Collectors.toList());
    }

    /**
     * 等待所有 Future 完成，收集结果
     */
    public <T> List<T> awaitAll(List<CompletableFuture<T>> futures) {
        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
        return futures.stream()
                .map(f -> {
                    try {
                        return f.join();
                    } catch (Exception e) {
                        log.warn("Task failed: {}", e.getMessage());
                        return null;
                    }
                })
                .collect(Collectors.toList());
    }
}
