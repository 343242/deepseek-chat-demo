package com.smart.rag.infrastructure.concurrent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;

/**
 * Scope close-time reporting: warning on unhandled COLLECT_ALL failures,
 * {@link ScopeReport} construction, observer notification, debug log summary.
 */
final class ScopeReporter {

    private static final Logger log = LoggerFactory.getLogger(ScopeReporter.class);

    private final ScopeContext ctx;
    private final ScopeLifecycle lifecycle;

    ScopeReporter(ScopeContext ctx, ScopeLifecycle lifecycle) {
        this.ctx = ctx;
        this.lifecycle = lifecycle;
    }

    void warnAboutUnhandledCollectAllFailures() {
        if (ctx.options.policy() != ScopePolicy.COLLECT_ALL || lifecycle.failuresHandled()) {
            return;
        }
        long unhandledFailureCount = ctx.state.internalSubtasks().stream()
                .filter(task -> task.state() == TaskState.FAILED)
                .filter(task -> !task.failureObserved())
                .count();
        if (unhandledFailureCount > 0) {
            log.warn("TaskScope '{}' closed with {} unhandled failure(s). "
                            + "Call throwIfFailed() or inspect subtask.exception() explicitly.",
                    ctx.options.name(), unhandledFailureCount);
        }
    }

    void logScopeSummary() {
        ScopeReport report = scopeReport();
        notifyScopeObserver(report);
        log.debug("TaskScope '{}' completed: total={}ms, tasks={}, success={}, failed={}, cancelled={}, slowestTask={}",
                report.scopeName(), report.elapsed().toMillis(), report.taskCount(), report.successCount(),
                report.failedCount(), report.cancelledCount(), report.slowestTaskName());
    }

    private void notifyScopeObserver(ScopeReport report) {
        try {
            ctx.scopeObserver.onScopeClosed(report);
        } catch (RuntimeException ex) {
            log.warn("TaskScope '{}' observer failed while handling close report", ctx.options.name(), ex);
        }
    }

    private ScopeReport scopeReport() {
        List<DefaultSubtask<?>> tasks = ctx.state.internalSubtasks();
        long success = tasks.stream().filter(task -> task.state() == TaskState.SUCCESS).count();
        long failed = tasks.stream().filter(task -> task.state() == TaskState.FAILED).count();
        long cancelled = tasks.stream().filter(task -> task.state() == TaskState.CANCELLED).count();
        String slowestTaskName = tasks.stream()
                .max(Comparator.comparing(DefaultSubtask::elapsed))
                .map(DefaultSubtask::name)
                .orElse("-");
        Duration elapsed = Duration.ofNanos(System.nanoTime() - ctx.startNanos);

        return new ScopeReport(ctx.options.name(), elapsed, tasks.size(), success, failed, cancelled, slowestTaskName);
    }
}
