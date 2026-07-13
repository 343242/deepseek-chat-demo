# PRD: DB-Driven MCP Connections

**Status:** planning  
**Priority:** P1  
**Deployment assumption:** one application instance; pre-release data may be reset

## 1. Problem

MCP connection definitions currently have two competing authorities: YAML bootstrap
configuration and PostgreSQL Admin state. Runtime identity is also derived from the
remote Server name, so a remote rename can change local policy keys. The result is
an implicit one-time bootstrap path that does not match the intended product: an
authorized administrator explicitly manages this application as an MCP client.

The replacement must make PostgreSQL the only connection authority, connect and
reconcile tools automatically after an Admin command, and recover enabled
connections after restart. It must do so without turning the Admin module into a
general workflow engine or control plane.

## 2. Product Intent

- An authorized administrator creates, updates, enables, disables, reconnects,
  refreshes, and deletes MCP connections.
- A committed DB row is the durable desired state. In-memory clients are disposable
  observations of that state.
- Remote connection and `tools/list` work happens outside the HTTP request. The
  Admin response acknowledges the committed intent; Server GET/health shows the
  latest observed result.
- Local Server identity is stable and unrelated to the remote Server name.
- The system favors a small state model and conditional DB writes over generation
  tokens, distributed locks, durable operation records, or ordered cache protocols.

## 3. Terminology

| Term | Definition |
|---|---|
| desired state | The committed DB connection definition: canonical URL, encrypted Bearer Token presence/value, and enabled flag. |
| desired state hash | A non-secret digest identifying one exact desired state. |
| observed state hash | The desired hash of the client currently published by this process; null means no applied observation is trusted. |
| local identity | Stable `serverId` generated from the DB row ID as `mcp_<row-id>`. |
| remote identity | Informational Server name returned by MCP initialization. It never participates in local identity or tool keys. |
| catalog synced | Whether a complete tool catalog has been committed for the currently desired connection. |
| connection status | Read-time projection: `PENDING`, `READY`, `DEGRADED`, or `DISABLED`; it is not an independently persisted state machine. |

## 4. Requirements

### R1. PostgreSQL is the sole connection source

- Runtime startup must not bind MCP connections from
  `spring.ai.mcp.client.*.connections`.
- YAML connection definitions, example Server defaults, YAML-to-DB import, and
  implicit first-start bootstrap are removed.
- An empty DB starts successfully with an empty MCP registry.
- Security defaults that are not connection definitions may remain configuration
  driven.

### R2. Stable local identity

- New rows receive a DB ID first, then local identity `mcp_<row-id>` in the same
  explicit transaction.
- All foreign keys, policy lookup, callback names, health output, and circuit
  ownership use local identity.
- `prefixed_tool_name` is generated from local identity and the exact remote tool
  name. Remote Server renames only update informational metadata.
- Existing local MCP Server/tool data is deleted by V19 and recreated through the
  Admin API. No compatibility remapping or dual-read period is required.

### R3. Exact Admin API contract

Existing paths remain under `/api/admin/mcp` and retain the project-wide HTTP 200 +
`GlobalResponse<T>` convention.

| Path | Request contract | Mutation result |
|---|---|---|
| `POST /servers` | `Idempotency-Key` header + existing create body | `ACCEPTED/PENDING` |
| `POST /servers/{id}/update` | update body with required `version` | `ACCEPTED` when URL changes; otherwise `SUCCESS` |
| `POST /servers/{serverId}/update-bearer-token` | nullable token (null clears) + required `version` | `ACCEPTED/PENDING` |
| `POST /servers/{serverId}/enable` | `{ "version": n }` | `ACCEPTED/PENDING` |
| `POST /servers/{serverId}/disable` | `{ "version": n }` | `SUCCESS/DISABLED` |
| `POST /servers/{id}/delete` | `{ "version": n }` | `SUCCESS`, status null |
| `POST /servers/{serverId}/reconnect` | no body | `ACCEPTED/PENDING` |
| `POST /servers/{serverId}/refresh-tools` | no body | `ACCEPTED/PENDING` |
| `POST /servers/bulk-disable` | 1-100 `{serverId, version}` items | `SUCCESS/DISABLED` |
| `POST /servers/bulk-refresh` | 1-100 unique `serverId` values | `ACCEPTED/PENDING` |

Server/tool list paths accept `page` (1-based, default 1) and `size` (default 20,
maximum 100) and return the existing `PagedResult<T>` shape.

Connection mutations return:

```json
{
  "code": 0,
  "message": "success",
  "data": {
    "resourceId": "mcp_42",
    "outcome": "ACCEPTED",
    "status": "PENDING",
    "errorCode": null,
    "errorMessage": null
  }
}
```

`McpMutationResponse` has exactly these fields:

| Field | Type | Contract |
|---|---|---|
| `resourceId` | string/null | Local Server ID; null only for an all-or-nothing bulk command. |
| `outcome` | string | `SUCCESS` for completed local work; `ACCEPTED` when background MCP I/O remains. |
| `status` | string/null | `PENDING`, `READY`, `DEGRADED`, or `DISABLED`; null only after delete. |
| `errorCode` | string/null | One stable, allowlisted machine code. |
| `errorMessage` | string/null | One allowlisted Chinese user-facing message. |

There is no `PARTIAL_SUCCESS`, failure-domain field, warning hierarchy, operation
ID, or separate connection/catalog error surface. A live connection whose catalog
failed is simply `DEGRADED` with `MCP_CATALOG_SYNC_FAILED`.

`ServerConfigResponse` exposes:

- `serverId`, `displayName`, `description`, `url`, `remoteServerName`;
- `enabled`, `autoConnect`, `version`;
- `status`, `errorCode`, `errorMessage`;
- `lastAttemptAt`, `nextReconcileAt`, `lastConnectedAt`, `createdAt`, `updatedAt`.

It never exposes a Bearer Token, ciphertext, desired/observed hash, stack trace,
raw exception message, or internal URL resolution details. The pre-release cutover
removes `initError`, `health`, revision/generation fields, operation DTOs, and all
deprecated aliases immediately.

### R4. Command and disconnect semantics

- Create/update/token rotation/enable/reconnect/refresh commit DB intent and return
  without waiting for MCP network I/O.
- Disable/delete/display-only updates are local operations and return `SUCCESS`.
- A disconnected HTTP client does not cancel or roll back committed desired state.
  There is no request-owned remote task to orphan.
- Create requires `Idempotency-Key` (1-128 printable ASCII characters). Retrying the
  same key and semantically identical payload returns the same Server. Reusing the
  key for a different payload returns a conflict business code.
- The idempotency key is a column on the Server row, not a separate request record. It
  lives exactly as long as that Server and is deleted with it; no TTL or cleanup job
  exists, and idempotency storage cannot grow faster than configured Servers.
- Updates, token rotation, enable, disable, and delete require the current `version`.
  A stale version is rejected; no last-writer-wins behavior is permitted.
- Reconnect clears the trusted connection observation. Refresh clears only the
  catalog-synced observation. Both are idempotent commands.

### R5. Connection and catalog reconciliation

- A create command schedules one connection attempt even when `autoConnect=false`.
  The DB row records that due attempt, so process failure or executor rejection cannot
  lose it. After it finishes permanently, ongoing automatic recovery only applies to
  `enabled=true AND autoConnect=true`; manual reconnect/refresh schedules another
  durable due attempt for any enabled row.
- A successful connection automatically runs a complete `tools/list` and reconciles
  the catalog.
- Catalog reconciliation is set based: validate the complete remote response first,
  then upsert seen tools and mark missing tools absent in one DB transaction.
- New tools default to disabled. Existing Admin policy is preserved. A failed or
  incomplete fetch must not expose a partial catalog.
- If connection succeeds and catalog sync fails, keep the live client, retain the
  previous tool policy rows, report `DEGRADED`, and retry catalog without reconnecting.
  URL/token/enable/reconnect mutations mark old tools absent before the new client is
  used; a manual refresh failure keeps the current connection's prior catalog visible.
- Retry uses bounded exponential backoff with jitter and a circuit breaker. Retryable
  transport/timeout/5xx failures are distinguished from permanent validation,
  credential, policy, and incompatible-protocol failures.
- Executor saturation is not a Server failure. The scheduler stops submitting the
  current batch and applies one short process-wide jittered scan delay; the DB due row
  remains unchanged and is retried later without a per-Server rejection counter.
- A temporary PostgreSQL outage does not withdraw already published clients or rewrite
  their state. Admin and DB-backed tool discovery/authorization fail safely; resource/
  prompt calls already supported by a live instance may continue. Scheduler/reconciler
  DB failures release `inFlight` and use bounded process-local scan backoff.
- One application instance supports up to 100 configured connections and 200 tools
  per connection as the validated baseline. Capacity above that is unqualified.

### R6. Derived status

All API and health views use one projection:

| Predicate | Status |
|---|---|
| `enabled=false` | `DISABLED` |
| enabled and a current safe DB error exists, or the live client's invocation circuit is not CLOSED | `DEGRADED` |
| enabled, a live client with CLOSED circuit exists, observed hash equals desired hash, catalog is synced, and no error exists | `READY` |
| any other enabled state | `PENDING` |

Desired mutations clear prior errors and make the row `PENDING` until the new
observation succeeds. Because `READY` is derived, the DB cannot contain an
independently drifting READY flag.

### R7. Required concurrency outcomes

- Two administrators updating one Server with the same version: exactly one commits;
  the other receives optimistic-lock conflict.
- An Admin desired-state change racing with background connection: a result built
  from the old desired hash is closed and cannot remain published.
- Delete/disable racing with recovery: once the Admin mutation commits, no new call
  may start through that Server and stale recovery cannot republish it.
- Delete/replacement racing with a HALF_OPEN invocation: the already-started call may
  finish, but its result must affect only its retired client, never a replacement.
- Duplicate scheduler ticks for one Server produce at most one in-flight reconcile in
  this process. This requirement does not justify per-ID lock lifecycle machinery.

### R8. Security

- Every Admin endpoint remains permission protected and validates all input.
- Endpoint validation rejects unsupported schemes, credentials in URLs, loopback,
  unspecified/multicast/link-local addresses, RFC1918 ranges, `169.254.0.0/16`, IPv6
  unique-local/link-local ranges, and redirect following. Each new connection attempt
  and each SDK HTTP request re-resolves and revalidates all DNS answers.
- MCP SDK 2.0.0 must receive a JDK `HttpClient.Builder` configured with
  `Redirect.NEVER` and `ProxySelector.NO_PROXY`. Because JDK `HttpClient` cannot pin a
  validated DNS result through its public API, production also requires network-layer
  outbound enforcement that denies private/link-local/metadata destinations. A custom
  MCP transport is not part of this task.
- Bearer Tokens are optional, 1-4096 visible ASCII characters when present, stored
  in `v2:<keyId>:<cipher>:<iv>` AES-GCM envelopes, redacted from logs/audit/API, and
  sent only in the Authorization header.
- Crypto configuration supports exactly one current and one optional previous Master
  Key. New writes use current; reads accept current/previous; startup locally rewraps
  previous-key MCP ciphertext to current without changing desired/observed state or
  reconnecting. Previous key removal is allowed only after the previous-key count is
  zero and other shared `SecretCipher` consumers have passed their migration/canary
  gate. V19 deletes existing MCP rows, so it never decrypts/re-encrypts old MCP data.
- Missing current key rejects token writes. Missing/lost required key degrades only
  affected encrypted connections with `MCP_CREDENTIAL_KEY_UNAVAILABLE`; tokenless and
  already live clients are not mass-withdrawn. Irrecoverable rows require Admin token
  replacement. A leaked encryption key requires key rotation and, when ciphertext may
  also be exposed, rotation of the remote Bearer Tokens themselves.
- Token rotation is audited without old/new token values. Delete physically removes
  the row and ciphertext; it does not rely on secure page-level erasure from PostgreSQL.
- Safe errors are selected from an allowlist. They contain no host, URL path/query,
  token, resolved IP, SQL, internal class name, stack trace, or raw cause.

### R9. Operability and audit

- `/actuator/health/mcp` exposes aggregate status and per-Server
  `serverId/status/errorCode`; details are visible only under the existing authorized
  health-details policy.
- Metrics cover reconcile attempts/duration/outcome, retry count, circuit state,
  configured/live/ready/degraded counts, and catalog tool count. Tags are bounded
  enums and never include Server ID, URL, token, or exception text.
- Metrics also expose aggregate mutation-guard wait/hold duration, oldest PENDING age,
  bounded stale-PENDING buckets, scheduler saturation count, DB-unavailable scan count,
  and current/previous credential-key row counts. No per-Server metric tag is added.
- Audit records actor, action, Server ID, outcome, and timestamp. URL changes record
  only `urlChanged=true`; token changes only `tokenChanged=true`.
- Structured logs may contain Server ID and stable error code, never endpoint or
  credential material.

### R10. Collection APIs and bulk scope

- Server and tool lists are paginated with default size 20 and maximum size 100.
- Bulk disable and bulk refresh accept 1-100 unique Server IDs and are all-or-nothing
  at validation/DB commit. Bulk disable returns `SUCCESS/DISABLED`; bulk refresh
  returns `ACCEPTED/PENDING`. No parent/child operation resources or per-item workflow
  records are created.
- Webhook, SSE, and other push delivery are out of scope; Admin clients poll Server
  resources with bounded backoff.

## 5. Performance and Test Evidence

- Admin mutation acknowledgement: p95 below 500 ms and p99 below 1 s with 100
  configured Servers. This measures request validation, DB commit, and JSON response;
  it excludes background MCP I/O by design.
- One hundred concurrent single-Server mutations complete at least 20 operations/s
  with p99 guard wait below 5 s; bulk-disable of 100 Servers completes in one guard/
  transaction with p95 below 1 s. Failure does not authorize striped locks in this task:
  optimize SQL/transaction scope or cap Admin concurrency first.
- Scheduler: a tick scans at most 100 eligible rows and submits work in under 1 s.
- Catalog: complete reconciliation of 200 tools commits in under 2 s against local
  PostgreSQL, excluding remote `tools/list` latency. Concurrent visible-tool reads stay
  below 50 ms p95 and Server-row lock wait stays below 250 ms p95. Evidence records WAL
  bytes for the fixture without imposing a hardware-specific hard limit.
- Evidence uses Testcontainers PostgreSQL, 20 warmup runs, 100 measured runs, and
  reports median/p95/p99 plus hardware/JVM/container versions. Mock MCP Servers use
  deterministic latency and failure scripts.

## 6. Acceptance Criteria

1. Empty DB startup produces an empty Registry without reading YAML connections.
2. V19 removes existing MCP Server/tool rows and new Admin creation assigns
   `mcp_<row-id>` consistently across DB, callback, policy, and health paths.
3. API serialization tests lock the exact mutation/status/error shape and prove old
   revision/generation/operation/error-domain fields are absent.
4. Create idempotency tests prove same key/same payload yields one row and same ID;
   same key/different payload conflicts, and deleting the Server deletes its key.
5. Request-disconnect tests prove DB intent remains committed and no request thread
   performs MCP I/O.
6. Race tests cover update-vs-reconcile, delete-vs-reconcile, reconnect-vs-recovery,
   duplicate scheduler ticks, queue saturation, DB outage, and
   delete/replacement-vs-HALF_OPEN invocation.
7. State projection tests prove all four statuses and demonstrate that READY cannot
   be persisted or drift independently.
8. Catalog tests cover 0/1/200 tools, remote rename, missing tools, malformed full
   response, transaction rollback, policy preservation, and catalog-only retry.
9. Direct callback discovery and authorization read committed DB catalog/policy and
   require no cache invalidation ordering.
10. Security tests cover URL edge cases, all DNS answers, per-request revalidation,
    redirect rejection, mandatory egress assumption, token limits, v2 current/previous
    key rotation, missing/lost key, tamper, masking, audit, and allowlisted errors.
11. Retry/circuit tests prove retry classification, timeout, backoff/jitter bounds,
    HALF_OPEN isolation, and shutdown behavior.
12. Pagination, bulk limits, stuck-PENDING visibility, DB-time due semantics, health,
    metrics cardinality, guard throughput, catalog/read contention, and performance
    methodology are repeatably verified in CI.

## 7. Out of Scope

- Multi-instance coordination, leader election, distributed locks, or cross-node
  exactly-once execution.
- Durable operation/job tables, leases, cancellation APIs, operation history, or
  generation fencing.
- YAML import/export, zero-operation example bootstrap, or remote-name identity.
- Ordered cache invalidation protocols or policy/callback caches without measured need.
- Backward-compatible MCP Admin DTO aliases during this pre-release cutover.
- Webhook/SSE notifications and capacity claims above the stated baseline.
- A bespoke OkHttp/Reactor MCP Streamable HTTP transport, application-only DNS pinning,
  deployment communication, maintenance-window coordination, or rollback rehearsal.
