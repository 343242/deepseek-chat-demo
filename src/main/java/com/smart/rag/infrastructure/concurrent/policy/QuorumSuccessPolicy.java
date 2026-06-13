package com.smart.rag.infrastructure.concurrent.policy;

import com.smart.rag.infrastructure.concurrent.DefaultSubtask;
import com.smart.rag.infrastructure.concurrent.ScopeState;
import com.smart.rag.infrastructure.concurrent.TaskState;

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
        // P1-6: previously this method called state.successCount() then separately
        // streamed internalSubtasks() to count pending — a two-scan race where the
        // success count could change between scans. Single-pass scan below
        // eliminates the race: we observe total / success / terminal atomically
        // (this method is only invoked from the owner thread via
        // drainCompletedSignalsOnOwnerThread, but the single-pass form is also
        // correct under any interleaving of subtask terminal transitions).
        int total = 0;
        int success = 0;
        int terminal = 0;
        for (DefaultSubtask<?> t : state.internalSubtasks()) {
            total++;
            if (t.state() == TaskState.SUCCESS) {
                success++;
            }
            if (t.isTerminal()) {
                terminal++;
            }
        }
        // -1: the current task just failed and is counted in `terminal` (or will
        // be momentarily); subtract it from pending so we don't over-count.
        int pending = total - terminal - 1;
        if (pending < 0) {
            pending = 0;
        }
        if (success + pending < requiredSuccessCount) {
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
