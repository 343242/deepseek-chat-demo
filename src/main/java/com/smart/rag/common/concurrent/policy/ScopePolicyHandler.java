package com.smart.rag.common.concurrent.policy;

import com.smart.rag.common.concurrent.DefaultSubtask;
import com.smart.rag.common.concurrent.ScopeState;

public interface ScopePolicyHandler {

    void onSuccess(DefaultSubtask<?> task, ScopeState state);

    void onFailure(DefaultSubtask<?> task, Throwable error, ScopeState state);

    void onTimeout(ScopeState state);

    boolean shouldStop(ScopeState state);
}
