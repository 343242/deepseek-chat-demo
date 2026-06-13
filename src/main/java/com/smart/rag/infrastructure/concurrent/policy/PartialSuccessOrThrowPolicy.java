package com.smart.rag.infrastructure.concurrent.policy;

import com.smart.rag.infrastructure.concurrent.DefaultSubtask;
import com.smart.rag.infrastructure.concurrent.ScopeState;

public final class PartialSuccessOrThrowPolicy implements ScopePolicyHandler {

    @Override
    public void onSuccess(DefaultSubtask<?> task, ScopeState state) {
        // P1-12: as soon as one branch succeeds the scope has its partial-success
        // result — there is no reason to keep the remaining (slower) branches
        // alive. Request a stop so the join loop cancels and drains them.
        state.requestStop();
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
        // P1-12: honor the requestStop set by onSuccess so the join loop breaks
        // as soon as the first success is observed (previously this always
        // returned false, defeating the partial-success fast-path).
        return state.stopRequested();
    }
}
