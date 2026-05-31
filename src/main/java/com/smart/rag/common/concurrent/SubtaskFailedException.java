package com.smart.rag.common.concurrent;

public final class SubtaskFailedException extends SubtaskException {

    public SubtaskFailedException(String taskName, Throwable cause) {
        super("子任务执行失败: " + taskName, cause);
    }
}
