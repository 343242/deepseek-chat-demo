package com.smart.rag.common.concurrent;

import com.smart.rag.config.ThreadPoolConstants;
import com.smart.rag.infrastructure.concurrent.DefaultScopedTasks;
import com.smart.rag.infrastructure.concurrent.DefaultSubtask;
import com.smart.rag.infrastructure.concurrent.executor.DefaultScopeExecutorFactory;
import com.smart.rag.infrastructure.concurrent.ExecutorMode;
import com.smart.rag.infrastructure.concurrent.ScopeExecutionException;
import com.smart.rag.infrastructure.concurrent.ScopeOptions;
import com.smart.rag.infrastructure.concurrent.ScopePolicy;
import com.smart.rag.infrastructure.concurrent.ScopeState;
import com.smart.rag.infrastructure.concurrent.ScopeTimeoutException;
import com.smart.rag.infrastructure.concurrent.ScopeViolationException;
import com.smart.rag.infrastructure.concurrent.ScopedTaskProperties;
import com.smart.rag.infrastructure.concurrent.ScopedTasks;
import com.smart.rag.infrastructure.concurrent.Subtask;
import com.smart.rag.infrastructure.concurrent.SubtaskCancelledException;
import com.smart.rag.infrastructure.concurrent.TaskScope;
import com.smart.rag.infrastructure.concurrent.TaskState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
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

    // ===== Commit B: Resource / Performance =====

    @Nested
    @DisplayName("P1-1: PoolConfig core/max use the same ThreadPoolConstants source")
    class P1_1_PoolConfigConsistentSource {

        @Test
        @DisplayName("default corePoolSize and maxPoolSize are both io-bound (CPU<<1 / CPU<<2)")
        void poolConfigUsesConsistentSource() {
            ScopedTaskProperties.PoolConfig config = new ScopedTaskProperties.PoolConfig();
            // P1-1: both must come from the io-bound family (was lightCore + ioMax mismatch).
            assertThat(config.getCorePoolSize()).isEqualTo(ThreadPoolConstants.ioCore());
            assertThat(config.getMaxPoolSize()).isEqualTo(ThreadPoolConstants.ioMax());
            // Sanity: core <= max and both positive.
            assertThat(config.getCorePoolSize()).isGreaterThan(0);
            assertThat(config.getMaxPoolSize()).isGreaterThanOrEqualTo(config.getCorePoolSize());
        }
    }

    @Nested
    @DisplayName("P1-15: sharedExecutor is created lazily (not in factory constructor)")
    class P1_15_SharedExecutorLazy {

        @Test
        @DisplayName("constructing the factory does not create the shared executor")
        void sharedExecutorLazyCreated() throws Exception {
            DefaultScopeExecutorFactory factory = new DefaultScopeExecutorFactory();
            // P1-15: the sharedExecutor field must be null until SHARED_EXECUTOR is requested.
            Field f = DefaultScopeExecutorFactory.class.getDeclaredField("sharedExecutor");
            f.setAccessible(true);
            assertThat(f.get(factory)).isNull();
            // Creating a VIRTUAL_THREAD_PER_TASK executor must NOT touch the shared pool.
            ScopeOptions opts = ScopeOptions.builder("p1-15-lazy")
                    .executorMode(ExecutorMode.VIRTUAL_THREAD_PER_TASK)
                    .build();
            ExecutorService e1 = factory.create(opts);
            assertThat(f.get(factory)).isNull();
            e1.shutdownNow();
            // Requesting SHARED_EXECUTOR creates it.
            ScopeOptions shared = ScopeOptions.builder("p1-15-shared")
                    .executorMode(ExecutorMode.SHARED_EXECUTOR)
                    .executorOwnedByScope(false)
                    .build();
            ExecutorService e2 = factory.create(shared);
            assertThat(f.get(factory)).isNotNull();
            assertThat(e2).isSameAs(f.get(factory));
            // A second SHARED request returns the same instance.
            ExecutorService e3 = factory.create(shared);
            assertThat(e3).isSameAs(e2);
            factory.close();
        }

        @Test
        @DisplayName("close() is a no-op when the shared executor was never created")
        void closeNoopWhenNeverCreated() throws Exception {
            DefaultScopeExecutorFactory factory = new DefaultScopeExecutorFactory();
            // No SHARED_EXECUTOR created — close must not NPE.
            factory.close();
            Field f = DefaultScopeExecutorFactory.class.getDeclaredField("sharedExecutor");
            f.setAccessible(true);
            assertThat(f.get(factory)).isNull();
        }
    }

    @Nested
    @DisplayName("P1-16: factoryCloseTimeout is a distinct configuration field")
    class P1_16_FactoryCloseTimeout {

        @Test
        @DisplayName("ScopedTaskProperties exposes factoryCloseTimeout distinct from closeTimeout")
        void factoryCloseTimeoutDistinct() {
            ScopedTaskProperties props = new ScopedTaskProperties();
            // P1-16: factoryCloseTimeout defaults to 30s, wider than per-scope closeTimeout (5s).
            assertThat(props.getFactoryCloseTimeout()).isEqualTo(Duration.ofSeconds(30));
            assertThat(props.getCloseTimeout()).isEqualTo(Duration.ofSeconds(5));
            assertThat(props.getFactoryCloseTimeout()).isGreaterThan(props.getCloseTimeout());
            // Setter works and is independent.
            props.setFactoryCloseTimeout(Duration.ofSeconds(60));
            assertThat(props.getFactoryCloseTimeout()).isEqualTo(Duration.ofSeconds(60));
            assertThat(props.getCloseTimeout()).isEqualTo(Duration.ofSeconds(5));
            // Invalid values rejected.
            assertThatThrownBy(() -> props.setFactoryCloseTimeout(null))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> props.setFactoryCloseTimeout(Duration.ZERO))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> props.setFactoryCloseTimeout(Duration.ofSeconds(-1)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    // ===== Commit C: Data Accuracy =====

    @Nested
    @DisplayName("P1-8: cancel() records real elapsed (fork -> cancel wall time)")
    class P1_8_CancelRealElapsed {

        @Test
        @DisplayName("a cancelled subtask reports elapsed > 0")
        void cancelRecordsRealElapsed() throws Exception {
            try (TaskScope scope = scopedTasks.open("p1-8-elapsed",
                    ScopeOptions.builder("p1-8-elapsed")
                            .policy(ScopePolicy.COLLECT_ALL)
                            .defaultTimeout(Duration.ofSeconds(2))
                            .build())) {
                Subtask<String> task = scope.fork("sleep", () -> {
                    Thread.sleep(Duration.ofSeconds(10));
                    return "never";
                });
                Thread.sleep(Duration.ofMillis(100)); // let it start
                scope.cancel(task);
                scope.join();
                assertThat(task.state()).isEqualTo(TaskState.CANCELLED);
                // P1-8: elapsed must be the real fork->cancel wall time (>0), not Duration.ZERO.
                // Use reflection since Subtask interface doesn't expose elapsed(); the concrete
                // DefaultSubtask stores it.
                assertThat(task).isInstanceOf(DefaultSubtask.class);
                Duration elapsed = ((DefaultSubtask<?>) task).elapsed();
                assertThat(elapsed).isGreaterThan(Duration.ZERO);
            }
        }
    }

    @Nested
    @DisplayName("P1-9: SecurityContextCarrier javadoc documents the immutability assumption")
    class P1_9_SecurityContextImmutabilityJavadoc {

        @Test
        @DisplayName("SecurityContextCarrier source contains the immutability warning")
        void securityContextJavadocDocumentsImmutability() throws Exception {
            // P1-9: compiled classes drop javadoc, so verify the source file contains the
            // immutability warning. This guards the documented contract: the captured
            // SecurityContext shares its Authentication reference with the source thread.
            java.nio.file.Path src = java.nio.file.Path.of(
                    "src/main/java/com/smart/rag/infrastructure/concurrent/context/SecurityContextCarrier.java");
            String content = java.nio.file.Files.readString(src);
            assertThat(content).contains("Immutability assumption");
            assertThat(content).contains("UsernamePasswordAuthenticationToken");
            assertThat(content).contains("AnonymousAuthenticationToken");
            assertThat(content).contains("thread-safety");
        }
    }

    @Nested
    @DisplayName("P1-11: waitForTerminationRemaining continues past a single subtask timeout")
    class P1_11_WaitForTerminationContinues {

        @Test
        @DisplayName("one slow subtask does not prevent awaiting the others")
        void waitForTerminationContinuesOnSingleTimeout() throws Exception {
            // Two subtasks: one completes fast, one is cancelled. With the old code a single
            // timeout returned early and could skip awaiting still-terminating siblings.
            // After P1-11 the loop continues so all siblings get a termination wait.
            CountDownLatch slowStarted = new CountDownLatch(1);
            AtomicBoolean slowInterrupted = new AtomicBoolean();
            try (TaskScope scope = scopedTasks.open("p1-11-continue",
                    ScopeOptions.builder("p1-11-continue")
                            .policy(ScopePolicy.COLLECT_ALL)
                            .defaultTimeout(Duration.ofSeconds(2))
                            .closeTimeout(Duration.ofSeconds(2))
                            .build())) {
                Subtask<String> fast = scope.fork("fast", () -> "fast");
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
                assertThat(slowStarted.await(2, TimeUnit.SECONDS)).isTrue();
                scope.cancel(slow);
                scope.join();
                // P1-11: the fast task must have been fully awaited (SUCCESS) regardless of
                // whether the cancelled slow task hit the per-subtask timeout branch first.
                assertThat(fast.state()).isEqualTo(TaskState.SUCCESS);
                assertThat(fast.result()).isEqualTo("fast");
                assertThat(slow.state()).isEqualTo(TaskState.CANCELLED);
            }
        }
    }

    // ===== Commit D: Code Quality =====

    @Nested
    @DisplayName("P1-2: ScopeState.stopRequested is a plain boolean, not AtomicBoolean")
    class P1_2_StopRequestedNotAtomic {

        @Test
        @DisplayName("ScopeState.stopRequested field is a plain boolean")
        void stopRequestedNotAtomic() throws Exception {
            Field f = ScopeState.class.getDeclaredField("stopRequested");
            // P1-2: must be a plain boolean, not java.util.concurrent.atomic.AtomicBoolean.
            assertThat(f.getType()).isEqualTo(boolean.class);
            // Behavioral check: requestStop flips the flag.
            ScopeState state = new ScopeState();
            assertThat(state.stopRequested()).isFalse();
            state.requestStop();
            assertThat(state.stopRequested()).isTrue();
        }
    }

    @Nested
    @DisplayName("P1-10: ScopeNestingGuard no longer uses InheritableThreadLocal")
    class P1_10_InheritedThreadLocalRemoved {

        @Test
        @DisplayName("ScopeNestingGuard has no InheritableThreadLocal fields")
        void inheritedThreadLocalRemoved() throws Exception {
            // P1-10: the two ITL fields must be gone; only plain ThreadLocal remain.
            // ScopeNestingGuard is package-private, so load it reflectively.
            Class<?> guard = Class.forName(
                    "com.smart.rag.infrastructure.concurrent.ScopeNestingGuard");
            java.lang.reflect.Field[] fields = guard.getDeclaredFields();
            long itlCount = java.util.Arrays.stream(fields)
                    .filter(f -> java.lang.reflect.Modifier.isStatic(f.getModifiers()))
                    .filter(f -> f.getType().equals(java.lang.InheritableThreadLocal.class))
                    .count();
            assertThat(itlCount).isZero();
            // LOCAL_SCOPE_DEPTH / LOCAL_SCOPE_IDS / SCOPED_SUBTASK remain as ThreadLocal.
            long tlCount = java.util.Arrays.stream(fields)
                    .filter(f -> java.lang.reflect.Modifier.isStatic(f.getModifiers()))
                    .filter(f -> f.getType().equals(java.lang.ThreadLocal.class))
                    .count();
            assertThat(tlCount).isEqualTo(3);
        }
    }

    @Nested
    @DisplayName("P1-14: markSuccess/markFailed have no dead NEW->SUCCESS/FAILED branch")
    class P1_14_NoNewStateBranch {

        @Test
        @DisplayName("markSuccess only transitions RUNNING->SUCCESS (no NEW->SUCCESS)")
        void markSuccessMarkFailedNoNewBranch() {
            // P1-14: the dead NEW->SUCCESS/FAILED branches were removed. A subtask that is
            // cancelled directly (never runs) must not be flipped to SUCCESS/FAILED.
            DefaultSubtask<String> subtask = new DefaultSubtask<>("p1-14");
            assertThat(subtask.state()).isEqualTo(TaskState.NEW);
            // markSuccess on a NEW subtask is a no-op (markRunning was never called).
            subtask.markSuccess("x", Duration.ZERO);
            assertThat(subtask.state()).isEqualTo(TaskState.NEW);
            // markFailed on a NEW subtask is a no-op.
            subtask.markFailed(new RuntimeException("e"), Duration.ZERO);
            assertThat(subtask.state()).isEqualTo(TaskState.NEW);
            // The only valid path to SUCCESS is via markRunning first.
            assertThat(subtask.markRunning()).isTrue();
            subtask.markSuccess("done", Duration.ZERO);
            assertThat(subtask.state()).isEqualTo(TaskState.SUCCESS);
        }
    }
}
