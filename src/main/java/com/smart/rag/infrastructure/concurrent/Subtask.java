package com.smart.rag.infrastructure.concurrent;

public interface Subtask<T> {

    String name();

    TaskState state();

    T result();

    Throwable exception();
}
