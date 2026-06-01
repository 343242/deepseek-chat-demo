package com.smart.rag.infrastructure.concurrent.policy;

import com.smart.rag.infrastructure.concurrent.DefaultSubtask;
import com.smart.rag.infrastructure.concurrent.ScopeState;

public interface ScopePolicyHandler {

    void onSuccess(DefaultSubtask<?> task, ScopeState state);

    void onFailure(DefaultSubtask<?> task, Throwable error, ScopeState state);

    void onTimeout(ScopeState state);

    boolean shouldStop(ScopeState state);
}
