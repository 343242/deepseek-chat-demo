package com.smart.rag.mcp.runtime;

import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Deterministic SHA-256 of canonical desired fields (design §4.1).
 * <p>
 * Hash inputs (length-prefixed canonical encoding):
 * <ol>
 *   <li>canonical URL (trimmed)</li>
 *   <li>encrypted token envelope or explicit null marker</li>
 *   <li>enabled boolean</li>
 * </ol>
 * The encrypted envelope (not plaintext token) is hashed. AES-GCM uses a random IV,
 * so every token rotation intentionally changes the desired hash even when the operator
 * submits the same plaintext. Display name, description, autoConnect, and remote name
 * do not alter the client and are excluded.
 * <p>
 * Encryption-key rewrap is explicitly not a desired-state mutation.
 */
@Component
public class McpDesiredStateHasher {

    private static final byte[] NULL_MARKER = "null".getBytes(StandardCharsets.UTF_8);
    private static final byte SEP = 0x1F; // unit separator for unambiguous delimitation

    /**
     * Compute the desired state hash.
     *
     * @param canonicalUrl           trimmed URL
     * @param bearerTokenEncrypted   encrypted envelope or null
     * @param enabled                enabled flag
     * @return lowercase 64-char hex SHA-256
     */
    public String hash(String canonicalUrl, @Nullable String bearerTokenEncrypted, boolean enabled) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] urlBytes = canonicalUrl.getBytes(StandardCharsets.UTF_8);
            // Length-prefixed encoding prevents ambiguity
            md.update(intBytes(urlBytes.length));
            md.update(urlBytes);
            md.update(SEP);

            byte[] tokenBytes = bearerTokenEncrypted != null && !bearerTokenEncrypted.isBlank()
                    ? bearerTokenEncrypted.getBytes(StandardCharsets.UTF_8)
                    : NULL_MARKER;
            md.update(intBytes(tokenBytes.length));
            md.update(tokenBytes);
            md.update(SEP);

            md.update(enabled ? (byte) 1 : (byte) 0);

            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static byte[] intBytes(int value) {
        return new byte[]{
                (byte) (value >>> 24),
                (byte) (value >>> 16),
                (byte) (value >>> 8),
                (byte) value
        };
    }
}
