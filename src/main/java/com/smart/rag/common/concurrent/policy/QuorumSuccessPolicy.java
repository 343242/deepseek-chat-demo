package com.smart.rag.common.concurrent.policy;

import com.smart.rag.common.concurrent.DefaultSubtask;
import com.smart.rag.common.concurrent.ScopeState;

public final class QuorumSuccessPolicy implements ScopePolicyHandler {

    private final int requiredSuccessCount;

    public QuorumSuccessPolicy(int requiredSuccessCount) {
        this.requiredSuccessCount = requiredSuccessCount;
    }

    @Override
    public void onSuccess(DefaultSubtask<?> task, ScopeState state) {
        if (state.successCount() >= requiredSuccessCount) {
            state.requestStop();
        }
    }

    @Override
    public void onFailure(DefaultSubtask<?> task, Throwable error, ScopeState state) {
        // Keep waiting; remaining tasks may still satisfy the success quorum.
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
