package com.smart.rag.infrastructure.concurrent.context;

import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

public final class SecurityContextCarrier implements ContextCarrier<SecurityContext> {

    @Override
    public SecurityContext capture() {
        SecurityContext current = SecurityContextHolder.getContext();
        SecurityContext snapshot = SecurityContextHolder.createEmptyContext();
        snapshot.setAuthentication(current.getAuthentication());
        return snapshot;
    }

    @Override
    public SecurityContext restore(SecurityContext snapshot) {
        SecurityContext previous = SecurityContextHolder.getContext();
        if (snapshot == null) {
            SecurityContextHolder.clearContext();
        } else {
            SecurityContextHolder.setContext(snapshot);
        }
        return previous;
    }

    @Override
    public void clear(SecurityContext previous) {
        if (previous == null) {
            SecurityContextHolder.clearContext();
        } else {
            SecurityContextHolder.setContext(previous);
        }
    }
}
