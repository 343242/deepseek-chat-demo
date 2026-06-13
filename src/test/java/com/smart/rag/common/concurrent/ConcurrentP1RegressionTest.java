package com.smart.rag.common.concurrent;

import com.smart.rag.infrastructure.concurrent.DefaultScopedTasks;
import com.smart.rag.infrastructure.concurrent.ScopeExecutionException;
import com.smart.rag.infrastructure.concurrent.ScopeOptions;
import com.smart.rag.infrastructure.concurrent.ScopePolicy;
import com.smart.rag.infrastructure.concurrent.ScopeTimeoutException;
import com.smart.rag.infrastructure.concurrent.ScopeViolationException;
import com.smart.rag.infrastructure.concurrent.ScopedTasks;
import com.smart.rag.infrastructure.concurrent.Subtask;
import com.smart.rag.infrastructure.concurrent.SubtaskCancelledException;
import com.smart.rag.infrastructure.concurrent.TaskScope;
import com.smart.rag.infrastructure.concurrent.TaskState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Regression coverage for the P1 (major) issues fixed in
 * `.trellis/tasks/06-13-fix-concurrent-module-p1-major-issues/` (Commit A).
 *
 * <p>Each nested class covers one P1. Tests live separately from
 * {@link DefaultTaskScopeTest} so the P1 fixes are easy to grep by name.
 */
class ConcurrentP1RegressionTest {

    private final ScopedTasks scopedTasks = new DefaultScopedTasks();

    @Nested
    @DisplayName("P1-3: Subtask.cancel() moved to TaskScope (owner-only)")
    class P1_3_SubtaskCancelMovedToTaskScope {

        @Test
        @DisplayName("scope.cancel(subtask) cancels a sleeping subtask from the owner thread")
        void scopeCancelFromOwnerThread() {
            try (TaskScope scope = scopedTasks.open("p1-3-owner",
                    ScopeOptions.builder("p1-3-owner")
                            .policy(ScopePolicy.COLLECT_ALL)
                            .defaultTimeout(Duration.ofSeconds(2))
                            .build())) {
                Subtask<String> cancelled = scope.fork("cancelled", () -> {
                    Thread.sleep(Duration.ofSeconds(10));
                    return "never";
                });
                // P1-3: cancel is now a TaskScope method, owner-thread checked.
                boolean interrupted = scope.cancel(cancelled);
                scope.join();
                assertThat(cancelled.state()).isEqualTo(TaskState.CANCELLED);
                assertThat(interrupted).isTrue();
                assertThatThrownBy(cancelled::result).isInstanceOf(SubtaskCancelledException.class);
            }
        }

        @Test
        @DisplayName("Subtask interface no longer exposes cancel() (API moved)")
        void subtaskInterfaceHasNoCancelMethod() throws NoSuchMethodException {
            // P1-3: Subtask must NOT declare cancel() anymore.
            assertThatThrownBy(() -> Subtask.class.getMethod("cancel"))
                    .isInstanceOf(NoSuchMethodException.class);
            // TaskScope must declare cancel(Subtask<?>).
            java.lang.reflect.Method m = TaskScope.class.getMethod("cancel", Subtask.class);
            assertThat(m.getReturnType()).isEqualTo(boolean.class);
        }

        @Test
        @DisplayName("cancel(subtask) from a non-owner thread throws ScopeViolationException")
        void cancelFromNonOwnerThreadThrows() throws Exception {
            try (TaskScope scope = scopedTasks.open("p1-3-nonowner",
                    ScopeOptions.builder("p1-3-nonowner")
                            .policy(ScopePolicy.COLLECT_ALL)
                            .defaultTimeout(Duration.ofSeconds(2))
                            .build())) {
                Subtask<String> task = scope.fork("task", () -> {
                    Thread.sleep(Duration.ofSeconds(5));
                    return "slow";
                });
                ExecutorService other = Executors.newSingleThreadExecutor();
                try {
                    // P1-3: invoking cancel from a worker thread must be rejected.
                    assertThatThrownBy(() -> other.submit(() -> scope.cancel(task)).get())
                            .hasRootCauseInstanceOf(ScopeViolationException.class);
                } finally {
                    other.shutdownNow();
                }
                // Cleanup from owner thread.
                scope.cancel(task);
                scope.join();
            }
        }
    }

    @Nested
    @DisplayName("P1-4: TaskScope.join(ScopeJoiner) default method does not call throwIfFailed()")
    class P1_4_JoinJoinerDefaultMethod {

        @Test
        @DisplayName("join(ScopeJoiner) returns collected results and does NOT throw on failures")
        void joinJoinerSkipsThrowIfFailed() {
            try (TaskScope scope = scopedTasks.open("p1-4-joiner",
                    ScopePolicy.COLLECT_ALL)) {
                Subtask<String> success = scope.fork("ok", () -> "ok");
                Subtask<String> failed = scope.fork("fail", () -> {
                    throw new IllegalStateException("boom");
                });
                // P1-4: the default join(ScopeJoiner) does NOT call throwIfFailed();
                // it silently returns only the successful results.
                List<String> results = scope.join(
                        com.smart.rag.infrastructure.concurrent.ScopeJoiner.successfulResults(String.class));
                assertThat(results).containsExactly("ok");
                assertThat(success.state()).isEqualTo(TaskState.SUCCESS);
                assertThat(failed.state()).isEqualTo(TaskState.FAILED);
            }
        }
    }

    @Nested
    @DisplayName("P1-5: ScopeExecutionException.allFailures() renamed to unacceptableFailures()")
    class P1_5_UnacceptableFailuresRenamed {

        @Test
        @DisplayName("unacceptableFailures() returns the failure list on SHUTDOWN_ON_FAILURE")
        void unacceptableFailuresReturnsList() {
            try (TaskScope scope = scopedTasks.open("p1-5-rename")) {
                scope.fork("fail", () -> {
                    throw new IllegalStateException("expected");
                });
                scope.join();
                assertThatThrownBy(scope::throwIfFailed)
                        .isInstanceOf(ScopeExecutionException.class)
                        .satisfies(ex -> assertThat(
                                ((ScopeExecutionException) ex).unacceptableFailures()).hasSize(1));
            }
        }

        @Test
        @DisplayName("allFailures() method no longer exists on ScopeExecutionException")
        void allFailuresMethodRemoved() {
            // P1-5: the old misleading name must be gone.
            assertThatThrownBy(() ->
                    ScopeExecutionException.class.getMethod("allFailures"))
                    .isInstanceOf(NoSuchMethodException.class);
        }
    }

    @Nested
    @DisplayName("P1-6: QuorumSuccessPolicy.onFailure single-scan race fix")
    class P1_6_QuorumRaceSingleScan {

        @Test
        @DisplayName("quorum=2 with 3 tasks where 2 fail fast stops early (not enough remaining)")
        void quorumStopsWhenSuccessImpossible() throws Exception {
            // requiredSuccessCount=2, 3 tasks total. If 2 fail before any success,
            // at most 1 success is still possible — onFailure must requestStop.
            ScopeOptions options = ScopeOptions.builder("p1-6-quorum")
                    .policy(ScopePolicy.QUORUM_SUCCESS)
                    .quorumSuccessCount(2)
                    .defaultTimeout(Duration.ofSeconds(5))
                    .build();
            CountDownLatch slowStarted = new CountDownLatch(1);
            AtomicBoolean slowInterrupted = new AtomicBoolean();
            try (TaskScope scope = scopedTasks.open("p1-6-quorum", options)) {
                scope.fork("fail1", () -> {
                    throw new IllegalStateException("fail1");
                });
                scope.fork("fail2", () -> {
                    throw new IllegalStateException("fail2");
                });
                scope.fork("slow", () -> {
                    slowStarted.countDown();
                    try {
                        Thread.sleep(Duration.ofSeconds(30));
                    } catch (InterruptedException ex) {
                        slowInterrupted.set(true);
                        throw ex;
                    }
                    return "slow";
                });
                assertThat(slowStarted.await(2, TimeUnit.SECONDS)).isTrue();
                // P1-6: after both failures, quorum (2) is unreachable, so the slow
                // task must be cancelled and the join must return promptly.
                long start = System.nanoTime();
                scope.join();
                long elapsedMs = (System.nanoTime() - start) / 1_000_000;
                assertThat(elapsedMs).isLessThan(5_000);
                assertThat(slowInterrupted).isTrue();
            }
        }
    }

    @Nested
    @DisplayName("P1-7: joined flag rolled back on timeout (allows join retry)")
    class P1_7_JoinedFlagRolledBackOnTimeout {

        @Test
        @DisplayName("after a timeout, join() can be retried (no 'already joined' error)")
        void joinRetryableAfterTimeout() {
            // SHUTDOWN_ON_FAILURE throws on timeout (COLLECT_ALL does not), so this
            // policy actually exercises the catch-and-rollback path in joinInternal.
            ScopeOptions options = ScopeOptions.builder("p1-7-retry")
                    .policy(ScopePolicy.SHUTDOWN_ON_FAILURE)
                    .defaultTimeout(Duration.ofMillis(100))
                    .closeTimeout(Duration.ofSeconds(2))
                    .build();
            try (TaskScope scope = scopedTasks.open("p1-7-retry", options)) {
                Subtask<String> slow = scope.fork("slow", () -> {
                    Thread.sleep(Duration.ofSeconds(5));
                    return "slow";
                });
                // First join: times out (task sleeps 5s, timeout 100ms).
                assertThatThrownBy(() -> scope.join())
                        .isInstanceOf(ScopeTimeoutException.class);
                // P1-7: without rollback, the second join would throw ScopeClosedException
                // ("already joined"). With rollback it must succeed (tasks already cancelled).
                // The slow task was cancelled by onTimeout, so the retry terminates quickly.
                scope.join();
                assertThat(slow.state()).isEqualTo(TaskState.CANCELLED);
            }
        }
    }

    @Nested
    @DisplayName("P1-12: PartialSuccessOrThrowPolicy stops on first success")
    class P1_12_PartialSuccessStopsOnFirstSuccess {

        @Test
        @DisplayName("first success cancels remaining slow tasks under PARTIAL_SUCCESS_OR_THROW")
        void firstSuccessCancelsRemaining() throws Exception {
            CountDownLatch slowStarted = new CountDownLatch(1);
            AtomicBoolean slowInterrupted = new AtomicBoolean();
            try (TaskScope scope = scopedTasks.open("p1-12-fast",
                    ScopePolicy.PARTIAL_SUCCESS_OR_THROW)) {
                Subtask<String> slow = scope.fork("slow", () -> {
                    slowStarted.countDown();
                    try {
                        Thread.sleep(Duration.ofSeconds(30));
                    } catch (InterruptedException ex) {
                        slowInterrupted.set(true);
                        throw ex;
                    }
                    return "slow";
                });
                scope.fork("quick", () -> "quick");
                assertThat(slowStarted.await(2, TimeUnit.SECONDS)).isTrue();
                // P1-12: as soon as "quick" succeeds the scope must stop and cancel
                // the still-running "slow" task.
                long start = System.nanoTime();
                scope.join();
                long elapsedMs = (System.nanoTime() - start) / 1_000_000;
                assertThat(elapsedMs).isLessThan(5_000);
                assertThat(slow.state()).isEqualTo(TaskState.CANCELLED);
                assertThat(slowInterrupted).isTrue();
            }
        }

        @Test
        @DisplayName("throwIfFailed does not throw when at least one branch succeeded")
        void throwIfFailedToleratesFailureWhenSuccessExists() {
            try (TaskScope scope = scopedTasks.open("p1-12-throwif",
                    ScopePolicy.PARTIAL_SUCCESS_OR_THROW)) {
                Subtask<String> success = scope.fork("ok", () -> "ok");
                scope.fork("fail", () -> {
                    throw new IllegalStateException("degraded");
                });
                scope.join();
                // P1-12 must preserve the partial-success contract: a single success
                // means failures are caller-managed, so throwIfFailed stays quiet.
                scope.throwIfFailed();
                assertThat(success.result()).isEqualTo("ok");
            }
        }
    }

    @Nested
    @DisplayName("P1-13: join() / joinUntil() javadoc and behavior consistency")
    class P1_13_JoinAndJoinUntilConsistent {

        @Test
        @DisplayName("join() honors defaultTimeout and completes when tasks finish")
        void joinUsesDefaultTimeout() {
            ScopeOptions options = ScopeOptions.builder("p1-13-join")
                    .policy(ScopePolicy.COLLECT_ALL)
                    .defaultTimeout(Duration.ofSeconds(2))
                    .build();
            try (TaskScope scope = scopedTasks.open("p1-13-join", options)) {
                Subtask<String> task = scope.fork("task", () -> "done");
                // P1-13: join() uses defaultTimeout from options (2s here).
                scope.join();
                assertThat(task.state()).isEqualTo(TaskState.SUCCESS);
                assertThat(task.result()).isEqualTo("done");
            }
        }

        @Test
        @DisplayName("joinUntil rejects null, negative, and zero timeouts")
        void joinUntilRejectsInvalidTimeouts() {
            try (TaskScope scope = scopedTasks.open("p1-13-until",
                    ScopePolicy.COLLECT_ALL)) {
                scope.fork("quick", () -> "quick");
                // P1-13: joinUntil must reject null/negative/zero for API consistency.
                assertThatThrownBy(() -> scope.joinUntil(null))
                        .isInstanceOf(ScopeViolationException.class);
                assertThatThrownBy(() -> scope.joinUntil(Duration.ZERO))
                        .isInstanceOf(ScopeViolationException.class);
                assertThatThrownBy(() -> scope.joinUntil(Duration.ofMillis(-1)))
                        .isInstanceOf(ScopeViolationException.class);
                // Positive timeout works and completes.
                scope.joinUntil(Duration.ofSeconds(2));
            }
        }
    }
}
