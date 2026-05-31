package com.smart.rag.common.concurrent.policy;

import com.smart.rag.common.concurrent.DefaultSubtask;
import com.smart.rag.common.concurrent.ScopeState;

public final class CollectAllPolicy implements ScopePolicyHandler {

    @Override
    public void onSuccess(DefaultSubtask<?> task, ScopeState state) {
        // No-op.
    }

    @Override
    public void onFailure(DefaultSubtask<?> task, Throwable error, ScopeState state) {
        // Collect all terminal states before caller decides how to handle failures.
    }

    @Override
    public void onTimeout(ScopeState state) {
        state.requestStop();
    }

    @Override
    public boolean shouldStop(ScopeState state) {
        return false;
    }
}
