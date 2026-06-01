package com.smart.rag.common.concurrent;

import java.util.concurrent.Callable;

final class ScopeNestingGuard {

    private static final ThreadLocal<Integer> LOCAL_SCOPE_DEPTH =
            ThreadLocal.withInitial(() -> 0);
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
    private static final ThreadLocal<Boolean> SCOPED_SUBTASK =
            ThreadLocal.withInitial(() -> false);

    private ScopeNestingGuard() {
    }

    static void ensureOpenAllowed(String scopeName) {
        if (LOCAL_SCOPE_DEPTH.get() == 0 && INHERITED_SCOPE_DEPTH.get() > 0 && !SCOPED_SUBTASK.get()) {
            throw new ScopeViolationException(
                    "Nested TaskScope '" + scopeName + "' must be opened from a scoped subtask");
        }
    }

    static void scopeOpened() {
        LOCAL_SCOPE_DEPTH.set(LOCAL_SCOPE_DEPTH.get() + 1);
    }

    static void scopeClosed() {
        int depth = LOCAL_SCOPE_DEPTH.get() - 1;
        if (depth <= 0) {
            LOCAL_SCOPE_DEPTH.remove();
        } else {
            LOCAL_SCOPE_DEPTH.set(depth);
        }
    }

    static <T> Callable<T> scopedSubtask(Callable<T> delegate) {
        int capturedLocalDepth = LOCAL_SCOPE_DEPTH.get();
        int capturedInheritedDepth = INHERITED_SCOPE_DEPTH.get();
        return () -> {
            Boolean previousScoped = SCOPED_SUBTASK.get();
            int previousLocal = LOCAL_SCOPE_DEPTH.get();
            int previousInherited = INHERITED_SCOPE_DEPTH.get();
            SCOPED_SUBTASK.set(true);
            LOCAL_SCOPE_DEPTH.set(capturedLocalDepth);
            INHERITED_SCOPE_DEPTH.set(capturedInheritedDepth);
            try {
                return delegate.call();
            } finally {
                SCOPED_SUBTASK.set(previousScoped);
                LOCAL_SCOPE_DEPTH.set(previousLocal);
                INHERITED_SCOPE_DEPTH.set(previousInherited);
            }
        };
    }
}
