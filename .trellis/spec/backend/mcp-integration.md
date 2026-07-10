# MCP Integration Contracts

> Executable contracts for MCP Admin, persistence, callback discovery and runtime calls.

## Scenario: DB-Driven MCP Runtime

### 1. Scope / Trigger

Use this spec when changing MCP Server bootstrap, tool naming/policy, Bearer Token storage, callback discovery, SDK calls, cache invalidation or `mcp_*` tables.

These paths cross Admin API -> Service -> PostgreSQL -> runtime registry -> Spring AI callback -> MCP SDK. A value that is safe in one layer is not automatically valid in another.

### 2. Signatures

```java
String McpToolUtils.canonicalServerId(String remoteServerName); // <= 48 chars
String McpToolUtils.prefixedToolName(String serverId, String rawToolName); // <= 64 chars
McpToolConfig McpAuthorizer.requireAuthorized(Subject subject, String prefixedName);
```

```sql
UNIQUE (server_id, tool_name)
CHECK (intent IN ('DIRECT_ANSWER', 'RETRIEVAL', 'DEEP_RETRIEVAL', 'GENERAL_TOOL'))
CHECK (risk IN ('low', 'high'))
```

Token storage envelope:

```text
v1:<base64 AES-GCM cipher+tag>:<base64 12-byte IV>
```

Runtime resource transition:

```text
Admin owns initialized client -> registry add/replace succeeds -> Registry owns client
```

### 3. Contracts

- `server_id`: canonical remote identity, maximum 48 characters, stable hash suffix on truncation.
- `prefixed_tool_name`: canonical policy/callback key, maximum 64 characters. It is not reversible.
- `tool_name`: exact raw name returned by the remote MCP Server. SDK `CallToolRequest.name` must use this field, never a substring of `prefixed_tool_name`.
- Tool visibility requires authenticated subject, existing DB row, `enabled=true`, and matching effective intent. Null intent means `GENERAL_TOOL`.
- Direct calls re-read the current DB-backed policy and use the returned row's raw `tool_name`.
- Unknown and disabled tools are denied by default; negative lookups may be cached but must be invalidated after Admin writes.
- `spring.ai.mcp.client.streamable-http.connections`: first-bootstrap input only. Runtime state comes from PostgreSQL.
- `mcp.strict-tool-filter`: defaults to `true`.
- `app.security.crypto.master-key`: Base64-encoded 32-byte key. Missing key rejects token writes; malformed/tampered stored tokens fail closed.
- `auto_connect=false`: skip application-start connection. Explicit Admin creation may still handshake once to derive remote identity.
- After registry handoff, failure cleanup must use registry remove/replace. Admin must not directly close a registry-owned client.

### 4. Validation & Error Matrix

| Condition | Required behavior |
|---|---|
| Blank/invalid Admin field | Jakarta Validation or `ClientException(VALIDATION_ERROR/BAD_REQUEST)` |
| Anonymous/unknown/disabled tool | `ClientException(FORBIDDEN)` before remote call |
| Intent mismatch | Tool is not visible |
| Empty canonical server/tool segment | Classified exception; never create an `unknown` key |
| Missing master key on token write | `ClientException(BAD_REQUEST)` |
| Unknown token version, bad Base64/IV, tag mismatch | `ServiceException`, no anonymous retry |
| Remote initialize/list/resource/prompt failure | `RemoteException` with safe Chinese message and preserved cause |
| Placeholder resource/prompt call | `RemoteException(MCP_SERVER_UNREACHABLE)`, never an NPE |
| Invalid persisted security regex/cap | Structured warning and safe defaults |
| Registry handoff failure | Close Admin-owned client |
| Failure after registry handoff | Remove through registry; do not directly close client |

### 5. Good / Base / Bad Cases

- Good: remote tool `search-docs` is stored as raw `tool_name=search-docs`, exposed as canonical `prefixed_tool_name=knowledge_search_docs`, authorized by the canonical key, then called remotely as `search-docs`.
- Base: no Bearer Token stores DB null; decode returns null and the SDK client is built without Authorization.
- Bad: derive the remote name with `prefixedName.substring(serverPrefix.length())`. This changes hyphens, spaces and hashed long names.
- Bad: catch token decode failure and build an anonymous client.
- Bad: call `client.close()` after registry handoff; the published runtime snapshot would retain a closed client.
- Bad: add a unique business index without repairing duplicates allowed by the prior schema.

### 6. Tests Required

- Naming: canonical segment cleanup, long-name hash stability, server namespace prefix, raw-name SDK request.
- Authorization: anonymous, unknown, disabled, matching/mismatching intent, direct-call recheck before SDK invocation.
- Token: round-trip, random IV, missing key, legacy/malformed envelope, bad IV and tampered cipher.
- Lifecycle: initialize failure, pre-handoff failure, post-handoff failure, replace/remove and executor shutdown.
- Discovery: one remote Server failure must not hide healthy Server tools; cached callback arrays must be defensively copied.
- Cache: single/batch Admin updates invalidate policy, list and provider caches; compile/invalidate concurrency cannot restore stale regex patterns.
- Database: run V1-V18 on PostgreSQL 18 and V17-V18 with invalid policy values and duplicate `(server_id, tool_name)` rows; assert normalization, newest-row retention, CHECK/UNIQUE enforcement and V18 repeatability.
- Wiring: start the complete MCP Admin/Provider/Filter/Adapter Spring Bean graph and run MCP ArchUnit rules.

### 7. Wrong vs Correct

#### Wrong

```java
String rawName = prefixedName.substring(serverId.length() + 1);
client.callTool(new CallToolRequest(rawName, arguments));
```

```java
registry.add(config, client);
try {
    mapper.markConnected(config.getServerId());
} catch (RuntimeException failure) {
    client.close(); // Registry still publishes this client.
}
```

#### Correct

```java
McpToolConfig authorized = authorizer.requireAuthorized(subject, prefixedName);
client.callTool(new CallToolRequest(authorized.getToolName(), arguments));
```

```java
registry.add(config, client);
try {
    mapper.markConnected(config.getServerId());
} catch (RuntimeException failure) {
    registry.remove(config.getServerId()); // Registry releases its owned client.
}
```
