package com.smart.rag.infrastructure.concurrent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class ScopeState {

    private final ArrayList<DefaultSubtask<?>> subtasks = new ArrayList<>();
    // P1-2: plain boolean — ScopeState instances are only accessed by the owner thread
    // (all call sites are on the scope-owner thread after the P0 split into collaborators).
    // AtomicBoolean was unnecessary overhead.
    private boolean stopRequested;

    public void add(DefaultSubtask<?> subtask) {
        subtasks.add(subtask);
    }

    public List<DefaultSubtask<?>> internalSubtasks() {
        return Collections.unmodifiableList(subtasks);
    }

    public List<Subtask<?>> publicSubtasks() {
        return List.copyOf(subtasks);
    }

    public List<Throwable> failures() {
        return subtasks.stream()
                .map(DefaultSubtask::failure)
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    public long successCount() {
        return subtasks.stream()
                .filter(subtask -> subtask.state() == TaskState.SUCCESS)
                .count();
    }

    public boolean allTerminal() {
        return subtasks.stream().allMatch(DefaultSubtask::isTerminal);
    }

    public void requestStop() {
        stopRequested = true;
    }

    public boolean stopRequested() {
        return stopRequested;
    }
}
