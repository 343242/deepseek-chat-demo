package com.smart.rag.infrastructure.concurrent;

import java.time.Duration;
import java.util.Objects;

public record ScopeOptions(
        String name,
        ScopePolicy policy,
        ExecutorMode executorMode,
        int maxConcurrency,
        Duration defaultTimeout,
        Duration closeTimeout,
        int quorumSuccessCount,
        boolean executorOwnedByScope,
        boolean inheritMdc,
        boolean inheritSecurityContext,
        boolean inheritRequestContext
) {

    public ScopeOptions {
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(policy, "policy must not be null");
        Objects.requireNonNull(executorMode, "executorMode must not be null");
        Objects.requireNonNull(defaultTimeout, "defaultTimeout must not be null");
        Objects.requireNonNull(closeTimeout, "closeTimeout must not be null");
        if (name.isBlank()) {
            throw new ScopeViolationException("scope name must not be blank");
        }
        if (maxConcurrency < 0) {
            throw new ScopeViolationException("maxConcurrency must be greater than or equal to 0");
        }
        if (defaultTimeout.isNegative()) {
            throw new ScopeViolationException("defaultTimeout must not be negative");
        }
        if (closeTimeout.isZero() || closeTimeout.isNegative()) {
            throw new ScopeViolationException("closeTimeout must be positive");
        }
        if (quorumSuccessCount < 0) {
            throw new ScopeViolationException("quorumSuccessCount must be greater than or equal to 0");
        }
        if (policy == ScopePolicy.QUORUM_SUCCESS && quorumSuccessCount <= 0) {
            throw new ScopeViolationException("quorumSuccessCount must be positive for QUORUM_SUCCESS");
        }
    }

    public static ScopeOptions shutdownOnFailure(String name) {
        return builder(name).build();
    }

    public static Builder builder(String name) {
        return new Builder(name);
    }

    public ScopeOptions withPolicy(ScopePolicy policy) {
        return new ScopeOptions(name, policy, executorMode, maxConcurrency,
                defaultTimeout, closeTimeout, quorumSuccessCount, executorOwnedByScope,
                inheritMdc, inheritSecurityContext, inheritRequestContext);
    }

    public static final class Builder {

        private final String name;
        private ScopePolicy policy = ScopePolicy.SHUTDOWN_ON_FAILURE;
        private ExecutorMode executorMode = ExecutorMode.VIRTUAL_THREAD_PER_TASK;
        private int maxConcurrency;
        private Duration defaultTimeout = Duration.ZERO;
        private Duration closeTimeout = Duration.ofSeconds(5);
        private int quorumSuccessCount = 1;
        private boolean executorOwnedByScope = true;
        private boolean inheritMdc = true;
        private boolean inheritSecurityContext;
        private boolean inheritRequestContext;

        private Builder(String name) {
            this.name = name;
        }

        public Builder policy(ScopePolicy policy) {
            this.policy = policy;
            return this;
        }

        public Builder executorMode(ExecutorMode executorMode) {
            this.executorMode = executorMode;
            return this;
        }

        public Builder maxConcurrency(int maxConcurrency) {
            this.maxConcurrency = maxConcurrency;
            return this;
        }

        public Builder defaultTimeout(Duration defaultTimeout) {
            this.defaultTimeout = defaultTimeout;
            return this;
        }

        public Builder closeTimeout(Duration closeTimeout) {
            this.closeTimeout = closeTimeout;
            return this;
        }

        public Builder quorumSuccessCount(int quorumSuccessCount) {
            this.quorumSuccessCount = quorumSuccessCount;
            return this;
        }

        public Builder executorOwnedByScope(boolean executorOwnedByScope) {
            this.executorOwnedByScope = executorOwnedByScope;
            return this;
        }

        public Builder inheritMdc(boolean inheritMdc) {
            this.inheritMdc = inheritMdc;
            return this;
        }

        public Builder inheritSecurityContext(boolean inheritSecurityContext) {
            this.inheritSecurityContext = inheritSecurityContext;
            return this;
        }

        public Builder inheritRequestContext(boolean inheritRequestContext) {
            this.inheritRequestContext = inheritRequestContext;
            return this;
        }

        public ScopeOptions build() {
            return new ScopeOptions(
                    name,
                    policy,
                    executorMode,
                    maxConcurrency,
                    defaultTimeout,
                    closeTimeout,
                    quorumSuccessCount,
                    executorOwnedByScope,
                    inheritMdc,
                    inheritSecurityContext,
                    inheritRequestContext
            );
        }
    }
}
