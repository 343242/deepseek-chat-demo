package com.smart.rag.common.concurrent;

public interface Subtask<T> {

    String name();

    TaskState state();

    T result();

    Throwable exception();

    boolean cancel();
}
