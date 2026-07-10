package com.smart.rag.mcp.runtime;

import com.smart.rag.infrastructure.exception.ClientException;
import com.smart.rag.infrastructure.exception.errorcode.ClientErrorCode;

import java.net.URI;
import java.util.Locale;
import java.util.Set;

final class McpUriPolicy {

    private static final Set<String> BLOCKED_SCHEMES = Set.of("jar", "netdoc", "ldap", "jndi", "dns");

    private McpUriPolicy() {
    }

    static void requireAllowed(URI uri) {
        String scheme = uri.getScheme();
        if (scheme != null && BLOCKED_SCHEMES.contains(scheme.toLowerCase(Locale.ROOT))) {
            throw new ClientException(ClientErrorCode.BAD_REQUEST, "MCP Resource URI 协议不受支持");
        }
    }
}
