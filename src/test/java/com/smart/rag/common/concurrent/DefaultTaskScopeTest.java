package com.smart.rag.common.concurrent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

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
                        .satisfies(ex -> assertThat(((ScopeExecutionException) ex).allFailures()).hasSize(1));
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
                            assertThat(scopeError.allFailures()).hasSize(2);
                            assertThat(scopeError.getSuppressed()).hasSize(1);
                        });
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
                cancelled.cancel();
                scope.join();

                assertThatThrownBy(failed::result)
                        .isInstanceOf(SubtaskFailedException.class)
                        .isInstanceOf(SubtaskException.class);
                assertThatThrownBy(cancelled::result)
                        .isInstanceOf(SubtaskCancelledException.class)
                        .isInstanceOf(SubtaskException.class);
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
}
