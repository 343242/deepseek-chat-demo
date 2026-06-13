package com.smart.rag.infrastructure.concurrent;

/**
 * Owner-thread invariant and lifecycle flags (closed / joined / failuresHandled).
 *
 * <p>All methods MUST be called from the {@link ScopeContext#ownerThread}.
 * Violations throw {@link ScopeViolationException}.
 */
final class ScopeLifecycle {

    private final ScopeContext ctx;

    ScopeLifecycle(ScopeContext ctx) {
        this.ctx = ctx;
    }

    void ensureOwner(String operation) {
        if (Thread.currentThread() != ctx.ownerThread) {
            throw new ScopeViolationException(
                    operation + " must be called from the scope owner thread");
        }
    }

    boolean isClosed() {
        return ctx.closed.get();
    }

    boolean isJoined() {
        return ctx.joined.get();
    }

    /**
     * Atomically transition to closed. Returns true if this call flipped the flag
     * (caller proceeds with cleanup), false if a previous close already ran.
     */
    boolean markClosed() {
        return ctx.closed.compareAndSet(false, true);
    }

    /**
     * Atomically transition to joined. Returns true if this call flipped the flag.
     * Subsequent calls throw {@link ScopeClosedException}.
     */
    boolean markJoined() {
        if (!ctx.joined.compareAndSet(false, true)) {
            throw new ScopeClosedException(
                    "TaskScope '" + ctx.options.name() + "' has already joined");
        }
        return true;
    }

    /**
     * P1-7: roll back the joined flag after a join attempt failed (e.g. with
     * {@link ScopeTimeoutException}). markJoined() is set early to prevent
     * concurrent re-entry; if the join itself throws, callers must clear the
     * flag so a subsequent join retry is allowed.
     */
    void rollbackJoined() {
        ctx.joined.compareAndSet(true, false);
    }

    void markFailuresHandled() {
        ctx.failuresHandled.set(true);
    }

    boolean failuresHandled() {
        return ctx.failuresHandled.get();
    }
}
