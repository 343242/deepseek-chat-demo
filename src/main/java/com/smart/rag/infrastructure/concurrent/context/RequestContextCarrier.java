package com.smart.rag.infrastructure.concurrent.context;

import com.smart.rag.chat.context.RequestContext;
import com.smart.rag.chat.context.RequestContextHolder;

public final class RequestContextCarrier implements ContextCarrier<RequestContext> {

    @Override
    public RequestContext capture() {
        return RequestContextHolder.get();
    }

    @Override
    public RequestContext restore(RequestContext snapshot) {
        RequestContext previous = RequestContextHolder.get();
        RequestContextHolder.set(snapshot);
        return previous;
    }

    @Override
    public void clear(RequestContext previous) {
        RequestContextHolder.set(previous);
    }
}
