package com.smart.rag.common.concurrent.policy;

import com.smart.rag.common.concurrent.DefaultSubtask;
import com.smart.rag.common.concurrent.ScopeState;

public final class ShutdownOnFailurePolicy implements ScopePolicyHandler {

    @Override
    public void onSuccess(DefaultSubtask<?> task, ScopeState state) {
        // No-op.
    }

    @Override
    public void onFailure(DefaultSubtask<?> task, Throwable error, ScopeState state) {
        state.requestStop();
    }

    @Override
    public void onTimeout(ScopeState state) {
        state.requestStop();
    }

    @Override
    public boolean shouldStop(ScopeState state) {
        return state.stopRequested();
    }
}
