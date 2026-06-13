package com.smart.rag.common.concurrent;

import com.smart.rag.infrastructure.concurrent.*;
import org.slf4j.MDC;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Standalone smoke tests for the structured task scope utility.
 *
 * <p>This class intentionally avoids JUnit so it can be compiled and run with
 * {@code javac/java} directly after production classes and the project logging jars are
 * available on the classpath. MDC assertions require an SLF4J provider; using only
 * {@code slf4j-api} will correctly fail that case because MDC becomes a no-op.
 */
public final class TestStructuredTask {

    private final ScopedTasks scopedTasks = new DefaultScopedTasks();

    private TestStructuredTask() {}

    public static void main(String[] args) {
        TestStructuredTask tests = new TestStructuredTask();
        tests.run("success results", tests::successResults);
        tests.run("shutdown on failure cancels unfinished task", tests::shutdownOnFailureCancelsUnfinishedTask);
        tests.run("collect all keeps successful results", tests::collectAllKeepsSuccessfulResults);
        tests.run("joinUntil timeout cancels unfinished task", tests::joinUntilTimeoutCancelsUnfinishedTask);
        tests.run("owner thread violation", tests::ownerThreadViolation);
        tests.run("MDC propagation", tests::mdcPropagation);
        tests.run("subtask result state exceptions", tests::subtaskResultStateExceptions);
        System.out.println("Structured task standalone tests passed.");
    }

    private void successResults() {
        try (TaskScope scope = scopedTasks.open("standalone-success")) {
            Subtask<String> first = scope.fork("first", () -> "alpha");
            Subtask<Integer> second = scope.fork("second", () -> 42);

            scope.join();
            scope.throwIfFailed();

            requireEquals("alpha", first.result(), "first result");
            requireEquals(42, second.result(), "second result");
        }
    }

    private void shutdownOnFailureCancelsUnfinishedTask() throws Exception {
        CountDownLatch slowStarted = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean();

        try (TaskScope scope = scopedTasks.open("standalone-fail-fast")) {
            Subtask<String> slow = scope.fork("slow", () -> {
                slowStarted.countDown();
                try {
                    Thread.sleep(Duration.ofSeconds(10));
                } catch (InterruptedException ex) {
                    interrupted.set(true);
                    throw ex;
                }
                return "slow";
            });
            scope.fork("failed", () -> {
                require(slowStarted.await(1, TimeUnit.SECONDS), "slow task did not start");
                throw new IllegalStateException("boom");
            });

            scope.join();

            requireEquals(TaskState.CANCELLED, slow.state(), "slow task state");
            require(interrupted.get(), "slow task was not interrupted");
            expectThrows(ScopeExecutionException.class, scope::throwIfFailed, "throwIfFailed");
        }
    }

    private void collectAllKeepsSuccessfulResults() {
        try (TaskScope scope = scopedTasks.open("standalone-collect", ScopePolicy.COLLECT_ALL)) {
            Subtask<String> success = scope.fork("success", () -> "ok");
            Subtask<String> failure = scope.fork("failure", () -> {
                throw new IllegalStateException("bad candidate");
            });

            scope.join();

            requireEquals("ok", success.result(), "successful collect result");
            require(failure.exception() instanceof IllegalStateException, "failure was not recorded");
            expectThrows(ScopeExecutionException.class, scope::throwIfFailed, "collect throwIfFailed");
        }
    }

    private void joinUntilTimeoutCancelsUnfinishedTask() {
        try (TaskScope scope = scopedTasks.open("standalone-timeout")) {
            Subtask<String> slow = scope.fork("slow", () -> {
                Thread.sleep(Duration.ofSeconds(10));
                return "slow";
            });

            expectThrows(
                    ScopeTimeoutException.class,
                    () -> scope.joinUntil(Duration.ofMillis(50)),
                    "joinUntil timeout"
            );
            requireEquals(TaskState.CANCELLED, slow.state(), "timeout task state");
        }
    }

    private void ownerThreadViolation() throws Exception {
        try (TaskScope scope = scopedTasks.open("standalone-owner")) {
            AtomicReference<Throwable> error = new AtomicReference<>();
            Thread worker = Thread.startVirtualThread(() -> {
                try {
                    scope.fork("illegal", () -> "nope");
                } catch (Throwable ex) {
                    error.set(ex);
                }
            });

            worker.join();

            require(error.get() instanceof ScopeViolationException, "non-owner fork was not rejected");
        }
    }

    private void mdcPropagation() {
        MDC.setContextMap(Map.of("traceId", "standalone-trace"));
        try (TaskScope scope = scopedTasks.open("standalone-mdc")) {
            Subtask<String> traceId = scope.fork("read-mdc", () -> MDC.get("traceId"));

            scope.join();
            scope.throwIfFailed();

            requireEquals("standalone-trace", traceId.result(), "MDC traceId");
            requireEquals("standalone-trace", MDC.get("traceId"), "owner MDC traceId");
        } finally {
            MDC.clear();
        }
    }

    private void subtaskResultStateExceptions() {
        try (TaskScope scope = scopedTasks.open("standalone-result-state", ScopePolicy.COLLECT_ALL)) {
            Subtask<String> failed = scope.fork("failed", () -> {
                throw new IllegalStateException("failed");
            });
            Subtask<String> cancelled = scope.fork("cancelled", () -> {
                Thread.sleep(Duration.ofSeconds(10));
                return "cancelled";
            });

            scope.cancel(cancelled);
            scope.join();

            expectThrows(SubtaskFailedException.class, failed::result, "failed result");
            expectThrows(SubtaskCancelledException.class, cancelled::result, "cancelled result");
        }
    }

    private void run(String name, CheckedRunnable test) {
        try {
            test.run();
            System.out.println("[PASS] " + name);
        } catch (Throwable ex) {
            System.err.println("[FAIL] " + name);
            ex.printStackTrace(System.err);
            System.exit(1);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void requireEquals(Object expected, Object actual, String message) {
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError(message + ": expected=" + expected + ", actual=" + actual);
        }
    }

    private static <T extends Throwable> void expectThrows(
            Class<T> expectedType,
            CheckedRunnable action,
            String message
    ) {
        try {
            action.run();
        } catch (Throwable ex) {
            if (expectedType.isInstance(ex)) {
                return;
            }
            throw new AssertionError(message + ": expected " + expectedType.getSimpleName()
                    + " but got " + ex.getClass().getSimpleName(), ex);
        }
        throw new AssertionError(message + ": expected " + expectedType.getSimpleName());
    }

    @FunctionalInterface
    private interface CheckedRunnable {

        void run() throws Exception;
    }
}
