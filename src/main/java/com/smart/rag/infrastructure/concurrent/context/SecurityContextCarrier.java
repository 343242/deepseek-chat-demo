package com.smart.rag.infrastructure.concurrent.context;

import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Captures and restores Spring Security {@link SecurityContext} across thread boundaries.
 *
 * <p><b>Immutability assumption (P1-9):</b> The captured {@link SecurityContext} shares its
 * {@link org.springframework.security.core.Authentication} reference with the source thread.
 * This is safe for Spring Security's built-in implementations
 * ({@link org.springframework.security.authentication.UsernamePasswordAuthenticationToken},
 * {@link org.springframework.security.authentication.AnonymousAuthenticationToken}, etc.)
 * which are immutable. Custom mutable {@code Authentication} implementations must ensure
 * thread-safety or use immutable copies — concurrent mutation will produce non-deterministic
 * context across fork boundaries.
 */
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
