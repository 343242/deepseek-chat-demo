package com.smart.rag.common.concurrent;

public interface ScopedTasks {

    TaskScope open(String name);

    TaskScope open(String name, ScopePolicy policy);

    TaskScope open(String name, ScopeOptions options);
}
