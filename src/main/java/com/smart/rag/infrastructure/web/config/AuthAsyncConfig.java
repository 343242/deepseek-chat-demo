package com.smart.rag.infrastructure.web.config;

import com.smart.rag.infrastructure.concurrent.ThreadPoolConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 登录路径 fire-and-forget 预热专用线程池。
 * <p>
 * 用于登录后异步预热用户权限缓存等 best-effort 副作用：不阻塞登录响应，饱和时丢弃任务
 * （cache miss 由 {@code getCurrentUser} 同步兜底），故采用显式 {@link ThreadPoolExecutor}
 * + 有界队列 + 丢弃策略，避免占用 common ForkJoinPool 或回压请求线程。
 * <p>
 * 不使用 {@code infrastructure/concurrent} 的结构化并发（ScopedTasks/TaskScope）：
 * 那是 fork-join 框架，{@code join()}/{@code close()} 会阻塞到子任务完成且禁止子任务逃逸，
 * 与 fire-and-forget 语义冲突。
 */
@Configuration
public class AuthAsyncConfig {

    private static final Logger log = LoggerFactory.getLogger(AuthAsyncConfig.class);

    /** 预热队列容量：预热任务短小，小队列即可；饱和即丢弃。 */
    private static final int WARMUP_QUEUE_CAPACITY = 64;

    @Bean(destroyMethod = "shutdown")
    public ThreadPoolExecutor authPermissionWarmupExecutor() {
        return new ThreadPoolExecutor(
                ThreadPoolConstants.lightCore(),
                ThreadPoolConstants.lightMax(),
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(WARMUP_QUEUE_CAPACITY),
                authWarmupThreadFactory(),
                (r, exec) -> log.debug("Permission warmup task discarded (executor saturated; will lazy-load on cache miss)")
        );
    }

    private ThreadFactory authWarmupThreadFactory() {
        AtomicInteger counter = new AtomicInteger();
        return r -> {
            Thread t = new Thread(r, "auth-perm-warmup-" + counter.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }
}
