package com.smart.rag.common.concurrent.context;

public interface ContextCarrier<S> {

    S capture();

    S restore(S snapshot);

    void clear(S previous);
}
