package com.smart.rag.mcp.runtime;

import org.springframework.stereotype.Component;

/**
 * Validates Bearer Token format (design §R8).
 * <p>
 * Bearer Tokens are optional. When present: 1-4096 visible ASCII characters.
 * Sent only in the Authorization header, never in logs/audit/API responses.
 */
@Component
public class McpBearerTokenValidator {

    private static final int MAX_LENGTH = 4096;

    /**
     * Validate a bearer token. Null/blank means "no token" (valid).
     * Throws IllegalArgumentException on validation failure.
     */
    public void validate(String bearerToken) {
        if (bearerToken == null || bearerToken.isBlank()) {
            return; // optional
        }
        if (bearerToken.length() > MAX_LENGTH) {
            throw new IllegalArgumentException(
                    "Bearer Token 不能超过 " + MAX_LENGTH + " 个字符");
        }
        // Visible ASCII only (0x20-0x7E)
        for (int i = 0; i < bearerToken.length(); i++) {
            char c = bearerToken.charAt(i);
            if (c < 0x20 || c > 0x7E) {
                throw new IllegalArgumentException("Bearer Token 只能包含可见 ASCII 字符");
            }
        }
    }
}
