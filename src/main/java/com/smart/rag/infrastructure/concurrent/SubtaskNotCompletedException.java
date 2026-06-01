package com.smart.rag.infrastructure.concurrent;

public final class SubtaskNotCompletedException extends SubtaskException {

    public SubtaskNotCompletedException(String taskName) {
        super("子任务尚未完成: " + taskName, null);
    }
}
