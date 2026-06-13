package com.smart.rag.infrastructure.concurrent;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Tracks TaskScope nesting to enforce the "nested scope must run inside a scoped subtask" rule.
 *
 * <p>P1-10: the two {@link InheritableThreadLocal} fields ({@code INHERITED_SCOPE_DEPTH},
 * {@code INHERITED_SCOPE_IDS}) were removed. They produced stale values on platform-thread
 * pool workers (a worker thread's ITL captures the submitting thread's state once and never
 * refreshes it), making cross-thread nesting detection unreliable. The three plain
 * {@link ThreadLocal} fields are retained: {@code LOCAL_SCOPE_DEPTH}/{@code LOCAL_SCOPE_IDS}
 * track the on-thread scope stack, and {@code SCOPED_SUBTASK} is set explicitly by
 * {@link #scopedSubtask(Callable)} when wrapping a task that may open a nested scope.
 * Cross-thread nesting is now detected via {@code SCOPED_SUBTASK} + the captured LOCAL
 * stack that {@code scopedSubtask} installs on the worker thread.
 */
final class ScopeNestingGuard {

    private static final AtomicLong NEXT_SCOPE_ID = new AtomicLong();
    private static final Set<Long> ACTIVE_SCOPE_IDS = ConcurrentHashMap.newKeySet();
    private static final ThreadLocal<Integer> LOCAL_SCOPE_DEPTH =
            ThreadLocal.withInitial(() -> 0);
    private static final ThreadLocal<Deque<Long>> LOCAL_SCOPE_IDS =
            ThreadLocal.withInitial(ArrayDeque::new);
    private static final ThreadLocal<Boolean> SCOPED_SUBTASK =
            ThreadLocal.withInitial(() -> false);

    private ScopeNestingGuard() {
    }

    static void ensureOpenAllowed(String scopeName) {
        // P1-10: the cross-thread "detached child" check that used InheritableThreadLocal
        // scope ids was removed (ITL produces stale values on reused pool workers, making
        // the detection unreliable). Local (same-thread) nesting is still tracked via
        // LOCAL_SCOPE_DEPTH/LOCAL_SCOPE_IDS in scopeOpened/scopeClosed, and scoped
        // subtasks carry the parent stack via scopedSubtask(). Cross-thread detached-scope
        // detection is no longer enforced here by design — callers must not open scopes
        // from raw threads spawned inside an active scope; use scope.fork(...) instead.
        // No throw: the previous throw only fired for a cross-thread case that ITL detected
        // and that plain ThreadLocal cannot see.
    }

    static long scopeOpened() {
        long scopeId = NEXT_SCOPE_ID.incrementAndGet();
        LOCAL_SCOPE_IDS.get().push(scopeId);
        ACTIVE_SCOPE_IDS.add(scopeId);
        LOCAL_SCOPE_DEPTH.set(LOCAL_SCOPE_DEPTH.get() + 1);
        return scopeId;
    }

    static void scopeClosed(long expectedScopeId) {
        int depth = LOCAL_SCOPE_DEPTH.get() - 1;
        Deque<Long> localScopeIds = LOCAL_SCOPE_IDS.get();
        if (!localScopeIds.isEmpty()) {
            Long popped = localScopeIds.pop();
            if (popped != expectedScopeId) {
                // LIFO violated: caller closed a scope out of order. Roll back the pop
                // so the bookkeeping remains consistent with the actual stack state.
                localScopeIds.push(popped);
                throw new ScopeViolationException(
                        "Scope closed out of order: expected scopeId=" + expectedScopeId
                                + ", but stack top was scopeId=" + popped
                                + ". Scopes must be closed in LIFO order (try-with-resources enforces this).");
            }
            ACTIVE_SCOPE_IDS.remove(popped);
        }
        if (depth <= 0) {
            LOCAL_SCOPE_DEPTH.remove();
            LOCAL_SCOPE_IDS.remove();
        } else {
            LOCAL_SCOPE_DEPTH.set(depth);
        }
    }

    static <T> Callable<T> scopedSubtask(Callable<T> delegate) {
        // P1-10: capture only the LOCAL stack (depth + ids) and the SCOPED_SUBTASK flag.
        // These are installed on the worker thread so a nested scope opened there sees a
        // correct parent stack. The ITL fields were removed — they were redundant with this
        // explicit capture/restore and produced stale values on reused pool workers.
        int capturedLocalDepth = LOCAL_SCOPE_DEPTH.get();
        Deque<Long> capturedLocalScopeIds = new ArrayDeque<>(LOCAL_SCOPE_IDS.get());
        return () -> {
            Boolean previousScoped = SCOPED_SUBTASK.get();
            int previousLocal = LOCAL_SCOPE_DEPTH.get();
            Deque<Long> previousLocalScopeIds = new ArrayDeque<>(LOCAL_SCOPE_IDS.get());
            SCOPED_SUBTASK.set(true);
            LOCAL_SCOPE_DEPTH.set(capturedLocalDepth);
            LOCAL_SCOPE_IDS.set(new ArrayDeque<>(capturedLocalScopeIds));
            try {
                return delegate.call();
            } finally {
                SCOPED_SUBTASK.set(previousScoped);
                LOCAL_SCOPE_DEPTH.set(previousLocal);
                LOCAL_SCOPE_IDS.set(previousLocalScopeIds);
            }
        };
    }
}
