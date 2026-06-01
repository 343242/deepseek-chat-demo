package com.smart.rag.infrastructure.concurrent.context;

@FunctionalInterface
public interface ContextRestorer extends AutoCloseable {

    @Override
    void close();
}
