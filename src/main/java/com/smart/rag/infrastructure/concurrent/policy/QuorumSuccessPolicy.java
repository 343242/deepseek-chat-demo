package com.smart.rag.infrastructure.concurrent.policy;

import com.smart.rag.infrastructure.concurrent.DefaultSubtask;
import com.smart.rag.infrastructure.concurrent.ScopeState;

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
        long pending = state.internalSubtasks().stream()
                .filter(t -> !t.isTerminal())
                .count();
        if (state.successCount() + pending < requiredSuccessCount) {
            state.requestStop();
        }
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
