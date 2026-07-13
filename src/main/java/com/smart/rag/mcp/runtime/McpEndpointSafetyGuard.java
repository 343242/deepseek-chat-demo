package com.smart.rag.mcp.runtime;

import com.smart.rag.infrastructure.exception.ClientException;
import com.smart.rag.infrastructure.exception.errorcode.ClientErrorCode;
import com.smart.rag.infrastructure.security.HostSafetyValidator;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URISyntaxException;

/**
 * MCP-specific URL/DNS validation around existing HostSafetyValidator (design §R8).
 * <p>
 * Validates: scheme (http/https only), no credentials in URL, no loopback,
 * no unspecified/multicast/link-local, no RFC1918, no 169.254/16, no IPv6 ULA/LL.
 * Each new connection attempt and each SDK HTTP request re-resolves and revalidates.
 */
@Component
public class McpEndpointSafetyGuard {

    private final HostSafetyValidator hostSafetyValidator;

    public McpEndpointSafetyGuard(HostSafetyValidator hostSafetyValidator) {
        this.hostSafetyValidator = hostSafetyValidator;
    }

    /**
     * Validate an MCP server URL before connection.
     * Throws ClientException on validation failure.
     */
    public void validate(String url) {
        if (url == null || url.isBlank()) {
            throw new ClientException(ClientErrorCode.BAD_REQUEST, "MCP Server URL 不能为空");
        }
        URI parsed;
        try {
            parsed = new URI(url.trim());
        } catch (URISyntaxException e) {
            throw new ClientException(ClientErrorCode.BAD_REQUEST, "MCP Server URL 格式无效");
        }
        if (parsed.getUserInfo() != null) {
            throw new ClientException(ClientErrorCode.BAD_REQUEST, "MCP Server URL 不能包含凭据信息");
        }
        hostSafetyValidator.validate(url);
    }

    /**
     * Revalidate a URI at request time (per SDK HTTP request).
     */
    public void revalidateRequest(URI uri) {
        if (uri == null) {
            return;
        }
        hostSafetyValidator.validate(uri.toString());
    }
}
