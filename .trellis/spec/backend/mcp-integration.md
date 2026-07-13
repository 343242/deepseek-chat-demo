# MCP Integration Contracts

> Executable contracts for MCP Admin, persistence, callback discovery and runtime calls.

## Scenario: DB-Driven MCP Runtime

### 1. Scope / Trigger

Use this spec when changing MCP Server identity, tool naming/policy, Bearer Token storage, callback discovery, SDK transport security, or `mcp_*` tables.

These paths cross Admin API -> Service -> PostgreSQL -> runtime registry -> Spring AI callback -> MCP SDK. A value that is safe in one layer is not automatically valid in another.

### 2. Signatures

```java
String McpToolUtils.serverId(long rowId);              // "mcp_<row-id>", stable local identity
String McpToolUtils.prefixedToolName(String localServerId, String rawToolName); // <= 64 chars
McpToolConfig McpAuthorizer.requireAuthorized(Subject subject, String prefixedName);
```

```sql
UNIQUE (server_id, tool_name)
CHECK (intent IN ('DIRECT_ANSWER', 'RETRIEVAL', 'DEEP_RETRIEVAL', 'GENERAL_TOOL'))
CHECK (risk IN ('low', 'high'))
```

Token storage envelope (v2 key-ID AES-GCM):

```text
v2:<keyId>:<base64 cipher+tag>:<base64 12-byte IV>
```

Transport security:

```text
HttpClient.Redirect.NEVER  +  ProxySelector.of(null)  +  per-request destination revalidation
```

Runtime resource transition:

```text
Admin owns initialized client -> registry add/replace succeeds -> Registry owns client
```

- `server_id`: stable local identity `mcp_<row-id>`, derived from the DB row ID. Remote Server name is informational metadata only.
- `prefixed_tool_name`: canonical policy/callback key generated from local `server_id` + exact remote tool name, maximum 64 characters.
- `tool_name`: exact raw name returned by the remote MCP Server. SDK `CallToolRequest.name` must use this field, never a substring of `prefixed_tool_name`.
- Tool visibility requires authenticated subject, existing DB row, `enabled=true`, and matching effective intent. Null intent means `GENERAL_TOOL`.
- Direct calls re-read committed DB catalog/policy via indexed `prefixed_tool_name` lookup. No cache invalidation ordering is required.
- Unknown and disabled tools are denied by default.
- PostgreSQL is the sole connection source. YAML `spring.ai.mcp.client.*.connections` is removed; an empty DB starts with an empty Registry.
- Bearer Token crypto: bounded current + one optional previous Master Key. New writes use current; reads accept current/previous. Missing current key rejects writes.
- MCP SDK transport is configured with `Redirect.NEVER` and `ProxySelector.of(null)`. Each HTTP request re-resolves and revalidates DNS. Production also requires network-layer egress enforcement.
- Admin mutations commit desired state and return without waiting for MCP network I/O. Status is a read-time projection (`PENDING`/`READY`/`DEGRADED`/`DISABLED`), never independently persisted.
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
| Stale desired hash during reconcile | Conditional WHERE returns 0 rows; close unowned client, do not publish |
| Reconcile executor saturation | Stop current batch, jittered scan delay; DB due time unchanged |
| Idempotency key reuse with different payload | `ClientException(CONFLICT)` |
| v1 token envelope after V19 | Rejected; V19 deleted all MCP rows, no legacy decrypt |

### 5. Good / Base / Bad Cases

- Good: remote tool `search-docs` on Server row 42 is stored as raw `tool_name=search-docs`, exposed as `prefixed_tool_name=mcp_42_search-docs`, authorized by the local-ID key, then called remotely as `search-docs`.
- Base: no Bearer Token stores DB null; decode returns null and the SDK client is built without Authorization.
- Bad: derive the raw name with `prefixedName.substring(serverId.length() + 1)`. This breaks on hyphens, spaces, and long names.
- Bad: catch token decode failure and build an anonymous client.
- Bad: call `client.close()` after registry handoff; the published runtime snapshot would retain a closed client.
- Bad: use the remote Server name as `server_id` instead of `mcp_<row-id>`; a remote rename changes all policy keys.

### 6. Tests Required

- Naming: local identity `mcp_<row-id>` derivation, prefixed tool name from local ID + raw name, raw-name SDK request.
- Authorization: anonymous, unknown, disabled, matching/mismatching intent, direct-call DB recheck before SDK invocation.
- Token: v2 envelope round-trip, random IV, current/previous key, missing key, tampered cipher, legacy v1 rejection after V19.
- Transport security: `Redirect.NEVER` blocks 30x following, `ProxySelector.of(null)` disables ambient proxy, customizer fires per request.
- Lifecycle: initialize failure, pre-handoff failure, post-handoff failure, exact-instance remove/restore, executor shutdown.
- Discovery: direct indexed DB reads for visible tools; no cache layer between callback and DB.
- Database: V17 → V18 → V19 on PostgreSQL; V19 deletes existing MCP rows and recreates schema with local identity, desired/observed hashes, idempotency key, and retry fields.
- Wiring: start the complete MCP Admin/Registry/Reconciler Bean graph and run MCP ArchUnit dependency rules.

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
