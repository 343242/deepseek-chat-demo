package com.smart.rag.mcp.runtime;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** Builds an SDK client and its HTTP transport without starting the connection. */
@Component
public class McpClientBuilder {

    public McpSyncClient build(String url, @Nullable String bearerToken, Duration timeout) {
        HttpClientStreamableHttpTransport.Builder transportBuilder =
                HttpClientStreamableHttpTransport.builder(url).openConnectionOnStartup(false);
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
