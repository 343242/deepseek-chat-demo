package com.smart.rag.infrastructure.concurrent.context;

public interface ContextCarrier<S> {

    S capture();

    S restore(S snapshot);

    void clear(S previous);
}
