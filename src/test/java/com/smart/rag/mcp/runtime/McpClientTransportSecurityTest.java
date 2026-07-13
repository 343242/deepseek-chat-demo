package com.smart.rag.mcp.runtime;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase A transport spike: proves the MCP SDK 2.0.0 Streamable HTTP transport
 * accepts a hardened JDK {@link HttpClient.Builder} and that:
 * <ol>
 *   <li>Under {@link HttpClient.Redirect#NEVER}, a 30x response is not followed.</li>
 *   <li>The per-request customizer fires on every SDK HTTP request.</li>
 * </ol>
 * This test does NOT claim DNS pinning — JDK public API cannot inject a validated
 * resolver. Production must enforce network-layer egress separately.
 */
class McpClientTransportSecurityTest {

    private HttpServer server;
    private final AtomicInteger mcpHits = new AtomicInteger();
    private final AtomicInteger redirectTargetHits = new AtomicInteger();
    private volatile boolean customizerHeaderSeen;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);

        server.createContext("/mcp", new RedirectHandler());
        server.createContext("/elsewhere", exchange -> {
            redirectTargetHits.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });

        server.start();
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void redirectNever_doesNotFollowRedirect() {
        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/mcp";

        McpSyncClient client = buildHardenedClient(url);
        // initialize() sends an MCP JSON-RPC POST; the 302 response is not a valid
        // MCP response, so the SDK must fail — but it must NOT follow the redirect.
        assertThrows(RuntimeException.class, client::initialize,
                "initialize must fail on 302, not silently follow");

        assertTrue(mcpHits.get() >= 1, "the MCP endpoint must have received at least one request");
        assertEquals(0, redirectTargetHits.get(),
                "Redirect.NEVER must prevent following the 302 to /elsewhere");
    }

    @Test
    void httpRequestCustomizer_firesForEachRequest() {
        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/mcp";

        HttpClientStreamableHttpTransport.Builder transportBuilder =
                HttpClientStreamableHttpTransport.builder(url)
                        .openConnectionOnStartup(false)
                        .customizeClient(b -> b
                                .followRedirects(HttpClient.Redirect.NEVER)
                                .proxy(ProxySelector.of(null)))
                        .httpRequestCustomizer((request, method, uri, body, context) ->
                                request.header("X-Test-Proven", "true"));

        McpSyncClient client = McpClient.sync(transportBuilder.build())
                .requestTimeout(Duration.ofSeconds(5))
                .clientInfo(new McpSchema.Implementation("test", "1.0.0"))
                .build();

        assertThrows(RuntimeException.class, client::initialize);

        assertTrue(customizerHeaderSeen,
                "the httpRequestCustomizer must have added X-Test-Proven before the request reached the server");
    }

    private McpSyncClient buildHardenedClient(String url) {
        HttpClientStreamableHttpTransport.Builder transportBuilder =
                HttpClientStreamableHttpTransport.builder(url)
                        .openConnectionOnStartup(false)
                        .customizeClient(b -> b
                                .followRedirects(HttpClient.Redirect.NEVER)
                                .proxy(ProxySelector.of(null)));
        return McpClient.sync(transportBuilder.build())
                .requestTimeout(Duration.ofSeconds(5))
                .clientInfo(new McpSchema.Implementation("test", "1.0.0"))
                .build();
    }

    /**
     * Returns 302 to /elsewhere on every request, records hit count and custom header.
     */
    private class RedirectHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            mcpHits.incrementAndGet();
            if ("true".equals(exchange.getRequestHeaders().getFirst("X-Test-Proven"))) {
                customizerHeaderSeen = true;
            }
            // Drain request body so the client doesn't get a broken pipe
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().add("Location", "/elsewhere");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        }
    }
}
