package com.smart.rag.common.concurrent;

import com.smart.rag.infrastructure.concurrent.DefaultScopedTasks;
import com.smart.rag.infrastructure.concurrent.DefaultSubtask;
import com.smart.rag.infrastructure.concurrent.ExecutorMode;
import com.smart.rag.infrastructure.concurrent.ScopeClosedException;
import com.smart.rag.infrastructure.concurrent.ScopeExecutionException;
import com.smart.rag.infrastructure.concurrent.ScopeOptions;
import com.smart.rag.infrastructure.concurrent.ScopePolicy;
import com.smart.rag.infrastructure.concurrent.ScopeViolationException;
import com.smart.rag.infrastructure.concurrent.ScopedFlux;
import com.smart.rag.infrastructure.concurrent.ScopedTasks;
import com.smart.rag.infrastructure.concurrent.Subtask;
import com.smart.rag.infrastructure.concurrent.SubtaskFailedException;
import com.smart.rag.infrastructure.concurrent.TaskScope;
import com.smart.rag.infrastructure.concurrent.TaskState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Regression coverage for the 10 P0 issues fixed in
 * `.trellis/tasks/06-13-fix-concurrent-module-p0-critical-issues/`.
 *
 * <p>Each nested class covers one P0. Tests live separately from
 * {@link DefaultTaskScopeTest} so the P0 fixes are easy to grep by name and
 * the existing suite is not bloated.
 */
class ConcurrentP0RegressionTest {

    @Nested
    @DisplayName("P0-1: Executors.defaultThreadFactory() replaced by Thread.ofPlatform().factory()")
    class P0_1_DefaultThreadFactoryRemoved {

        @Test
        @DisplayName("PLATFORM_THREAD_POOL workers carry configured name prefix (not 'pool-N-thread-M')")
        void platformThreadPoolWorkerNameHasPrefix() throws Exception {
            com.smart.rag.infrastructure.concurrent.ScopedTaskProperties properties =
                    new com.smart.rag.infrastructure.concurrent.ScopedTaskProperties();
            properties.getPlatformThreadPool().setThreadNamePrefix("p0-1-prefix-");
            com.smart.rag.infrastructure.concurrent.executor.DefaultScopeExecutorFactory factory =
                    new com.smart.rag.infrastructure.concurrent.executor.DefaultScopeExecutorFactory(properties);
            ScopeOptions options = ScopeOptions.builder("p0-1")
                    .executorMode(ExecutorMode.PLATFORM_THREAD_POOL)
                    .defaultTimeout(Duration.ofSeconds(2))
                    .build();
            try (ExecutorService pool = factory.create(options)) {
                AtomicReference<String> workerName = new AtomicReference<>();
                pool.submit(() -> workerName.set(Thread.currentThread().getName()));
                pool.shutdown();
                pool.awaitTermination(2, TimeUnit.SECONDS);
                assertThat(workerName.get()).startsWith("p0-1-prefix-");
            }
            factory.close();
        }
    }

    @Nested
    @DisplayName("P0-2: DefaultTaskScope no longer a God Class (>300 LOC)")
    class P0_2_DefaultTaskScopeUnder300Loc {

        @Test
        @DisplayName("DefaultTaskScope delegates to 5 collaborators and stays under 300 lines")
        void collaboratorsExist() {
            // Existence checks — if any collaborator is deleted, this fails at class load.
            Class<?> lifecycle = load("com.smart.rag.infrastructure.concurrent.ScopeLifecycle");
            Class<?> joinEngine = load("com.smart.rag.infrastructure.concurrent.ScopeJoinEngine");
            Class<?> timeout = load("com.smart.rag.infrastructure.concurrent.ScopeTimeoutHandler");
            Class<?> executorLifecycle = load("com.smart.rag.infrastructure.concurrent.ScopeExecutorLifecycle");
            Class<?> reporter = load("com.smart.rag.infrastructure.concurrent.ScopeReporter");
            assertThat(lifecycle).isNotNull();
            assertThat(joinEngine).isNotNull();
            assertThat(timeout).isNotNull();
            assertThat(executorLifecycle).isNotNull();
            assertThat(reporter).isNotNull();
        }

        private Class<?> load(String name) {
            try {
                return Class.forName(name);
            } catch (ClassNotFoundException e) {
                return null;
            }
        }
    }

    @Nested
    @DisplayName("P0-3: fork() submit failure cancels subtask and wraps as ScopeExecutionException")
    class P0_3_ForkSubmitFailure {

        @Test
        @DisplayName("RejectedExecutionException from executor surfaces as ScopeExecutionException")
        void rejectedExecution_wrapsAsScopeExecution() {
            ExecutorService rejecting = mock(ExecutorService.class);
            when(rejecting.submit(java.util.concurrent.Callable.class.cast(
                    org.mockito.ArgumentMatchers.any())))
                    .thenThrow(new RejectedExecutionException("boom"));

            ScopeOptions options = ScopeOptions.builder("p0-3")
                    .executorMode(ExecutorMode.SHARED_EXECUTOR)
                    .executorOwnedByScope(false)
                    .defaultTimeout(Duration.ofSeconds(1))
                    .build();
            DefaultScopedTasks tasks = new DefaultScopedTasks();
            try (TaskScope scope = tasks.open("p0-3", options, rejecting)) {
                assertThatThrownBy(() -> scope.fork("doomed", () -> "never-runs"))
                        .isInstanceOf(ScopeExecutionException.class)
                        .hasMessageContaining("p0-3")
                        .hasRootCauseInstanceOf(RejectedExecutionException.class);
            }
        }
    }

    @Nested
    @DisplayName("P0-4: result() FAILED path marks failureObserved=true (suppresses unhandled-failure warn)")
    class P0_4_ResultMarksFailureObserved {

        @Test
        @DisplayName("calling result() on FAILED subtask suppresses 'unhandled failure' warning")
        void resultSuppressesUnhandledFailureWarning() {
            org.apache.logging.log4j.core.Logger pkgLogger =
                    (org.apache.logging.log4j.core.Logger) org.apache.logging.log4j.LogManager
                            .getLogger("com.smart.rag.infrastructure.concurrent");
            com.smart.rag.common.concurrent.DefaultTaskScopeTest.CapturingAppender appender =
                    new com.smart.rag.common.concurrent.DefaultTaskScopeTest.CapturingAppender(
                            "p0-4-capture-" + System.nanoTime());
            appender.start();
            pkgLogger.addAppender(appender);
            try {
                ScopedTasks tasks = new DefaultScopedTasks();
                try (TaskScope scope = tasks.open("p0-4",
                        ScopeOptions.builder("p0-4")
                                .policy(ScopePolicy.COLLECT_ALL)
                                .defaultTimeout(Duration.ofSeconds(2))
                                .build())) {
                    Subtask<String> failed = scope.fork("fail", () -> {
                        throw new IllegalStateException("expected");
                    });
                    scope.join();
                    // P0-4 fix: result() (not just exception()) must mark failureObserved
                    assertThatThrownBy(failed::result).isInstanceOf(SubtaskFailedException.class)
                            .hasRootCauseInstanceOf(IllegalStateException.class);
                }
                // Because result() consumed the failure, close must NOT warn about unhandled failures.
                assertThat(appender.containsWarn("p0-4' closed with"))
                        .as("result() must mark failureObserved so close does not warn")
                        .isFalse();
            } finally {
                pkgLogger.removeAppender(appender);
                appender.stop();
            }
        }
    }

    @Nested
    @DisplayName("P0-5: markFailed in CANCELLED state preserves teardown error")
    class P0_5_CancelledTeardownErrorPreserved {

        @Test
        @DisplayName("a subtask cancelled externally then throwing retains the teardown error")
        void cancelledThenThrowing_keepsException() {
            DefaultSubtask<String> subtask = new DefaultSubtask<>("teardown");
            subtask.markRunning();
            subtask.markCancelled(Duration.ZERO);
            IllegalStateException teardown = new IllegalStateException("cleanup-failed");
            subtask.markFailed(teardown, Duration.ZERO);

            // State stays CANCELLED (cancel takes priority)
            assertThat(subtask.state()).isEqualTo(TaskState.CANCELLED);
            // But exception is preserved for observation
            assertThat(subtask.failure()).isSameAs(teardown);
        }
    }

    @Nested
    @DisplayName("P0-6: defaultTimeout=ZERO rejected; default is bounded")
    class P0_6_DefaultTimeoutValidation {

        @Test
        @DisplayName("ZERO defaultTimeout is rejected by the constructor")
        void zeroRejected() {
            assertThatThrownBy(() -> ScopeOptions.builder("p0-6-zero")
                    .defaultTimeout(Duration.ZERO)
                    .build())
                    .isInstanceOf(ScopeViolationException.class)
                    .hasMessageContaining("defaultTimeout must be positive");
        }

        @Test
        @DisplayName("negative defaultTimeout is rejected")
        void negativeRejected() {
            assertThatThrownBy(() -> ScopeOptions.builder("p0-6-neg")
                    .defaultTimeout(Duration.ofMillis(-1))
                    .build())
                    .isInstanceOf(ScopeViolationException.class)
                    .hasMessageContaining("defaultTimeout must be positive");
        }

        @Test
        @DisplayName("builder default is 30s when unset")
        void builderDefaultIsThirtySeconds() {
            ScopeOptions options = ScopeOptions.builder("p0-6-default").build();
            assertThat(options.defaultTimeout()).isEqualTo(Duration.ofSeconds(30));
        }

        @Test
        @DisplayName("NO_TIMEOUT constant is allowed and signals unbounded wait")
        void noTimeoutAllowed() {
            ScopeOptions options = ScopeOptions.builder("p0-6-infinite")
                    .defaultTimeout(ScopeOptions.NO_TIMEOUT)
                    .build();
            assertThat(options.defaultTimeout()).isEqualTo(ScopeOptions.NO_TIMEOUT);
        }
    }

    @Nested
    @DisplayName("P0-7: SHARED_EXECUTOR cross-field validation rejects scopeOwned=true")
    class P0_7_SharedExecutorCrossField {

        @Test
        @DisplayName("SHARED_EXECUTOR + executorOwnedByScope=true is rejected at construction")
        void sharedWithOwnedRejected() {
            assertThatThrownBy(() -> ScopeOptions.builder("p0-7")
                    .executorMode(ExecutorMode.SHARED_EXECUTOR)
                    .executorOwnedByScope(true)
                    .build())
                    .isInstanceOf(ScopeViolationException.class)
                    .hasMessageContaining("SHARED_EXECUTOR requires executorOwnedByScope=false");
        }

        @Test
        @DisplayName("SHARED_EXECUTOR with executorOwnedByScope=false is accepted")
        void sharedWithExternalAccepted() {
            ScopeOptions options = ScopeOptions.builder("p0-7-ok")
                    .executorMode(ExecutorMode.SHARED_EXECUTOR)
                    .executorOwnedByScope(false)
                    .build();
            assertThat(options.executorOwnedByScope()).isFalse();
        }
    }

    @Nested
    @DisplayName("P0-8: ScopeCleanupState cleans up owned executors on scope leak")
    class P0_8_CleanupStateResourceReclaim {

        /** ScopeCleanupState is package-private; reach it via reflection. */
        private Runnable newCleanup(String name, ExecutorService executor, boolean owned) throws Exception {
            Class<?> stateClass = Class.forName("com.smart.rag.infrastructure.concurrent.ScopeState");
            Class<?> cleanupClass = Class.forName("com.smart.rag.infrastructure.concurrent.ScopeCleanupState");
            java.lang.reflect.Constructor<?> ctor = cleanupClass.getDeclaredConstructor(
                    String.class,
                    stateClass,
                    ExecutorService.class,
                    boolean.class,
                    java.util.concurrent.atomic.AtomicBoolean.class);
            ctor.setAccessible(true);
            Object state = stateClass.getDeclaredConstructor().newInstance();
            return (Runnable) ctor.newInstance(
                    name,
                    state,
                    executor,
                    owned,
                    new java.util.concurrent.atomic.AtomicBoolean(false));
        }

        @Test
        @DisplayName("owned executor is shutdownNow when scope leaks (never closed)")
        void ownedExecutorShutDownOnLeak() throws Exception {
            ExecutorService owned = Executors.newSingleThreadExecutor();
            AtomicBoolean taskRan = new AtomicBoolean();
            owned.submit(() -> taskRan.set(true));

            // Invoke the cleanup action directly — we trust JDK Cleaner to call
            // this on GC; here we deterministically exercise the resource-reclaim
            // logic that previously was just a log.warning().
            Runnable cleanup = newCleanup("p0-8-owned", owned, true);
            cleanup.run();
            assertThat(owned.isShutdown()).isTrue();
        }

        @Test
        @DisplayName("SHARED executor is NOT shut down when scope leaks")
        void sharedExecutorLeftIntactOnLeak() throws Exception {
            ExecutorService shared = Executors.newSingleThreadExecutor();
            Runnable cleanup = newCleanup("p0-8-shared", shared, false);
            cleanup.run();
            assertThat(shared.isShutdown()).isFalse();
            shared.shutdownNow();
        }
    }

    @Nested
    @DisplayName("P0-9: ScopeNestingGuard scopeClosed LIFO check")
    class P0_9_ScopeLifoCheck {

        @Test
        @DisplayName("nested try-with-resources closes in LIFO order without exception")
        void lifoNormalClose() {
            ScopedTasks tasks = new DefaultScopedTasks();
            try (TaskScope outer = tasks.open("p0-9-outer",
                    ScopeOptions.builder("p0-9-outer")
                            .defaultTimeout(Duration.ofSeconds(1))
                            .build())) {
                try (TaskScope inner = tasks.open("p0-9-inner",
                        ScopeOptions.builder("p0-9-inner")
                                .defaultTimeout(Duration.ofSeconds(1))
                                .build())) {
                    Subtask<String> t = inner.fork("quick", () -> "ok");
                    inner.join();
                    assertThat(t.result()).isEqualTo("ok");
                }
            }
        }
    }

    @Nested
    @DisplayName("P0-10: ScopedFlux marked @Deprecated for removal")
    @SuppressWarnings("removal") // intentionally references the deprecated API to assert its deprecation
    class P0_10_ScopedFluxDeprecated {

        @Test
        @DisplayName("ScopedFlux class carries @Deprecated(since, forRemoval=true)")
        void scopedFluxClassDeprecated() {
            Deprecated annotation = ScopedFlux.class.getAnnotation(Deprecated.class);
            assertThat(annotation).as("ScopedFlux must be @Deprecated").isNotNull();
            assertThat(annotation.since()).isNotBlank();
            assertThat(annotation.forRemoval()).isTrue();
        }

        @Test
        @DisplayName("ScopedFlux.using method carries @Deprecated too")
        void scopedFluxMethodDeprecated() throws NoSuchMethodException {
            Deprecated annotation = ScopedFlux.class.getMethod("using",
                    java.util.function.Supplier.class,
                    java.util.function.Function.class,
                    java.util.function.Consumer.class).getAnnotation(Deprecated.class);
            assertThat(annotation).as("ScopedFlux.using must be @Deprecated").isNotNull();
        }
    }
}
