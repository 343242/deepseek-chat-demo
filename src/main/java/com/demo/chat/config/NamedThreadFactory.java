package com.demo.chat.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 自定义线程工厂。
 * <p>
 * 遵循阿里巴巴 Java 开发手册：
 * <ul>
 *   <li>线程名带业务含义前缀 + 序号，便于监控和排查</li>
 *   <li>设置 daemon=false，确保关键业务线程不会随主线程退出</li>
 *   <li>注册 UncaughtExceptionHandler，防止异常静默吞掉</li>
 * </ul>
 */
public class NamedThreadFactory implements ThreadFactory {

    private static final Logger log = LoggerFactory.getLogger(NamedThreadFactory.class);

    private final AtomicInteger threadNumber = new AtomicInteger(1);
    private final String namePrefix;
    private final boolean daemon;

    public NamedThreadFactory(String namePrefix) {
        this(namePrefix, false);
    }

    public NamedThreadFactory(String namePrefix, boolean daemon) {
        this.namePrefix = namePrefix;
        this.daemon = daemon;
    }

    @Override
    public Thread newThread(Runnable runnable) {
        Thread thread = new Thread(runnable, namePrefix + threadNumber.getAndIncrement());
        thread.setDaemon(daemon);
        thread.setUncaughtExceptionHandler((t, e) ->
                log.error("Uncaught exception in thread {}: {}", t.getName(), e.getMessage(), e)
        );
        return thread;
    }
}
