# MCP Transport SSRF Evidence

**Date:** 2026-07-13  
**Repo dependency:** `io.modelcontextprotocol.sdk:mcp-core:2.0.0`  
**Runtime HTTP stack:** JDK `java.net.http.HttpClient`

## Confirmed Local Evidence

- `pom.xml` locks MCP SDK `2.0.0` and Java 21.
- `McpClientBuilder` builds `HttpClientStreamableHttpTransport` directly.
- SDK 2.0.0 bytecode exposes:
  - `HttpClientStreamableHttpTransport.Builder.clientBuilder(HttpClient.Builder)`;
  - `customizeClient(Consumer<HttpClient.Builder>)`;
  - per-request sync/async HTTP request customizers.
- The SDK builder creates a JDK `HttpClient.Builder`, applies connect timeout, builds
  it, and retains the resulting `HttpClient` for Streamable HTTP requests.
- JDK `HttpClient.Builder` supports explicit `followRedirects`, `proxy`, TLS settings,
  and timeout, but has no public DNS resolver or validated-address injection point.
- SDK `McpClientTransport` can technically be implemented by application code.
- OkHttp 4.12 is already a direct project dependency and supports custom DNS, but the
  SDK does not accept an OkHttp client in its Streamable HTTP transport.

## Decision

Use the SDK transport and inject a JDK builder configured with
`HttpClient.Redirect.NEVER` and `ProxySelector.NO_PROXY`. Revalidate the destination in
the SDK request customizer on every request. Preserve default HTTPS hostname/SNI
verification.

This protects redirect and ambient-proxy paths but cannot strictly pin the DNS answer
used by the later JDK connect. Production therefore requires network-layer egress
enforcement that rejects private, link-local, and metadata destinations even if DNS
rebinding wins the application-level time-of-check/time-of-use race.

## Rejected Alternative

Implementing an OkHttp-backed `McpClientTransport` would require duplicating MCP
Streamable HTTP session IDs, SSE response handling, reconnect, DELETE shutdown,
protocol headers, and authorization retry. That is a security-sensitive transport
fork, not a small SSRF wrapper. It is excluded from this cutover and becomes a separate
task only if mandatory network egress enforcement is unavailable.

## Phase A Proof

Before V19 implementation, an executable local-server test must prove that the
configured SDK transport does not follow 30x responses and that the request customizer
runs for every request. The test does not claim DNS pinning; the network-layer control
is an explicit release prerequisite.
