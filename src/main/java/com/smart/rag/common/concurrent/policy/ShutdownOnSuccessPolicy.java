package com.smart.rag.common.concurrent.policy;

import com.smart.rag.common.concurrent.DefaultSubtask;
import com.smart.rag.common.concurrent.ScopeState;

public final class ShutdownOnSuccessPolicy implements ScopePolicyHandler {

    @Override
    public void onSuccess(DefaultSubtask<?> task, ScopeState state) {
        state.requestStop();
    }

    @Override
    public void onFailure(DefaultSubtask<?> task, Throwable error, ScopeState state) {
        // Keep waiting; a later task may still provide the winning result.
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
