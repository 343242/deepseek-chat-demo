package com.smart.rag.common.concurrent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ScopeState {

    private final ArrayList<DefaultSubtask<?>> subtasks = new ArrayList<>();
    private final AtomicBoolean stopRequested = new AtomicBoolean();

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

    public boolean allTerminal() {
        return subtasks.stream().allMatch(DefaultSubtask::isTerminal);
    }

    public void requestStop() {
        stopRequested.set(true);
    }

    public boolean stopRequested() {
        return stopRequested.get();
    }
}
