package com.smart.rag.infrastructure.concurrent;

public final class SubtaskCancelledException extends SubtaskException {

    public SubtaskCancelledException(String taskName) {
        super("子任务已取消: " + taskName, null);
    }
}
