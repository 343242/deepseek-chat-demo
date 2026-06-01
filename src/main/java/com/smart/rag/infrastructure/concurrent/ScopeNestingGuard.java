package com.smart.rag.infrastructure.concurrent;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

final class ScopeNestingGuard {

    private static final AtomicLong NEXT_SCOPE_ID = new AtomicLong();
    private static final Set<Long> ACTIVE_SCOPE_IDS = ConcurrentHashMap.newKeySet();
    private static final ThreadLocal<Integer> LOCAL_SCOPE_DEPTH =
            ThreadLocal.withInitial(() -> 0);
    private static final ThreadLocal<Deque<Long>> LOCAL_SCOPE_IDS =
            ThreadLocal.withInitial(ArrayDeque::new);
    private static final InheritableThreadLocal<Integer> INHERITED_SCOPE_DEPTH =
            new InheritableThreadLocal<>() {
                @Override
                protected Integer initialValue() {
                    return 0;
                }

                @Override
                protected Integer childValue(Integer parentValue) {
                    return LOCAL_SCOPE_DEPTH.get() + parentValue;
                }
            };
    private static final InheritableThreadLocal<Set<Long>> INHERITED_SCOPE_IDS =
            new InheritableThreadLocal<>() {
                @Override
                protected Set<Long> initialValue() {
                    return Set.of();
                }

                @Override
                protected Set<Long> childValue(Set<Long> parentValue) {
                    Set<Long> inherited = new HashSet<>(parentValue);
                    inherited.addAll(LOCAL_SCOPE_IDS.get());
                    return Set.copyOf(inherited);
                }
            };
    private static final ThreadLocal<Boolean> SCOPED_SUBTASK =
            ThreadLocal.withInitial(() -> false);

    private ScopeNestingGuard() {
    }

    static void ensureOpenAllowed(String scopeName) {
        if (LOCAL_SCOPE_DEPTH.get() == 0 && !activeInheritedScopeIds().isEmpty() && !SCOPED_SUBTASK.get()) {
            throw new ScopeViolationException(
                    "Nested TaskScope '" + scopeName + "' must be opened from a scoped subtask");
        }
    }

    static void scopeOpened() {
        long scopeId = NEXT_SCOPE_ID.incrementAndGet();
        LOCAL_SCOPE_IDS.get().push(scopeId);
        ACTIVE_SCOPE_IDS.add(scopeId);
        LOCAL_SCOPE_DEPTH.set(LOCAL_SCOPE_DEPTH.get() + 1);
    }

    static void scopeClosed() {
        int depth = LOCAL_SCOPE_DEPTH.get() - 1;
        Deque<Long> localScopeIds = LOCAL_SCOPE_IDS.get();
        if (!localScopeIds.isEmpty()) {
            ACTIVE_SCOPE_IDS.remove(localScopeIds.pop());
        }
        if (depth <= 0) {
            LOCAL_SCOPE_DEPTH.remove();
            LOCAL_SCOPE_IDS.remove();
        } else {
            LOCAL_SCOPE_DEPTH.set(depth);
        }
    }

    static <T> Callable<T> scopedSubtask(Callable<T> delegate) {
        int capturedLocalDepth = LOCAL_SCOPE_DEPTH.get();
        int capturedInheritedDepth = INHERITED_SCOPE_DEPTH.get();
        Deque<Long> capturedLocalScopeIds = new ArrayDeque<>(LOCAL_SCOPE_IDS.get());
        Set<Long> capturedInheritedScopeIds = INHERITED_SCOPE_IDS.get();
        return () -> {
            Boolean previousScoped = SCOPED_SUBTASK.get();
            int previousLocal = LOCAL_SCOPE_DEPTH.get();
            int previousInherited = INHERITED_SCOPE_DEPTH.get();
            Deque<Long> previousLocalScopeIds = new ArrayDeque<>(LOCAL_SCOPE_IDS.get());
            Set<Long> previousInheritedScopeIds = INHERITED_SCOPE_IDS.get();
            SCOPED_SUBTASK.set(true);
            LOCAL_SCOPE_DEPTH.set(capturedLocalDepth);
            INHERITED_SCOPE_DEPTH.set(capturedInheritedDepth);
            LOCAL_SCOPE_IDS.set(new ArrayDeque<>(capturedLocalScopeIds));
            INHERITED_SCOPE_IDS.set(capturedInheritedScopeIds);
            try {
                return delegate.call();
            } finally {
                SCOPED_SUBTASK.set(previousScoped);
                LOCAL_SCOPE_DEPTH.set(previousLocal);
                INHERITED_SCOPE_DEPTH.set(previousInherited);
                LOCAL_SCOPE_IDS.set(previousLocalScopeIds);
                INHERITED_SCOPE_IDS.set(previousInheritedScopeIds);
            }
        };
    }

    private static Set<Long> activeInheritedScopeIds() {
        Set<Long> inheritedScopeIds = INHERITED_SCOPE_IDS.get();
        if (inheritedScopeIds.isEmpty()) {
            return inheritedScopeIds;
        }

        Set<Long> activeInheritedScopeIds = new HashSet<>(inheritedScopeIds);
        activeInheritedScopeIds.retainAll(ACTIVE_SCOPE_IDS);
        if (activeInheritedScopeIds.size() != inheritedScopeIds.size()) {
            INHERITED_SCOPE_IDS.set(Set.copyOf(activeInheritedScopeIds));
        }
        return activeInheritedScopeIds;
    }
}
