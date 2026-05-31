package com.smart.rag.common.concurrent.context;

@FunctionalInterface
public interface ContextRestorer extends AutoCloseable {

    @Override
    void close();
}
