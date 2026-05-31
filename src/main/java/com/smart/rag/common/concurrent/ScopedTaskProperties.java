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
                .executorOwnedByScope(executorMode.ownsExecutor())
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
        if (platformThreadPool == null) {
            throw new IllegalArgumentException("platformThreadPool must not be null");
        }
        this.platformThreadPool = platformThreadPool;
    }

    public PoolConfig getSharedExecutor() {
        return sharedExecutor;
    }

    public void setSharedExecutor(PoolConfig sharedExecutor) {
        if (sharedExecutor == null) {
            throw new IllegalArgumentException("sharedExecutor must not be null");
        }
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
            if (corePoolSize < 0) {
                throw new IllegalArgumentException("corePoolSize must be >= 0");
            }
            if (corePoolSize > maxPoolSize) {
                throw new IllegalArgumentException("corePoolSize must be <= maxPoolSize");
            }
            this.corePoolSize = corePoolSize;
        }

        public int getMaxPoolSize() {
            return maxPoolSize;
        }

        public void setMaxPoolSize(int maxPoolSize) {
            if (maxPoolSize <= 0) {
                throw new IllegalArgumentException("maxPoolSize must be > 0");
            }
            if (maxPoolSize < corePoolSize) {
                throw new IllegalArgumentException("maxPoolSize must be >= corePoolSize");
            }
            this.maxPoolSize = maxPoolSize;
        }

        public int getQueueCapacity() {
            return queueCapacity;
        }

        public void setQueueCapacity(int queueCapacity) {
            if (queueCapacity <= 0) {
                throw new IllegalArgumentException("queueCapacity must be > 0");
            }
            this.queueCapacity = queueCapacity;
        }

        public int getKeepAliveSeconds() {
            return keepAliveSeconds;
        }

        public void setKeepAliveSeconds(int keepAliveSeconds) {
            if (keepAliveSeconds < 0) {
                throw new IllegalArgumentException("keepAliveSeconds must be >= 0");
            }
            this.keepAliveSeconds = keepAliveSeconds;
        }

        public String getThreadNamePrefix() {
            return threadNamePrefix;
        }

        public void setThreadNamePrefix(String threadNamePrefix) {
            if (threadNamePrefix == null || threadNamePrefix.isBlank()) {
                throw new IllegalArgumentException("threadNamePrefix must not be blank");
            }
            this.threadNamePrefix = threadNamePrefix;
        }
    }
}
