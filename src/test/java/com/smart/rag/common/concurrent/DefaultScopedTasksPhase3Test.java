package com.smart.rag.common.concurrent;

import com.smart.rag.chat.context.PolicyContext;
import com.smart.rag.chat.context.RequestContext;
import com.smart.rag.chat.context.RequestContextHolder;
import com.smart.rag.chat.context.SessionContext;
import com.smart.rag.chat.context.UserContext;
import com.smart.rag.infrastructure.concurrent.*;
import com.smart.rag.infrastructure.concurrent.context.ContextCarrier;
import com.smart.rag.infrastructure.concurrent.executor.DefaultScopeExecutorFactory;
import com.smart.rag.infrastructure.concurrent.executor.ScopeExecutorFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("DefaultScopedTasks Phase 3")
class DefaultScopedTasksPhase3Test {

    @AfterEach
    void clearThreadLocals() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.clear();
    }

    @Nested
    @DisplayName("properties")
    class PropertiesDefaults {

        @Test
        @DisplayName("open(name) applies configured defaults")
        void openName_appliesConfiguredDefaults() {
            ScopedTaskProperties properties = new ScopedTaskProperties();
            properties.setExecutorMode(ExecutorMode.PLATFORM_THREAD_POOL);
            properties.setMaxConcurrency(3);
            properties.setDefaultTimeout(Duration.ofMillis(250));
            properties.setCloseTimeout(Duration.ofSeconds(2));
            properties.setQuorumSuccessCount(2);
            properties.setInheritMdc(false);
            properties.setInheritSecurityContext(true);
            properties.setInheritRequestContext(true);
            RecordingExecutorFactory executorFactory = new RecordingExecutorFactory();
            ScopedTasks scopedTasks = new DefaultScopedTasks(executorFactory, properties);

            try (TaskScope scope = scopedTasks.open("configured")) {
                assertThat(scope).isNotNull();
            }

            ScopeOptions options = executorFactory.lastOptions();
            assertThat(options.name()).isEqualTo("configured");
            assertThat(options.policy()).isEqualTo(ScopePolicy.SHUTDOWN_ON_FAILURE);
            assertThat(options.executorMode()).isEqualTo(ExecutorMode.PLATFORM_THREAD_POOL);
            assertThat(options.maxConcurrency()).isEqualTo(3);
            assertThat(options.defaultTimeout()).isEqualTo(Duration.ofMillis(250));
            assertThat(options.closeTimeout()).isEqualTo(Duration.ofSeconds(2));
            assertThat(options.quorumSuccessCount()).isEqualTo(2);
            assertThat(options.inheritMdc()).isFalse();
            assertThat(options.inheritSecurityContext()).isTrue();
            assertThat(options.inheritRequestContext()).isTrue();
        }

        @Test
        @DisplayName("PoolConfig rejects invalid executor configuration early")
        void poolConfig_rejectsInvalidConfigurationEarly() {
            ScopedTaskProperties.PoolConfig pool = new ScopedTaskProperties.PoolConfig();

            assertThatThrownBy(() -> pool.setCorePoolSize(-1))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("corePoolSize");
            assertThatThrownBy(() -> pool.setMaxPoolSize(0))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("maxPoolSize");
            assertThatThrownBy(() -> pool.setQueueCapacity(0))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("queueCapacity");
            assertThatThrownBy(() -> pool.setKeepAliveSeconds(-1))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("keepAliveSeconds");
            assertThatThrownBy(() -> pool.setThreadNamePrefix(" "))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("threadNamePrefix");

            ScopedTaskProperties properties = new ScopedTaskProperties();
            assertThatThrownBy(() -> properties.setPlatformThreadPool(null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("platformThreadPool");
            assertThatThrownBy(() -> properties.setSharedExecutor(null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("sharedExecutor");
            assertThatThrownBy(() -> properties.setQuorumSuccessCount(-1))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("quorumSuccessCount");

            pool.setCorePoolSize(4);
            assertThatThrownBy(() -> pool.setMaxPoolSize(3))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("maxPoolSize");
        }
    }

    @Nested
    @DisplayName("executor modes")
    class ExecutorModes {

        @Test
        @DisplayName("PLATFORM_THREAD_POOL runs subtasks on named platform threads")
        void platformThreadPool_runsOnNamedPlatformThreads() {
            ScopedTaskProperties properties = new ScopedTaskProperties();
            properties.getPlatformThreadPool().setThreadNamePrefix("scoped-platform-test-");
            ScopedTasks scopedTasks = new DefaultScopedTasks(new DefaultScopeExecutorFactory(properties), properties);
            ScopeOptions options = ScopeOptions.builder("platform")
                    .executorMode(ExecutorMode.PLATFORM_THREAD_POOL)
                    .build();

            try (TaskScope scope = scopedTasks.open("platform", options)) {
                Subtask<String> threadName = scope.fork("thread-name", () -> Thread.currentThread().getName());
                Subtask<Boolean> virtual = scope.fork("is-virtual", () -> Thread.currentThread().isVirtual());

                scope.join();
                scope.throwIfFailed();

                assertThat(threadName.result()).startsWith("scoped-platform-test-");
                assertThat(virtual.result()).isFalse();
            }
        }

        @Test
        @DisplayName("SHARED_EXECUTOR requires executorOwnedByScope=false and survives scope close")
        void sharedExecutor_requiresExternalOwnershipAndSurvivesScopeClose() {
            ScopedTaskProperties properties = new ScopedTaskProperties();
            properties.getSharedExecutor().setCorePoolSize(1);
            properties.getSharedExecutor().setMaxPoolSize(1);
            properties.getSharedExecutor().setThreadNamePrefix("scoped-shared-test-");
            DefaultScopeExecutorFactory executorFactory = new DefaultScopeExecutorFactory(properties);
            ScopedTasks scopedTasks = new DefaultScopedTasks(executorFactory, properties);
            ScopeOptions options = ScopeOptions.builder("shared")
                    .executorMode(ExecutorMode.SHARED_EXECUTOR)
                    .executorOwnedByScope(false)
                    .build();

            String firstThread;
            try (TaskScope scope = scopedTasks.open("shared", options)) {
                Subtask<String> threadName = scope.fork("first", () -> Thread.currentThread().getName());
                scope.join();
                scope.throwIfFailed();
                firstThread = threadName.result();
            }

            try (TaskScope scope = scopedTasks.open("shared-again", ScopeOptions.builder("shared-again")
                    .executorMode(ExecutorMode.SHARED_EXECUTOR)
                    .executorOwnedByScope(false)
                    .build())) {
                Subtask<String> threadName = scope.fork("second", () -> Thread.currentThread().getName());
                scope.join();
                scope.throwIfFailed();
                assertThat(threadName.result()).startsWith("scoped-shared-test-");
            } finally {
                executorFactory.close();
            }

            assertThat(firstThread).startsWith("scoped-shared-test-");
        }

        @Test
        @DisplayName("SHARED_EXECUTOR rejects scope-owned lifecycle")
        void sharedExecutor_rejectsScopeOwnedLifecycle() {
            ScopedTaskProperties properties = new ScopedTaskProperties();
            ScopedTasks scopedTasks = new DefaultScopedTasks(new DefaultScopeExecutorFactory(properties), properties);
            ScopeOptions options = ScopeOptions.builder("bad-shared")
                    .executorMode(ExecutorMode.SHARED_EXECUTOR)
                    .build();

            assertThatThrownBy(() -> scopedTasks.open("bad-shared", options))
                    .isInstanceOf(ScopeViolationException.class)
                    .hasMessageContaining("SHARED_EXECUTOR requires executorOwnedByScope=false");
        }

        @Test
        @DisplayName("open with external executor creates non-owned shared scope")
        void openWithExternalExecutor_createsNonOwnedSharedScope() {
            ExecutorService executor = Executors.newSingleThreadExecutor();
            ScopedTasks scopedTasks = new DefaultScopedTasks();

            try {
                ScopeOptions options = ScopeOptions.builder("external")
                        .policy(ScopePolicy.COLLECT_ALL)
                        .executorMode(ExecutorMode.SHARED_EXECUTOR)
                        .executorOwnedByScope(false)
                        .build();

                try (TaskScope scope = scopedTasks.open("external", options, executor)) {
                    Subtask<String> task = scope.fork("value", () -> "ok");

                    scope.join();

                    assertThat(task.result()).isEqualTo("ok");
                }

                assertThat(executor.isShutdown()).isFalse();
            } finally {
                executor.shutdownNow();
            }
        }

        @Test
        @DisplayName("factory close waits for shared executor using subsecond closeTimeout")
        void factoryClose_waitsForSharedExecutorWithSubsecondTimeout() throws Exception {
            ScopedTaskProperties properties = new ScopedTaskProperties();
            properties.setCloseTimeout(Duration.ofMillis(500));
            properties.getSharedExecutor().setCorePoolSize(1);
            properties.getSharedExecutor().setMaxPoolSize(1);
            DefaultScopeExecutorFactory executorFactory = new DefaultScopeExecutorFactory(properties);
            ExecutorService sharedExecutor = executorFactory.create(ScopeOptions.builder("shared")
                    .executorMode(ExecutorMode.SHARED_EXECUTOR)
                    .executorOwnedByScope(false)
                    .build());
            CountDownLatch started = new CountDownLatch(1);
            AtomicBoolean interrupted = new AtomicBoolean();

            sharedExecutor.submit(() -> {
                started.countDown();
                try {
                    Thread.sleep(100);
                } catch (InterruptedException ex) {
                    interrupted.set(true);
                    Thread.currentThread().interrupt();
                }
            });
            assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();

            executorFactory.close();

            assertThat(interrupted).isFalse();
            assertThat(sharedExecutor.isTerminated()).isTrue();
        }
    }

    @Nested
    @DisplayName("context propagation")
    class ContextPropagation {

        @Test
        @DisplayName("SecurityContext is inherited only when enabled and restored afterwards")
        void securityContext_inheritedWhenEnabledAndRestored() {
            UsernamePasswordAuthenticationToken ownerAuth =
                    new UsernamePasswordAuthenticationToken(42L, null, List.of());
            SecurityContextHolder.getContext().setAuthentication(ownerAuth);
            ScopeOptions options = ScopeOptions.builder("security")
                    .inheritSecurityContext(true)
                    .build();

            try (TaskScope scope = new DefaultScopedTasks().open("security", options)) {
                Subtask<Object> principal = scope.fork("read-security", () -> {
                    Object captured = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
                    SecurityContextHolder.getContext().setAuthentication(
                            new UsernamePasswordAuthenticationToken(99L, null, List.of()));
                    return captured;
                });

                scope.join();
                scope.throwIfFailed();

                assertThat(principal.result()).isEqualTo(42L);
                assertThat(SecurityContextHolder.getContext().getAuthentication()).isSameAs(ownerAuth);
            }
        }

        @Test
        @DisplayName("RequestContext carrier is injected by owning module and restored afterwards")
        void requestContext_injectedCarrierInheritedWhenEnabledAndRestored() {
            RequestContext ownerContext = requestContext(1L, "owner");
            RequestContextHolder.set(ownerContext);
            ScopeOptions options = ScopeOptions.builder("request-context")
                    .inheritRequestContext(true)
                    .build();

            ScopedTasks scopedTasks = new DefaultScopedTasks(
                    new DefaultScopeExecutorFactory(new ScopedTaskProperties()),
                    new ScopedTaskProperties(),
                    ScopeObserver.NOOP,
                    List.of(requestContextCarrier())
            );

            try (TaskScope scope = scopedTasks.open("request-context", options)) {
                Subtask<RequestContext> captured = scope.fork("read-request-context", () -> {
                    RequestContext value = RequestContextHolder.get();
                    RequestContextHolder.set(requestContext(2L, "child"));
                    return value;
                });

                scope.join();
                scope.throwIfFailed();

                assertThat(captured.result()).isSameAs(ownerContext);
                assertThat(RequestContextHolder.get()).isSameAs(ownerContext);
            }
        }

        @Test
        @DisplayName("SecurityContext and RequestContext are not inherited by default")
        void securityAndRequestContext_notInheritedByDefault() {
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken(42L, null, List.of()));
            RequestContextHolder.set(requestContext(1L, "owner"));

            try (TaskScope scope = new DefaultScopedTasks().open("defaults")) {
                Subtask<Object> auth = scope.fork("auth", () -> SecurityContextHolder.getContext().getAuthentication());
                Subtask<RequestContext> request = scope.fork("request", RequestContextHolder::get);

                scope.join();
                scope.throwIfFailed();

                assertThat(auth.result()).isNull();
                assertThat(request.result()).isNull();
            }
        }
    }

    private static ContextCarrier<RequestContext> requestContextCarrier() {
        return new ContextCarrier<>() {
            @Override
            public RequestContext capture() {
                return RequestContextHolder.get();
            }

            @Override
            public RequestContext restore(RequestContext snapshot) {
                RequestContext previous = RequestContextHolder.get();
                RequestContextHolder.set(snapshot);
                return previous;
            }

            @Override
            public void clear(RequestContext previous) {
                RequestContextHolder.set(previous);
            }
        };
    }

    @Nested
    @DisplayName("observability")
    class Observability {

        @Test
        @DisplayName("scope observer receives summary when scope closes")
        void scopeObserver_receivesSummaryOnClose() {
            AtomicReference<ScopeReport> observed = new AtomicReference<>();
            ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
            ScopeOptions options = ScopeOptions.builder("observed")
                    .executorOwnedByScope(true)
                    .build();

            try (TaskScope scope = new DefaultTaskScope(options, executor, List.of(), observed::set)) {
                scope.fork("ok", () -> "done");
                scope.join();
                scope.throwIfFailed();
            }

            ScopeReport report = observed.get();
            assertThat(report).isNotNull();
            assertThat(report.scopeName()).isEqualTo("observed");
            assertThat(report.taskCount()).isEqualTo(1);
            assertThat(report.successCount()).isEqualTo(1);
            assertThat(report.failedCount()).isZero();
            assertThat(report.cancelledCount()).isZero();
            assertThat(report.elapsed()).isPositive();
            assertThat(report.slowestTaskName()).isEqualTo("ok");
        }

        @Test
        @DisplayName("scope observer failure does not fail scope close")
        void scopeObserver_failureDoesNotFailScopeClose() {
            ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
            ScopeOptions options = ScopeOptions.builder("observer-failure")
                    .executorOwnedByScope(true)
                    .build();

            try (TaskScope scope = new DefaultTaskScope(options, executor, List.of(), report -> {
                throw new IllegalStateException("observer failed");
            })) {
                scope.fork("ok", () -> "done");
                scope.join();
                scope.throwIfFailed();
            }
        }
    }

    private static RequestContext requestContext(Long userId, String nickname) {
        return new RequestContext(
                new UserContext(userId, nickname, Set.of("USER"), Set.of()),
                new SessionContext("conv-" + userId, 1, "testing"),
                new PolicyContext(List.of("safe"), false)
        );
    }

    private static final class RecordingExecutorFactory implements ScopeExecutorFactory {

        private ScopeOptions lastOptions;

        @Override
        public ExecutorService create(ScopeOptions options) {
            this.lastOptions = options;
            ThreadFactory factory = Thread.ofVirtual().name("recording-scope-", 0).factory();
            return Executors.newThreadPerTaskExecutor(factory);
        }

        ScopeOptions lastOptions() {
            return lastOptions;
        }
    }
}
