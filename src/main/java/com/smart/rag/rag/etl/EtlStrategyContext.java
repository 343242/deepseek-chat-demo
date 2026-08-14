package com.smart.rag.rag.etl;

import com.smart.rag.infrastructure.concurrent.ScopedTasks;
import org.springframework.context.ApplicationEventPublisher;

import java.util.concurrent.ExecutorService;

/**
 * ETL 策略共享上下文 — 组装各策略共同依赖的执行环境
 * <p>
 * 将 IO/CPU 线程池、{@link ScopedTasks} 门面与事件发布器打包为单个依赖，
 * 避免各策略构造器参数膨胀（阿里规约：构造参数 ≤ 8）。
 * 在 {@link EtlExecutorConfig} 中注册为 Bean。
 */
public record EtlStrategyContext(
        ExecutorService ioExecutor,
        ExecutorService cpuExecutor,
        ScopedTasks scopedTasks,
        ApplicationEventPublisher eventPublisher) {
}
