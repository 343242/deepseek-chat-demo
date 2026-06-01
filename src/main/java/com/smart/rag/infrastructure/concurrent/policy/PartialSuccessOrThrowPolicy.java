package com.smart.rag.infrastructure.concurrent.policy;

import com.smart.rag.infrastructure.concurrent.DefaultSubtask;
import com.smart.rag.infrastructure.concurrent.ScopeState;

public final class PartialSuccessOrThrowPolicy implements ScopePolicyHandler {

    @Override
    public void onSuccess(DefaultSubtask<?> task, ScopeState state) {
        // Success is enough to make failures a caller-managed degradation decision.
    }

    @Override
    public void onFailure(DefaultSubtask<?> task, Throwable error, ScopeState state) {
        // Wait for all tasks to know whether at least one branch succeeded.
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
