package com.smart.rag.common.concurrent;

import com.smart.rag.infrastructure.concurrent.*;
import com.smart.rag.infrastructure.concurrent.context.MdcContextCarrier;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DefaultTaskScopeTest {

    private final ScopedTasks scopedTasks = new DefaultScopedTasks();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Nested
    @DisplayName("successful tasks")
    class SuccessfulTasks {

        @Test
        @DisplayName("join returns all subtask results")
        void join_returnsAllResults() {
            try (TaskScope scope = scopedTasks.open("success")) {
                Subtask<String> first = scope.fork("first", () -> "alpha");
                Subtask<Integer> second = scope.fork("second", () -> 42);

                scope.join();
                scope.throwIfFailed();

                assertThat(first.result()).isEqualTo("alpha");
                assertThat(second.result()).isEqualTo(42);
                assertThat(scope.subtasks()).hasSize(2);
            }
        }

        @Test
        @DisplayName("runnable fork returns null result")
        void runnableFork_returnsNullResult() {
            AtomicBoolean ran = new AtomicBoolean();

            try (TaskScope scope = scopedTasks.open("runnable")) {
                Subtask<Void> task = scope.fork("work", () -> ran.set(true));

                scope.join();
                scope.throwIfFailed();

                assertThat(ran).isTrue();
                assertThat(task.result()).isNull();
            }
        }
    }

    @Nested
    @DisplayName("lifecycle constraints")
    class LifecycleConstraints {

        @Test
        @DisplayName("non owner thread cannot fork")
        void nonOwnerThread_cannotFork() throws Exception {
            try (TaskScope scope = scopedTasks.open("owner")) {
                AtomicReference<Throwable> error = new AtomicReference<>();
                Thread worker = Thread.startVirtualThread(() -> {
                    try {
                        scope.fork("illegal", () -> "nope");
                    } catch (Throwable ex) {
                        error.set(ex);
                    }
                });

                worker.join();

                assertThat(error.get()).isInstanceOf(ScopeViolationException.class);
            }
        }

        @Test
        @DisplayName("non owner thread cannot close")
        void nonOwnerThread_cannotClose() throws Exception {
            try (TaskScope scope = scopedTasks.open("owner-close")) {
                AtomicReference<Throwable> error = new AtomicReference<>();
                Thread worker = Thread.startVirtualThread(() -> {
                    try {
                        scope.close();
                    } catch (Throwable ex) {
                        error.set(ex);
                    }
                });

                worker.join();

                assertThat(error.get()).isInstanceOf(ScopeViolationException.class);
            }
        }

        @Test
        @DisplayName("non owner thread cannot join, joinUntil, or throwIfFailed")
        void nonOwnerThread_cannotUseOwnerOnlyOperations() throws Exception {
            try (TaskScope scope = scopedTasks.open("owner-only-operations")) {
                AtomicReference<Throwable> joinError = new AtomicReference<>();
                AtomicReference<Throwable> joinUntilError = new AtomicReference<>();
                AtomicReference<Throwable> throwIfFailedError = new AtomicReference<>();

                Thread worker = Thread.startVirtualThread(() -> {
                    captureThrowable(scope::join, joinError);
                    captureThrowable(() -> scope.joinUntil(Duration.ofMillis(10)), joinUntilError);
                    captureThrowable(scope::throwIfFailed, throwIfFailedError);
                });

                worker.join();

                assertThat(joinError.get()).isInstanceOf(ScopeViolationException.class);
                assertThat(joinUntilError.get()).isInstanceOf(ScopeViolationException.class);
                assertThat(throwIfFailedError.get()).isInstanceOf(ScopeViolationException.class);
            }
        }

        @Test
        @DisplayName("join can only be called once and fork is forbidden after join")
        void joinOnceAndNoForkAfterJoin() {
            try (TaskScope scope = scopedTasks.open("single-join")) {
                scope.fork("first", () -> "done");
                scope.join();

                assertThatThrownBy(scope::join)
                        .isInstanceOf(ScopeClosedException.class);
                assertThatThrownBy(() -> scope.fork("late", () -> "late"))
                        .isInstanceOf(ScopeClosedException.class);
            }
        }

        @Test
        @DisplayName("closed scope rejects fork")
        void closedScope_rejectsFork() {
            TaskScope scope = scopedTasks.open("closed");
            scope.close();

            assertThatThrownBy(() -> scope.fork("late", () -> "late"))
                    .isInstanceOf(ScopeClosedException.class);
        }

        @Test
        @DisplayName("close is idempotent")
        void close_isIdempotent() {
            TaskScope scope = scopedTasks.open("idempotent-close");
            scope.close();

            assertThatCode(scope::close).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("close without join cancels unfinished tasks and waits for termination")
        void closeWithoutJoin_cancelsUnfinishedTasksAndWaitsForTermination() throws Exception {
            ScopeOptions options = ScopeOptions.builder("close-cancel")
                    .closeTimeout(Duration.ofSeconds(1))
                    .build();
            CountDownLatch started = new CountDownLatch(1);
            CountDownLatch terminated = new CountDownLatch(1);
            AtomicBoolean interrupted = new AtomicBoolean();
            TaskScope scope = scopedTasks.open("close-cancel", options);

            Subtask<String> slow = scope.fork("slow", () -> {
                started.countDown();
                try {
                    Thread.sleep(Duration.ofSeconds(10));
                } catch (InterruptedException ex) {
                    interrupted.set(true);
                    throw ex;
                } finally {
                    terminated.countDown();
                }
                return "slow";
            });
            assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();

            scope.close();

            assertThat(slow.state()).isEqualTo(TaskState.CANCELLED);
            assertThat(interrupted).isTrue();
            assertThat(terminated.await(100, TimeUnit.MILLISECONDS)).isTrue();
        }

        @Test
        @DisplayName("close timeout remains hard limit when task ignores interruption")
        void closeTimeout_remainsHardLimitWhenTaskIgnoresInterruption() throws Exception {
            ScopeOptions options = ScopeOptions.builder("close-hard-limit")
                    .closeTimeout(Duration.ofMillis(100))
                    .build();
            CountDownLatch started = new CountDownLatch(1);
            TaskScope scope = scopedTasks.open("close-hard-limit", options);
            scope.fork("ignore-interrupt", () -> {
                started.countDown();
                long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
                while (System.nanoTime() < deadline) {
                    try {
                        Thread.sleep(Duration.ofMillis(25));
                    } catch (InterruptedException ignored) {
                        // Deliberately ignore interruption to prove closeTimeout is the hard limit.
                    }
                }
                return "late";
            });
            assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();
            long startedClose = System.nanoTime();

            scope.close();

            assertThat(Duration.ofNanos(System.nanoTime() - startedClose)).isLessThan(Duration.ofMillis(600));
        }
    }

    @Nested
    @DisplayName("scope options and entrypoints")
    class ScopeOptionsAndEntrypoints {

        @Test
        @DisplayName("open variants create usable scopes")
        void openVariants_createUsableScopes() {
            try (TaskScope scope = scopedTasks.open("open-default")) {
                Subtask<String> task = scope.fork("task", () -> "default");

                scope.join();
                scope.throwIfFailed();

                assertThat(task.result()).isEqualTo("default");
            }

            try (TaskScope scope = scopedTasks.open("open-policy", ScopePolicy.COLLECT_ALL)) {
                Subtask<String> task = scope.fork("task", () -> "policy");

                scope.join();
                scope.throwIfFailed();

                assertThat(task.result()).isEqualTo("policy");
            }

            ScopeOptions options = ScopeOptions.builder("open-options")
                    .defaultTimeout(Duration.ofSeconds(1))
                    .build();
            try (TaskScope scope = scopedTasks.open("open-options", options)) {
                Subtask<String> task = scope.fork("task", () -> "options");

                scope.join();
                scope.throwIfFailed();

                assertThat(task.result()).isEqualTo("options");
            }
        }

        @Test
        @DisplayName("subtasks returns immutable snapshot")
        void subtasks_returnsImmutableSnapshot() {
            try (TaskScope scope = scopedTasks.open("subtasks-snapshot")) {
                scope.fork("first", () -> "first");
                List<Subtask<?>> snapshot = scope.subtasks();

                scope.fork("second", () -> "second");
                scope.join();
                scope.throwIfFailed();

                assertThat(snapshot).hasSize(1);
                assertThatThrownBy(() -> snapshot.clear())
                        .isInstanceOf(UnsupportedOperationException.class);
                assertThat(scope.subtasks()).hasSize(2);
            }
        }

        @Test
        @DisplayName("open rejects mismatched scope name and options name")
        void open_rejectsMismatchedNameAndOptionsName() {
            ScopeOptions options = ScopeOptions.builder("actual").build();

            assertThatThrownBy(() -> scopedTasks.open("requested", options))
                    .isInstanceOf(ScopeViolationException.class)
                    .hasMessageContaining("scope name and options.name must match");
        }

        @Test
        @DisplayName("scope options validate required values")
        void scopeOptions_validateRequiredValues() {
            assertThatThrownBy(() -> ScopeOptions.builder("").build())
                    .isInstanceOf(ScopeViolationException.class)
                    .hasMessageContaining("scope name must not be blank");
            assertThatThrownBy(() -> ScopeOptions.builder("negative-concurrency").maxConcurrency(-1).build())
                    .isInstanceOf(ScopeViolationException.class)
                    .hasMessageContaining("maxConcurrency");
            assertThatThrownBy(() -> ScopeOptions.builder("negative-default-timeout")
                    .defaultTimeout(Duration.ofMillis(-1))
                    .build())
                    .isInstanceOf(ScopeViolationException.class)
                    .hasMessageContaining("defaultTimeout");
            assertThatThrownBy(() -> ScopeOptions.builder("negative-close-timeout")
                    .closeTimeout(Duration.ofMillis(-1))
                    .build())
                    .isInstanceOf(ScopeViolationException.class)
                    .hasMessageContaining("closeTimeout");
            assertThatThrownBy(() -> ScopeOptions.builder("zero-close-timeout")
                    .closeTimeout(Duration.ZERO)
                    .build())
                    .isInstanceOf(ScopeViolationException.class)
                    .hasMessageContaining("closeTimeout must be positive");
            assertThatThrownBy(() -> ScopeOptions.builder(null).build())
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("name must not be null");
            assertThatThrownBy(() -> ScopeOptions.builder("null-policy").policy(null).build())
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("policy must not be null");
            assertThatThrownBy(() -> ScopeOptions.builder("null-executor-mode").executorMode(null).build())
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("executorMode must not be null");
            assertThatThrownBy(() -> ScopeOptions.builder("null-default-timeout").defaultTimeout(null).build())
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("defaultTimeout must not be null");
            assertThatThrownBy(() -> ScopeOptions.builder("null-close-timeout").closeTimeout(null).build())
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("closeTimeout must not be null");
        }

        @Test
        @DisplayName("joinUntil rejects null, zero, and negative timeouts")
        void joinUntil_rejectsInvalidTimeouts() {
            try (TaskScope scope = scopedTasks.open("invalid-timeout")) {
                assertThatThrownBy(() -> scope.joinUntil(null))
                        .isInstanceOf(ScopeViolationException.class)
                        .hasMessageContaining("joinUntil timeout must be positive");
                assertThatThrownBy(() -> scope.joinUntil(Duration.ZERO))
                        .isInstanceOf(ScopeViolationException.class)
                        .hasMessageContaining("joinUntil timeout must be positive");
                assertThatThrownBy(() -> scope.joinUntil(Duration.ofMillis(-1)))
                        .isInstanceOf(ScopeViolationException.class)
                        .hasMessageContaining("joinUntil timeout must be positive");
            }
        }

        @Test
        @DisplayName("phase 3 supports platform executor and guards shared executor ownership")
        void phase3_supportsConfiguredExecutorModes() {
            ScopeOptions platform = ScopeOptions.builder("platform")
                    .executorMode(ExecutorMode.PLATFORM_THREAD_POOL)
                    .build();

            try (TaskScope scope = scopedTasks.open("platform", platform)) {
                Subtask<Boolean> virtual = scope.fork("is-virtual", () -> Thread.currentThread().isVirtual());
                scope.join();
                scope.throwIfFailed();
                assertThat(virtual.result()).isFalse();
            }
            // P0-7: cross-field validation rejects SHARED_EXECUTOR + executorOwnedByScope=true
            // at option construction time (earlier than the previous factory.create() check)
            assertThatThrownBy(() -> ScopeOptions.builder("shared")
                    .executorMode(ExecutorMode.SHARED_EXECUTOR)
                    .build())
                    .isInstanceOf(ScopeViolationException.class)
                    .hasMessageContaining("SHARED_EXECUTOR requires executorOwnedByScope=false");
        }

        @Test
        @DisplayName("executorOwnedByScope false leaves external executor open")
        void executorOwnedByScopeFalse_leavesExternalExecutorOpen() {
            ExecutorService executor = Executors.newSingleThreadExecutor();
            ScopeOptions options = ScopeOptions.builder("external-executor")
                    .executorOwnedByScope(false)
                    .build();
            try {
                TaskScope scope = new DefaultTaskScope(options, executor, List.of());
                try (scope) {
                    Subtask<String> task = scope.fork("task", () -> "ok");

                    scope.join();
                    scope.throwIfFailed();

                    assertThat(task.result()).isEqualTo("ok");
                }

                assertThat(executor.isShutdown()).isFalse();
            } finally {
                executor.shutdownNow();
            }
        }
    }

    @Nested
    @DisplayName("failure policies")
    class FailurePolicies {

        @Test
        @DisplayName("shutdown on failure cancels unfinished tasks")
        void shutdownOnFailure_cancelsUnfinishedTasks() throws Exception {
            CountDownLatch slowStarted = new CountDownLatch(1);
            AtomicBoolean slowInterrupted = new AtomicBoolean();

            try (TaskScope scope = scopedTasks.open("fail-fast")) {
                Subtask<String> slow = scope.fork("slow", () -> {
                    slowStarted.countDown();
                    try {
                        Thread.sleep(Duration.ofSeconds(10));
                    } catch (InterruptedException ex) {
                        slowInterrupted.set(true);
                        throw ex;
                    }
                    return "slow";
                });
                scope.fork("fail", () -> {
                    assertThat(slowStarted.await(1, TimeUnit.SECONDS)).isTrue();
                    throw new IllegalStateException("boom");
                });

                scope.join();

                assertThat(slow.state()).isEqualTo(TaskState.CANCELLED);
                assertThat(slowInterrupted).isTrue();
                assertThatThrownBy(scope::throwIfFailed)
                        .isInstanceOf(ScopeExecutionException.class)
                        .hasMessageContaining("fail-fast");
            }
        }

        @Test
        @DisplayName("collect all waits for successful tasks after one failure")
        void collectAll_waitsForRemainingTasks() {
            ScopeOptions options = ScopeOptions.builder("collect")
                    .policy(ScopePolicy.COLLECT_ALL)
                    .build();

            try (TaskScope scope = scopedTasks.open("collect", ScopePolicy.COLLECT_ALL)) {
                Subtask<String> success = scope.fork("success", () -> {
                    Thread.sleep(Duration.ofMillis(80));
                    return "ok";
                });
                Subtask<String> failure = scope.fork("failure", () -> {
                    throw new IllegalStateException("bad candidate");
                });

                scope.join();

                assertThat(options.policy()).isEqualTo(ScopePolicy.COLLECT_ALL);
                assertThat(success.result()).isEqualTo("ok");
                assertThat(failure.exception()).isInstanceOf(IllegalStateException.class);
                assertThatThrownBy(scope::throwIfFailed)
                        .isInstanceOf(ScopeExecutionException.class)
                        .satisfies(ex -> assertThat(((ScopeExecutionException) ex).unacceptableFailures()).hasSize(1));
            }
        }

        @Test
        @DisplayName("multiple failures are aggregated with suppressed exceptions")
        void multipleFailures_areAggregated() {
            try (TaskScope scope = scopedTasks.open("aggregate", ScopePolicy.COLLECT_ALL)) {
                scope.fork("first", () -> {
                    throw new IllegalStateException("first");
                });
                scope.fork("second", () -> {
                    throw new IllegalArgumentException("second");
                });

                scope.join();

                assertThatThrownBy(scope::throwIfFailed)
                        .isInstanceOf(ScopeExecutionException.class)
                        .satisfies(ex -> {
                            ScopeExecutionException scopeError = (ScopeExecutionException) ex;
                            assertThat(scopeError.unacceptableFailures()).hasSize(2);
                            assertThat(scopeError.getSuppressed()).hasSize(1);
                        });
            }
        }

        @Test
        @DisplayName("collect all warns when failures are not handled explicitly")
        void collectAll_warnsWhenFailuresAreUnhandled() {
            CapturingAppender appender = attachTaskScopeAppender();
            try {
                try (TaskScope scope = scopedTasks.open("collect-unhandled", ScopePolicy.COLLECT_ALL)) {
                    scope.fork("failure", () -> {
                        throw new IllegalStateException("unhandled");
                    });

                    scope.join();
                }

                assertThat(appender.containsWarn("TaskScope 'collect-unhandled' closed with 1 unhandled failure(s)"))
                        .isTrue();
            } finally {
                detachTaskScopeAppender(appender);
            }
        }

        @Test
        @DisplayName("collect all does not warn after failure exception is inspected")
        void collectAll_doesNotWarnWhenFailureIsObserved() {
            CapturingAppender appender = attachTaskScopeAppender();
            try {
                try (TaskScope scope = scopedTasks.open("collect-observed", ScopePolicy.COLLECT_ALL)) {
                    Subtask<String> failure = scope.fork("failure", () -> {
                        throw new IllegalStateException("observed");
                    });

                    scope.join();

                    assertThat(failure.exception()).isInstanceOf(IllegalStateException.class);
                }

                assertThat(appender.containsWarn("TaskScope 'collect-observed' closed with"))
                        .isFalse();
            } finally {
                detachTaskScopeAppender(appender);
            }
        }
    }

    @Nested
    @DisplayName("timeouts")
    class Timeouts {

        @Test
        @DisplayName("joinUntil timeout cancels unfinished tasks")
        void joinUntilTimeout_cancelsUnfinishedTasks() {
            try (TaskScope scope = scopedTasks.open("timeout")) {
                Subtask<String> slow = scope.fork("slow", () -> {
                    Thread.sleep(Duration.ofSeconds(10));
                    return "slow";
                });

                assertThatThrownBy(() -> scope.joinUntil(Duration.ofMillis(50)))
                        .isInstanceOf(ScopeTimeoutException.class)
                        .hasMessageContaining("timeout");
                assertThat(slow.state()).isEqualTo(TaskState.CANCELLED);
            }
        }

        @Test
        @DisplayName("join uses default timeout when configured")
        void join_usesDefaultTimeout() {
            ScopeOptions options = ScopeOptions.builder("default-timeout")
                    .defaultTimeout(Duration.ofMillis(50))
                    .closeTimeout(Duration.ofMillis(100))
                    .build();

            try (TaskScope scope = scopedTasks.open("default-timeout", options)) {
                scope.fork("slow", () -> {
                    Thread.sleep(Duration.ofSeconds(10));
                    return "slow";
                });

                assertThatThrownBy(scope::join).isInstanceOf(ScopeTimeoutException.class);
            }
        }

        @Test
        @DisplayName("collect all timeout keeps completed results and cancels unfinished tasks")
        void collectAllTimeout_keepsCompletedResults() {
            ScopeOptions options = ScopeOptions.builder("collect-timeout")
                    .policy(ScopePolicy.COLLECT_ALL)
                    .closeTimeout(Duration.ofMillis(100))
                    .build();

            try (TaskScope scope = scopedTasks.open("collect-timeout", options)) {
                Subtask<String> fast = scope.fork("fast", () -> "fast");
                Subtask<String> slow = scope.fork("slow", () -> {
                    Thread.sleep(Duration.ofSeconds(10));
                    return "slow";
                });

                scope.joinUntil(Duration.ofMillis(80));

                assertThat(fast.result()).isEqualTo("fast");
                assertThat(slow.state()).isEqualTo(TaskState.CANCELLED);
            }
        }

        @Test
        @DisplayName("joinUntil explicit timeout overrides a longer default timeout")
        void joinUntil_overridesDefaultTimeout() {
            ScopeOptions options = ScopeOptions.builder("explicit-timeout")
                    .defaultTimeout(Duration.ofSeconds(10))
                    .closeTimeout(Duration.ofMillis(100))
                    .build();

            try (TaskScope scope = scopedTasks.open("explicit-timeout", options)) {
                scope.fork("slow", () -> {
                    Thread.sleep(Duration.ofSeconds(10));
                    return "slow";
                });
                long started = System.nanoTime();

                assertThatThrownBy(() -> scope.joinUntil(Duration.ofMillis(50)))
                        .isInstanceOf(ScopeTimeoutException.class);
                assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(1));
            }
        }

        @Test
        @DisplayName("collect all timeout preserves known failures")
        void collectAllTimeout_preservesKnownFailures() {
            ScopeOptions options = ScopeOptions.builder("collect-timeout-failure")
                    .policy(ScopePolicy.COLLECT_ALL)
                    .closeTimeout(Duration.ofMillis(100))
                    .build();

            try (TaskScope scope = scopedTasks.open("collect-timeout-failure", options)) {
                Subtask<String> failed = scope.fork("failed", () -> {
                    throw new IllegalStateException("known");
                });
                Subtask<String> slow = scope.fork("slow", () -> {
                    Thread.sleep(Duration.ofSeconds(10));
                    return "slow";
                });

                scope.joinUntil(Duration.ofMillis(80));

                assertThat(failed.exception()).isInstanceOf(IllegalStateException.class);
                assertThat(slow.state()).isEqualTo(TaskState.CANCELLED);
                assertThatThrownBy(scope::throwIfFailed)
                        .isInstanceOf(ScopeExecutionException.class)
                        .satisfies(ex -> assertThat(((ScopeExecutionException) ex).unacceptableFailures()).hasSize(1));
            }
        }
    }

    @Nested
    @DisplayName("subtask result state")
    class SubtaskResultState {

        @Test
        @DisplayName("result is non blocking and rejects unfinished tasks")
        void result_rejectsUnfinishedTaskWithoutBlocking() {
            try (TaskScope scope = scopedTasks.open("result-state")) {
                Subtask<String> task = scope.fork("slow", () -> {
                    Thread.sleep(Duration.ofSeconds(10));
                    return "slow";
                });

                long started = System.nanoTime();

                assertThatThrownBy(task::result)
                        .isInstanceOf(SubtaskNotCompletedException.class)
                        .isInstanceOf(SubtaskException.class);
                assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(100));
            }
        }

        @Test
        @DisplayName("failed and cancelled task results throw subtask exceptions")
        void failedAndCancelledResults_throwSubtaskExceptions() {
            try (TaskScope scope = scopedTasks.open("result-failures", ScopePolicy.COLLECT_ALL)) {
                Subtask<String> failed = scope.fork("failed", () -> {
                    throw new IllegalStateException("failed");
                });
                Subtask<String> cancelled = scope.fork("cancelled", () -> {
                    Thread.sleep(Duration.ofSeconds(10));
                    return "cancelled";
                });
                scope.cancel(cancelled);
                scope.join();

                assertThatThrownBy(failed::result)
                        .isInstanceOf(SubtaskFailedException.class)
                        .isInstanceOf(SubtaskException.class);
                assertThatThrownBy(cancelled::result)
                        .isInstanceOf(SubtaskCancelledException.class)
                        .isInstanceOf(SubtaskException.class);
            }
        }

        @Test
        @DisplayName("exception is only populated for failed tasks")
        void exception_isOnlyPopulatedForFailedTasks() {
            try (TaskScope scope = scopedTasks.open("exception-state", ScopePolicy.COLLECT_ALL)) {
                Subtask<String> success = scope.fork("success", () -> "ok");
                Subtask<String> cancelled = scope.fork("cancelled", () -> {
                    Thread.sleep(Duration.ofSeconds(10));
                    return "cancelled";
                });
                scope.cancel(cancelled);

                scope.join();

                assertThat(success.exception()).isNull();
                assertThat(cancelled.exception()).isNull();
            }
        }
    }

    @Nested
    @DisplayName("context propagation")
    class ContextPropagation {

        @Test
        @DisplayName("mdc is visible in subtask and restored after execution")
        void mdc_visibleAndRestored() {
            MDC.setContextMap(Map.of("traceId", "trace-1"));

            try (TaskScope scope = scopedTasks.open("mdc")) {
                Subtask<String> traceId = scope.fork("read-mdc", () -> MDC.get("traceId"));

                scope.join();
                scope.throwIfFailed();

                assertThat(traceId.result()).isEqualTo("trace-1");
                assertThat(MDC.get("traceId")).isEqualTo("trace-1");
            }
        }

        @Test
        @DisplayName("MDC propagation can be disabled")
        void mdcPropagation_canBeDisabled() {
            MDC.setContextMap(Map.of("traceId", "trace-disabled"));
            ScopeOptions options = ScopeOptions.builder("mdc-disabled")
                    .inheritMdc(false)
                    .build();

            try (TaskScope scope = scopedTasks.open("mdc-disabled", options)) {
                Subtask<String> traceId = scope.fork("read-mdc", () -> MDC.get("traceId"));

                scope.join();
                scope.throwIfFailed();

                assertThat(traceId.result()).isNull();
                assertThat(MDC.get("traceId")).isEqualTo("trace-disabled");
            }
        }

        @Test
        @DisplayName("MDC is restored on reusable worker threads")
        void mdc_isRestoredOnReusableWorkerThreads() throws Exception {
            ExecutorService executor = Executors.newSingleThreadExecutor();
            ScopeOptions options = ScopeOptions.builder("mdc-restore")
                    .executorOwnedByScope(false)
                    .build();
            try {
                executor.submit(() -> {
                    MDC.setContextMap(Map.of("traceId", "worker-before"));
                    return null;
                }).get(1, TimeUnit.SECONDS);

                MDC.setContextMap(Map.of("traceId", "owner"));
                TaskScope scope = new DefaultTaskScope(options, executor, List.of(new MdcContextCarrier()));
                try (scope) {
                    Subtask<String> traceId = scope.fork("mutate-mdc", () -> {
                        String captured = MDC.get("traceId");
                        MDC.put("traceId", "worker-mutated");
                        return captured;
                    });

                    scope.join();
                    scope.throwIfFailed();

                    assertThat(traceId.result()).isEqualTo("owner");
                }

                String workerTraceId = executor.submit(() -> MDC.get("traceId")).get(1, TimeUnit.SECONDS);
                assertThat(workerTraceId).isEqualTo("worker-before");
                assertThat(MDC.get("traceId")).isEqualTo("owner");
            } finally {
                executor.shutdownNow();
            }
        }
    }

    @Nested
    @DisplayName("concurrency limits")
    class ConcurrencyLimits {

        @Test
        @DisplayName("maxConcurrency limits simultaneous callable execution")
        void maxConcurrency_limitsSimultaneousCallableExecution() {
            ScopeOptions options = ScopeOptions.builder("limited")
                    .maxConcurrency(2)
                    .build();
            AtomicInteger active = new AtomicInteger();
            AtomicInteger maxActive = new AtomicInteger();

            try (TaskScope scope = scopedTasks.open("limited", options)) {
                for (int i = 0; i < 8; i++) {
                    scope.fork("task-" + i, () -> {
                        int now = active.incrementAndGet();
                        maxActive.accumulateAndGet(now, Math::max);
                        try {
                            Thread.sleep(Duration.ofMillis(40));
                            return "ok";
                        } finally {
                            active.decrementAndGet();
                        }
                    });
                }

                scope.join();
                scope.throwIfFailed();

                assertThat(maxActive.get()).isLessThanOrEqualTo(2);
                assertThat(scope.subtasks()).allSatisfy(task -> assertThat(task.state()).isEqualTo(TaskState.SUCCESS));
            }
        }
    }

    @Nested
    @DisplayName("nested scope interruption")
    class NestedScopeInterruption {

        @Test
        @DisplayName("parent timeout interrupts child join and inner scope cancels its tasks")
        void parentTimeout_interruptsChildJoinAndInnerScopeCancels() {
            AtomicReference<Subtask<String>> innerTask = new AtomicReference<>();

            try (TaskScope outer = scopedTasks.open("outer")) {
                outer.fork("child", () -> {
                    try (TaskScope inner = scopedTasks.open("inner")) {
                        innerTask.set(inner.fork("inner-slow", () -> {
                            Thread.sleep(Duration.ofSeconds(10));
                            return "inner";
                        }));
                        inner.join();
                    }
                    return "child";
                });

                assertThatThrownBy(() -> outer.joinUntil(Duration.ofMillis(80)))
                        .isInstanceOf(ScopeTimeoutException.class);
            }

            assertThat(innerTask.get()).isNotNull();
            assertThat(innerTask.get().state()).isEqualTo(TaskState.CANCELLED);
        }
    }

    @Nested
    @DisplayName("phase 4 policies")
    class Phase4Policies {

        @Test
        @DisplayName("shutdown on success cancels unfinished tasks after first success")
        void shutdownOnSuccess_cancelsUnfinishedTasksAfterFirstSuccess() throws Exception {
            CountDownLatch slowStarted = new CountDownLatch(1);
            AtomicBoolean slowInterrupted = new AtomicBoolean();

            try (TaskScope scope = scopedTasks.open("race-success", ScopePolicy.SHUTDOWN_ON_SUCCESS)) {
                Subtask<String> slow = scope.fork("slow", () -> {
                    slowStarted.countDown();
                    try {
                        Thread.sleep(Duration.ofSeconds(10));
                    } catch (InterruptedException ex) {
                        slowInterrupted.set(true);
                        throw ex;
                    }
                    return "slow";
                });
                Subtask<String> winner = scope.fork("winner", () -> {
                    assertThat(slowStarted.await(1, TimeUnit.SECONDS)).isTrue();
                    return "winner";
                });

                scope.join();
                scope.throwIfFailed();

                assertThat(winner.result()).isEqualTo("winner");
                assertThat(slow.state()).isEqualTo(TaskState.CANCELLED);
                assertThat(slowInterrupted).isTrue();
            }
        }

        @Test
        @DisplayName("shutdown on success aggregates all failures when no task succeeds")
        void shutdownOnSuccess_aggregatesAllFailuresWhenNoTaskSucceeds() {
            try (TaskScope scope = scopedTasks.open("race-all-fail", ScopePolicy.SHUTDOWN_ON_SUCCESS)) {
                scope.fork("first", () -> {
                    throw new IllegalStateException("first");
                });
                scope.fork("second", () -> {
                    throw new IllegalArgumentException("second");
                });

                scope.join();

                assertThatThrownBy(scope::throwIfFailed)
                        .isInstanceOf(ScopeExecutionException.class)
                        .satisfies(ex -> assertThat(((ScopeExecutionException) ex).unacceptableFailures()).hasSize(2));
            }
        }

        @Test
        @DisplayName("partial success throws only when every task fails")
        void partialSuccessOrThrow_throwsOnlyWhenEveryTaskFails() {
            try (TaskScope scope = scopedTasks.open("partial-success", ScopePolicy.PARTIAL_SUCCESS_OR_THROW)) {
                Subtask<String> success = scope.fork("success", () -> "ok");
                Subtask<String> failure = scope.fork("failure", () -> {
                    throw new IllegalStateException("degraded");
                });

                scope.join();
                scope.throwIfFailed();

                assertThat(success.result()).isEqualTo("ok");
                // P1-12: PARTIAL_SUCCESS_OR_THROW now stops on the first success, so the
                // failing branch may have completed as FAILED (exception set) OR been
                // cancelled before its exception was captured (exception null). Either
                // outcome is acceptable — the contract is that one success is enough.
                assertThat(failure.state()).isIn(TaskState.FAILED, TaskState.CANCELLED);
            }

            try (TaskScope scope = scopedTasks.open("partial-all-fail", ScopePolicy.PARTIAL_SUCCESS_OR_THROW)) {
                scope.fork("first", () -> {
                    throw new IllegalStateException("first");
                });
                scope.fork("second", () -> {
                    throw new IllegalArgumentException("second");
                });

                scope.join();

                assertThatThrownBy(scope::throwIfFailed)
                        .isInstanceOf(ScopeExecutionException.class)
                        .satisfies(ex -> assertThat(((ScopeExecutionException) ex).unacceptableFailures()).hasSize(2));
            }
        }

        @Test
        @DisplayName("quorum success cancels remaining tasks once success threshold is reached")
        void quorumSuccess_cancelsRemainingTasksAfterThreshold() throws Exception {
            ScopeOptions options = ScopeOptions.builder("quorum")
                    .policy(ScopePolicy.QUORUM_SUCCESS)
                    .quorumSuccessCount(2)
                    .build();
            CountDownLatch slowStarted = new CountDownLatch(1);
            AtomicBoolean slowInterrupted = new AtomicBoolean();

            try (TaskScope scope = scopedTasks.open("quorum", options)) {
                Subtask<String> first = scope.fork("first", () -> "first");
                Subtask<String> second = scope.fork("second", () -> "second");
                Subtask<String> slow = scope.fork("slow", () -> {
                    slowStarted.countDown();
                    try {
                        Thread.sleep(Duration.ofSeconds(10));
                    } catch (InterruptedException ex) {
                        slowInterrupted.set(true);
                        throw ex;
                    }
                    return "slow";
                });
                assertThat(slowStarted.await(1, TimeUnit.SECONDS)).isTrue();

                scope.join();
                scope.throwIfFailed();

                assertThat(first.result()).isEqualTo("first");
                assertThat(second.result()).isEqualTo("second");
                assertThat(slow.state()).isEqualTo(TaskState.CANCELLED);
                assertThat(slowInterrupted).isTrue();
            }
        }

        @Test
        @DisplayName("scope joiner returns a strongly typed aggregate after join")
        void scopeJoiner_returnsTypedAggregate() {
            try (TaskScope scope = scopedTasks.open("typed-joiner", ScopePolicy.COLLECT_ALL)) {
                scope.fork("alpha", () -> "a");
                scope.fork("bravo", () -> "b");

                List<String> values = scope.join(ScopeJoiner.successfulResults(String.class));

                assertThat(values).containsExactlyInAnyOrder("a", "b");
            }
        }
    }

    @Nested
    @DisplayName("phase 4 stream boundary")
    class Phase4StreamBoundary {

        @Test
        @DisplayName("scoped flux closes scope when subscription is cancelled")
        void scopedFlux_closesScopeWhenSubscriptionIsCancelled() {
            AtomicBoolean closed = new AtomicBoolean();

            Flux<Integer> flux = ScopedFlux.using(
                    () -> scopedTasks.open("stream-boundary"),
                    scope -> Flux.<Integer>never(),
                    scope -> closed.set(true)
            );

            Disposable subscription = flux.subscribe();
            subscription.dispose();

            assertThat(closed).isTrue();
        }
    }

    @Nested
    @DisplayName("phase 4 nested scope violations")
    class Phase4NestedScopeViolations {

        @Test
        @DisplayName("child thread cannot open a detached scope while parent scope is active")
        void childThread_cannotOpenDetachedScopeWhileParentScopeIsActive() throws Exception {
            AtomicReference<Throwable> error = new AtomicReference<>();

            try (TaskScope outer = scopedTasks.open("outer-active")) {
                Thread child = Thread.startVirtualThread(() -> captureThrowable(
                        () -> scopedTasks.open("detached-child").close(),
                        error
                ));

                child.join();

                assertThat(error.get())
                        .isInstanceOf(ScopeViolationException.class)
                        .hasMessageContaining("Nested TaskScope");
                outer.join();
            }
        }

        @Test
        @DisplayName("pooled thread created inside parent scope can open detached scope after parent closes")
        void pooledThreadCreatedInsideParentScope_canOpenDetachedScopeAfterParentCloses() throws Exception {
            AtomicReference<Throwable> error = new AtomicReference<>();

            try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
                try (TaskScope outer = scopedTasks.open("outer-active")) {
                    executor.submit(() -> {
                    }).get();
                    outer.join();
                }

                executor.submit(() -> captureThrowable(
                        () -> scopedTasks.open("detached-after-parent-close").close(),
                        error
                )).get();
            }

            assertThat(error.get()).isNull();
        }
    }

    private static void captureThrowable(CheckedRunnable action, AtomicReference<Throwable> target) {
        try {
            action.run();
        } catch (Throwable ex) {
            target.set(ex);
        }
    }

    private static CapturingAppender attachTaskScopeAppender() {
        // After P0-2 split, scope log lines come from DefaultTaskScope plus the
        // five collaborators (ScopeReporter, ScopeJoinEngine, ScopeTimeoutHandler,
        // ScopeExecutorLifecycle). They are sibling loggers under the same package,
        // so we attach the appender to the package-level logger to capture them all.
        org.apache.logging.log4j.core.Logger logger =
                (org.apache.logging.log4j.core.Logger) LogManager.getLogger("com.smart.rag.infrastructure.concurrent");
        CapturingAppender appender = new CapturingAppender("default-task-scope-capture-" + System.nanoTime());
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    private static void detachTaskScopeAppender(CapturingAppender appender) {
        org.apache.logging.log4j.core.Logger logger =
                (org.apache.logging.log4j.core.Logger) LogManager.getLogger("com.smart.rag.infrastructure.concurrent");
        logger.removeAppender(appender);
        appender.stop();
    }

    static final class CapturingAppender extends AbstractAppender {

        private final List<LogEvent> events = new CopyOnWriteArrayList<>();

        CapturingAppender(String name) {
            super(name, null, PatternLayout.createDefaultLayout(), false, Property.EMPTY_ARRAY);
        }

        @Override
        public void append(LogEvent event) {
            events.add(event.toImmutable());
        }

            boolean containsWarn(String fragment) {
            return events.stream()
                    .anyMatch(event -> event.getLevel().isMoreSpecificThan(Level.WARN)
                            && event.getMessage().getFormattedMessage().contains(fragment));
        }
    }

    @FunctionalInterface
    private interface CheckedRunnable {

        void run() throws Exception;
    }
}
