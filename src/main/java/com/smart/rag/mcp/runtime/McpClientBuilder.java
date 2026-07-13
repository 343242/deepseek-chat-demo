package com.smart.rag.mcp.runtime;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.time.Duration;

/**
 * Builds an SDK client and its HTTP transport without starting the connection.
 * <p>
 * The JDK {@link HttpClient.Builder} is hardened with {@link HttpClient.Redirect#NEVER}
 * and {@link ProxySelector#of} (no proxy) to block redirect-following and ambient
 * proxy paths. JDK public API cannot pin a validated DNS result through connect, so
 * production must also enforce network-layer egress that denies private, link-local,
 * and metadata destinations.
 */
@Component
public class McpClientBuilder {

    public McpSyncClient build(String url, @Nullable String bearerToken, Duration timeout) {
        HttpClientStreamableHttpTransport.Builder transportBuilder =
                HttpClientStreamableHttpTransport.builder(url)
                        .openConnectionOnStartup(false)
                        .customizeClient(b -> b
                                .followRedirects(HttpClient.Redirect.NEVER)
                                .proxy(ProxySelector.of(null)));
        if (bearerToken != null && !bearerToken.isBlank()) {
            transportBuilder.httpRequestCustomizer((request, method, uri, body, context) ->
                    request.header("Authorization", "Bearer " + bearerToken));
        }
        return McpClient.sync(transportBuilder.build())
                .requestTimeout(timeout)
                .clientInfo(new McpSchema.Implementation("smart-rag", "1.0.0"))
                .build();
    }
}
