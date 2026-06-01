package com.smart.rag.infrastructure.concurrent;

public enum TaskState {
    NEW,
    RUNNING,
    SUCCESS,
    FAILED,
    CANCELLED
}
