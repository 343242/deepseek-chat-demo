package com.smart.rag.infrastructure.concurrent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.Callable;

final class ObservedCallable<T> implements Callable<T> {

    private static final Logger log = LoggerFactory.getLogger(ObservedCallable.class);

    private final String scopeName;
    private final Callable<T> delegate;
    private final DefaultSubtask<T> subtask;

    ObservedCallable(String scopeName, Callable<T> delegate, DefaultSubtask<T> subtask) {
        this.scopeName = scopeName;
        this.delegate = delegate;
        this.subtask = subtask;
    }

    @Override
    public T call() throws Exception {
        long start = System.nanoTime();
        if (!subtask.markRunning()) {
            throw new InterruptedException("Subtask '" + subtask.name() + "' was cancelled before execution (state=" + subtask.state() + ")");
        }
        log.debug("TaskScope '{}' subtask '{}' started", scopeName, subtask.name());
        try {
            T value = delegate.call();
            subtask.markSuccess(value, elapsed(start));
            log.debug("TaskScope '{}' subtask '{}' succeeded in {}ms",
                    scopeName, subtask.name(), subtask.elapsed().toMillis());
            return value;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            subtask.markCancelled(elapsed(start));
            log.debug("TaskScope '{}' subtask '{}' cancelled in {}ms",
                    scopeName, subtask.name(), subtask.elapsed().toMillis());
            throw ex;
        } catch (Exception ex) {
            subtask.markFailed(ex, elapsed(start));
            log.debug("TaskScope '{}' subtask '{}' failed in {}ms: {}",
                    scopeName, subtask.name(), subtask.elapsed().toMillis(), ex.getMessage());
            throw ex;
        } catch (Error error) {
            subtask.markFailed(error, elapsed(start));
            throw error;
        } finally {
            subtask.markTerminated();
        }
    }

    private Duration elapsed(long startNanos) {
        return Duration.ofNanos(System.nanoTime() - startNanos);
    }
}
