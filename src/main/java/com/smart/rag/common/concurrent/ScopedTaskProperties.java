package com.smart.rag.common.concurrent;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "app.scoped-tasks")
public class ScopedTaskProperties {

    private ScopePolicy policy = ScopePolicy.SHUTDOWN_ON_FAILURE;
    private ExecutorMode executorMode = ExecutorMode.VIRTUAL_THREAD_PER_TASK;
    private int maxConcurrency;
    private Duration defaultTimeout = Duration.ZERO;
    private Duration closeTimeout = Duration.ofSeconds(5);
    private boolean inheritMdc = true;
    private boolean inheritSecurityContext;
    private boolean inheritRequestContext;
    private PoolConfig platformThreadPool = new PoolConfig("scoped-platform-");
    private PoolConfig sharedExecutor = new PoolConfig("scoped-shared-");

    public ScopeOptions toOptions(String name) {
        return ScopeOptions.builder(name)
                .policy(policy)
                .executorMode(executorMode)
                .maxConcurrency(maxConcurrency)
                .defaultTimeout(defaultTimeout)
                .closeTimeout(closeTimeout)
                .executorOwnedByScope(executorMode != ExecutorMode.SHARED_EXECUTOR)
                .inheritMdc(inheritMdc)
                .inheritSecurityContext(inheritSecurityContext)
                .inheritRequestContext(inheritRequestContext)
                .build();
    }

    public ScopePolicy getPolicy() {
        return policy;
    }

    public void setPolicy(ScopePolicy policy) {
        this.policy = policy;
    }

    public ExecutorMode getExecutorMode() {
        return executorMode;
    }

    public void setExecutorMode(ExecutorMode executorMode) {
        this.executorMode = executorMode;
    }

    public int getMaxConcurrency() {
        return maxConcurrency;
    }

    public void setMaxConcurrency(int maxConcurrency) {
        this.maxConcurrency = maxConcurrency;
    }

    public Duration getDefaultTimeout() {
        return defaultTimeout;
    }

    public void setDefaultTimeout(Duration defaultTimeout) {
        this.defaultTimeout = defaultTimeout;
    }

    public Duration getCloseTimeout() {
        return closeTimeout;
    }

    public void setCloseTimeout(Duration closeTimeout) {
        this.closeTimeout = closeTimeout;
    }

    public boolean isInheritMdc() {
        return inheritMdc;
    }

    public void setInheritMdc(boolean inheritMdc) {
        this.inheritMdc = inheritMdc;
    }

    public boolean isInheritSecurityContext() {
        return inheritSecurityContext;
    }

    public void setInheritSecurityContext(boolean inheritSecurityContext) {
        this.inheritSecurityContext = inheritSecurityContext;
    }

    public boolean isInheritRequestContext() {
        return inheritRequestContext;
    }

    public void setInheritRequestContext(boolean inheritRequestContext) {
        this.inheritRequestContext = inheritRequestContext;
    }

    public PoolConfig getPlatformThreadPool() {
        return platformThreadPool;
    }

    public void setPlatformThreadPool(PoolConfig platformThreadPool) {
        this.platformThreadPool = platformThreadPool;
    }

    public PoolConfig getSharedExecutor() {
        return sharedExecutor;
    }

    public void setSharedExecutor(PoolConfig sharedExecutor) {
        this.sharedExecutor = sharedExecutor;
    }

    public static class PoolConfig {

        private int corePoolSize = 4;
        private int maxPoolSize = 16;
        private int queueCapacity = 100;
        private int keepAliveSeconds = 60;
        private String threadNamePrefix;

        public PoolConfig() {
            this("scoped-");
        }

        public PoolConfig(String threadNamePrefix) {
            this.threadNamePrefix = threadNamePrefix;
        }

        public int getCorePoolSize() {
            return corePoolSize;
        }

        public void setCorePoolSize(int corePoolSize) {
            this.corePoolSize = corePoolSize;
        }

        public int getMaxPoolSize() {
            return maxPoolSize;
        }

        public void setMaxPoolSize(int maxPoolSize) {
            this.maxPoolSize = maxPoolSize;
        }

        public int getQueueCapacity() {
            return queueCapacity;
        }

        public void setQueueCapacity(int queueCapacity) {
            this.queueCapacity = queueCapacity;
        }

        public int getKeepAliveSeconds() {
            return keepAliveSeconds;
        }

        public void setKeepAliveSeconds(int keepAliveSeconds) {
            this.keepAliveSeconds = keepAliveSeconds;
        }

        public String getThreadNamePrefix() {
            return threadNamePrefix;
        }

        public void setThreadNamePrefix(String threadNamePrefix) {
            this.threadNamePrefix = threadNamePrefix;
        }
    }
}
