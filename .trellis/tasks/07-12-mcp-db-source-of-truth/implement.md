# Implementation Plan: Minimal DB-Driven MCP Connections

## 1. Delivery Strategy

- One feature branch and one PR against `agentic-rag-dev`.
- Seven reviewable Lore commits; migration and compatible code land in the same PR
  because V19 is a deliberate pre-release destructive cutover.
- Use `TransactionTemplate`, MyBatis-Plus, the existing explicit executor pattern,
  and existing crypto/exception/audit infrastructure. Add no dependency.
- Do not implement a durable operation subsystem, generation fencing, per-ID locks,
  persisted READY state, or MCP tool caches.
- Estimated engineering time: 9-12 working days including security review and race/
  integration tests. The old operation/coordinator plan was 11-16 days.

## 2. Dependency Map

```text
A contracts + transport spike
      |
B V19 + entities/mappers
      |
C identity + Admin fast mutations
      |
D reconciler + startup/scheduler
      |
E catalog + direct DB discovery + delete caches
      |
F security + observability + capacity
      |
G full verification/spec/commit review
```

B requires the contract tests from A. C requires B's schema. D requires C's mutation
semantics. E requires D's client/catalog lifecycle. F implements the transport/key
contracts proven in A/C after D/E are stable. No phases that modify the same MCP files
run in parallel.

## 3. Review Decisions Locked Before Coding

| Decision | Contract |
|---|---|
| State tokens | `@Version version`, `desired_state_hash`, `observed_state_hash`, `catalog_synced`; no revision/generation. |
| Status | Read-time projection only; no persisted overall status. |
| Async durability | DB desired row is the queue; no operation table. |
| Admin concurrency | Optimistic locking plus one short monitor owned by existing `McpServerRuntime`. |
| Worker de-duplication | Process-local `ConcurrentHashMap.newKeySet()`; rejection removes immediately and delays the whole scan once. |
| Stale recovery | Re-read + conditional `WHERE desired_state_hash=? AND enabled=true` + exact-instance Registry remove. |
| API errors | One `errorCode` and one allowlisted Chinese `errorMessage`. |
| Tool consistency | Complete set transaction; direct indexed DB reads; zero invalidation protocol. |
| Invocation circuit | Owned by each `McpServerImpl`, not a shared key registry. |
| Durable reconcile/recovery circuit | `next_reconcile_at + consecutive_failures`; Admin writes due-now, retry writes future, success/permanent failure clears. |
| Persisted time | SQL `CURRENT_TIMESTAMP` only; application wall time never becomes a DB due timestamp. |
| Master Key | Bounded current + one previous key, v2 key-ID envelope, startup rewrap; no general keyring/job system. |
| SSRF | SDK-injected redirect/proxy controls + per-request validation + mandatory production egress enforcement. |

## 4. Phase A: Contracts and Transport Spike (0.5-1 day)

### Files

- `.trellis/spec/backend/mcp-integration.md`
- `src/test/java/com/smart/rag/mcp/runtime/McpClientTransportSecurityTest.java` (new)
- `src/main/java/com/smart/rag/mcp/runtime/McpClientBuilder.java`

### Work

- [ ] Update spec terminology and exact signatures:
  `serverId(long rowId)` and `prefixedToolName(String localServerId,
  String rawToolName)`; remove remote-derived `canonicalServerId` contract.
- [ ] Record exact API/projector/naming/architecture cases in the spec; create each test
  only in the phase that implements the corresponding behavior.
- [ ] Prove MCP SDK 2.0.0 accepts a supplied JDK `HttpClient.Builder`, does not follow a
  local 30x redirect under `Redirect.NEVER`, and invokes request customization for each
  Streamable HTTP request. Record that JDK public API cannot pin DNS.
- [ ] Freeze Plan B: production supplies outbound enforcement denying private,
  link-local, and metadata addresses. If unavailable, stop before Phase B and split a
  custom-transport task rather than implementing it here.
- [ ] Use TDD red locally only. Do not commit/push a failing test, `@Disabled` test, tag
  exclusion, or environment-gated contract; each owning implementation commit must be
  green before CI sees it.

### Gate / rollback

Review exact API JSON, hash inputs, status truth table, deletion list, executable
redirect evidence, and egress prerequisite. The full suite remains green. Do not start
B while any old token/operation contract remains normative or production egress is
unavailable.

## 5. Phase B: V19 and Persistence (1-1.5 days)

### Files

- `src/main/resources/db/migration/V19__reset_mcp_connections_for_db_runtime.sql` (new)
- `src/main/java/com/smart/rag/mcp/admin/entity/McpServerConfig.java`
- `src/main/java/com/smart/rag/mcp/admin/entity/McpToolConfig.java`
- `src/main/java/com/smart/rag/mcp/admin/mapper/McpServerConfigMapper.java`
- `src/main/java/com/smart/rag/mcp/admin/mapper/McpToolConfigMapper.java`
- `src/main/resources/mapper/McpServerConfigMapper.xml`
- `src/main/resources/mapper/McpToolConfigMapper.xml`
- `src/test/java/com/smart/rag/mcp/admin/McpV19PostgresMigrationIT.java` (new)
- `src/test/java/com/smart/rag/mcp/admin/McpV19MigrationContractTest.java` (new)
- `pom.xml`

### Work

- [ ] Truncate/delete MCP tool rows before Server rows; retain security/audit data.
- [ ] Add only the Server/tool columns, checks, unique constraints, and indexes in
  design section 3. Do not create `mcp_admin_operation` or status/revision columns.
- [ ] Replace `initError` entity mapping with desired/observed facts, retry fields,
  remote name, and idempotency key. Add tool presence/schema/last-seen mapping.
- [ ] Add mapper methods for paged lists, idempotency lookup, optimistic desired
  mutation, conditional observed success/failure, recovery scan, visible tool query,
  and complete set reconciliation.
- [ ] Ensure observed writes never bump MyBatis `version`; desired writes do.
- [ ] Configure Maven Failsafe for `*IT` and Testcontainers PostgreSQL. Do not rely on
  `mvn test` to run the migration IT.
- [ ] Test V17 -> V18 -> V19, V19 empty-table result, constraints, indexes, cascade/
  deletion, transaction rollback, and repeat migration validation.
- [ ] Prove V19 does not attempt credential decrypt/rewrap because MCP rows are deleted.

### Gate / rollback

`mvn verify -DskipUnitTests -Dit.test=McpV19PostgresMigrationIT` passes. Rollback is
restore the MCP table backup and old binary; never edit an applied migration.

## 6. Phase C: Identity, API, and Fast Admin Mutations (2-2.5 days)

### Files

- `src/main/java/com/smart/rag/mcp/mcpclient/McpToolUtils.java`
- `src/main/java/com/smart/rag/mcp/admin/dto/McpMutationResponse.java` (new)
- `src/main/java/com/smart/rag/mcp/admin/dto/McpMutationOutcome.java` (new)
- `src/main/java/com/smart/rag/mcp/admin/dto/McpConnectionStatus.java` (new)
- `src/main/java/com/smart/rag/mcp/admin/dto/ServerConfigResponse.java`
- `src/main/java/com/smart/rag/mcp/admin/dto/UpdateServerRequest.java`
- `src/main/java/com/smart/rag/mcp/admin/dto/UpdateBearerTokenRequest.java`
- `src/main/java/com/smart/rag/mcp/admin/service/CreateServerRequest.java`
- `src/main/java/com/smart/rag/mcp/admin/controller/McpAdminController.java`
- `src/main/java/com/smart/rag/mcp/admin/service/McpServerAdminService.java`
- `src/main/java/com/smart/rag/mcp/runtime/McpDesiredStateHasher.java` (new)
- `src/main/java/com/smart/rag/mcp/runtime/McpConnectionStateProjector.java` (new)
- `src/main/java/com/smart/rag/mcp/runtime/McpBearerTokenCodec.java`
- `src/main/java/com/smart/rag/infrastructure/security/SecurityCryptoProperties.java`
- `src/main/java/com/smart/rag/infrastructure/security/SecretCipher.java`
- `src/main/java/com/smart/rag/mcp/admin/service/McpServerRuntime.java`
- `src/main/java/com/smart/rag/mcp/runtime/McpServerRegistryAdmin.java`
- `src/main/java/com/smart/rag/mcp/runtime/McpServerRegistryImpl.java`
- `src/test/java/com/smart/rag/mcp/admin/controller/McpAdminControllerTest.java`
- `src/test/java/com/smart/rag/mcp/runtime/McpConnectionStateProjectorTest.java` (new)
- `src/test/java/com/smart/rag/mcp/mcpclient/McpToolUtilsTest.java`

### Work

- [ ] Replace remote-derived naming with `mcp_<row-id>` and local-ID tool prefixing.
- [ ] Implement deterministic length-prefixed SHA-256 hashing over canonical URL,
  encrypted token envelope/null marker, and enabled flag.
- [ ] Implement bounded current/previous Master Key configuration and
  `v2:<keyId>:<cipher>:<iv>` MCP envelopes. Preserve legacy consumer behavior; reject
  duplicate/incomplete key configuration, reject MCP v1/unknown key IDs after V19,
  and never try more than two keys for legacy non-MCP ciphertext.
- [ ] Implement the single projector over DB facts plus Registry live/circuit
  observation and use it for Server GET/list/health DTO mapping.
- [ ] Lock API serialization, uppercase enums, status projection, local naming, remote
  rename, and absence of operation/revision/domain/legacy fields in green tests.
- [ ] Change Admin write endpoints to return exact `McpMutationResponse`. Keep project
  HTTP 200/business-code behavior.
- [ ] Require `Idempotency-Key` on create; insert row, assign local ID/hash, and verify
  same-key retries by normalized fields plus constant-time decrypted token comparison.
- [ ] Keep the key only on the Server row; test row delete removes it and no cleanup
  task/table exists.
- [ ] Require expected version for desired mutations, including delete/enable/disable;
  bulk disable carries `(serverId, version)` pairs.
- [ ] Execute 100-Server bulk disable as one validated transaction and one guard
  acquisition, not 100 recursive single-Server service calls.
- [ ] Make existing `McpServerRuntime` own one `withMutationGuard` monitor. Under it,
  withdraw/retire the old Registry instance, commit desired state, then close on
  success or restore/reactivate the exact instance on rollback. Never connect/list
  tools inside the guard or HTTP request.
- [ ] Add Registry `withdraw`, `restore`, `removeIfSame`, and live-client lookup.
- [ ] Add instance-local active checks to MCP capability entries so callbacks captured
  before withdrawal cannot start a new remote call.
- [ ] Desired connection mutations mark existing tools absent in the same transaction;
  manual refresh does not pre-hide the current catalog.
- [ ] Reconnect clears observed + catalog facts and marks tools absent; refresh clears
  catalog only; display changes preserve runtime facts.
- [ ] `autoConnect=false -> true` schedules due work when not READY without changing
  desired hash; `true -> false` does not tear down a healthy live client.
- [ ] Remove synthetic `unreachable-*` identities and placeholder Registry Servers.
- [ ] Test create retry/conflict, optimistic collision, field-specific mutation effects,
  exact-instance remove, no network call from controller/service, and response latency.
- [ ] Benchmark 100 concurrent mutations and one 100-Server bulk disable while recording
  guard wait/hold. Missing the target permits SQL/lock-scope optimization or bounded
  Admin admission only, not per-ID/striped locks.

### Gate / rollback

All API/projector/naming tests owned by Phase C are green. A code review must confirm
`McpServerRuntime.withMutationGuard`
contains no remote call, sleep, executor wait, or broad method synchronization.
Rollback is commit-local because V19 is not deployed separately.

## 7. Phase D: Reconciler, Retry, and Instance-Owned Circuit (2-2.5 days)

### Files

- `src/main/java/com/smart/rag/mcp/admin/service/McpBootstrapRunner.java` (delete/replace)
- `src/main/java/com/smart/rag/mcp/admin/service/McpStartupRecoveryRunner.java` (new)
- `src/main/java/com/smart/rag/mcp/admin/service/McpServerRuntime.java`
- `src/main/java/com/smart/rag/mcp/runtime/McpClientBuilder.java`
- `src/main/java/com/smart/rag/mcp/runtime/McpClientFactory.java`
- `src/main/java/com/smart/rag/mcp/runtime/McpConnectionReconciler.java` (new)
- `src/main/java/com/smart/rag/mcp/runtime/McpConnectionRecoveryScheduler.java` (new)
- `src/main/java/com/smart/rag/mcp/runtime/McpErrors.java`
- `src/main/java/com/smart/rag/mcp/runtime/McpResilienceProperties.java`
- `src/main/java/com/smart/rag/mcp/runtime/McpServerImpl.java`
- `src/main/java/com/smart/rag/mcp/runtime/McpRemoteCallExecutor.java`
- `src/main/java/com/smart/rag/mcp/runtime/McpCircuitBreakerRegistry.java` (delete)
- `src/main/resources/application.yml`

### Work

- [ ] Build an explicit 8-worker/200-queue `ThreadPoolExecutor`; use `inFlight.add`
  before submit, remove on rejection or worker `finally`, stop the rejected batch, and
  set one jittered process-local `saturatedUntilNanos`. Do not mutate Server retry
  counters or immediately rescan a full due batch.
- [ ] Implement one captured-hash attempt. Connect outside transaction/guard; under
  the guard re-read, commit a conditional observed transaction, then publish the exact
  Server instance. Publication failure exact-removes and conditionally clears observed;
  no Registry-before-DB visibility window is allowed.
- [ ] On connection success, schedule catalog sync. On stale/missing/disabled row,
  close the unowned client without publication.
- [ ] Persist classified safe failure + failure count + next reconcile time only under captured
  hash predicate. Desired changes supersede failures by clearing them.
- [ ] Implement capped exponential backoff, jitter, permanent-failure stop, threshold
  OPEN delay, and one due HALF_OPEN attempt using DB retry facts.
- [ ] Generate/compare every persisted due timestamp with SQL `CURRENT_TIMESTAMP`;
  pass only durations from Java. Test app clock skew of plus/minus 10 minutes.
- [ ] Scheduler selects at most 100 enabled due rows (including initial/manual commands)
  plus auto-connect rows eligible for recovery; application predicates choose connect
  vs catalog-only work. Success/permanent failure clears the due time.
- [ ] Startup makes previously healthy enabled auto-connect rows due, preserves future
  retry due times, skips permanent failures, and submits due enabled rows; empty DB is
  a successful no-op.
- [ ] Move `CircuitBreakerStateMachine` ownership into each `McpServerImpl`/
  `McpRemoteCallExecutor`; delete the shared MCP registry. Preserve shared generic
  fallback implementation for LLM.
- [ ] Make a fresh client start CLOSED and test/document the accepted cost that an old
  OPEN circuit's remote may fail up to the configured threshold before reopening.
- [ ] Rewrap previous-key MCP ciphertext in the startup pass with conditional local
  updates that preserve version, desired/observed hashes, catalog, due time, and live
  client; keep the previous key configured until its row count is zero.
- [ ] On DB read/write failure, keep existing Registry state, close only unowned newly
  built clients, release `inFlight`, and apply one process-local 1-30 s DB scan backoff.
  Admin fails normally; DB-authorized tool discovery/invocation fails closed.
- [ ] Add graceful scheduler/executor/client shutdown.
- [ ] Test update/delete/disable/reconnect vs connection publication, duplicate tick,
  repeated queue saturation, PostgreSQL outage/recovery, crash-shaped restart, retry
  classification/timing, and retired HALF_OPEN call vs replacement.

### Gate / rollback

No stale client survives any race test; thread/executor leak checks pass. Hotfix
rollback for scheduler issues is `mcp.recovery.enabled=false`; Admin desired CRUD and
manual status remain available while recovery is disabled.

## 8. Phase E: Atomic Catalog and Cache Deletion (1-1.5 days)

### Files

- `src/main/java/com/smart/rag/mcp/admin/service/McpToolAdminService.java`
- `src/main/java/com/smart/rag/mcp/admin/service/McpToolConfigAccessor.java`
- `src/main/java/com/smart/rag/mcp/config/DatabaseToolFilter.java` (delete)
- `src/main/java/com/smart/rag/mcp/runtime/McpConnectionReconciler.java`
- `src/main/java/com/smart/rag/mcp/runtime/McpServerImpl.java`
- `src/main/java/com/smart/rag/mcp/mcpclient/SyncMcpToolCallbackProvider.java` (delete)
- `src/main/java/com/smart/rag/mcp/mcpclient/McpServerToolCallbacksAdapter.java` (delete)
- `src/main/java/com/smart/rag/mcp/mcpclient/DefaultMcpServerToolCallbacksAdapter.java` (delete)
- `src/main/java/com/smart/rag/mcp/mcpclient/McpConnectionInfo.java` (delete)
- `src/main/java/com/smart/rag/mcp/mcpclient/McpToolFilter.java` (delete)
- `src/main/java/com/smart/rag/mcp/mcpclient/McpToolNamePrefixGenerator.java` (delete)
- `src/main/java/com/smart/rag/mcp/mcpclient/DefaultMcpToolNamePrefixGenerator.java` (delete)
- `src/main/java/com/smart/rag/mcp/mcpclient/SyncMcpToolCallback.java` (delete)
- `src/main/java/com/smart/rag/mcp/mcpclient/ToolContextToMcpMetaConverter.java` (delete)
- `src/main/java/com/smart/rag/mcp/config/McpClientConfiguration.java` (delete)
- `src/main/java/com/smart/rag/mcp/config/McpClientTransportConfiguration.java`
- `src/test/java/com/smart/rag/mcp/mcpclient/SyncMcpToolCallbackProviderTest.java` (delete)
- `src/test/java/com/smart/rag/mcp/McpDependencyRulesTest.java`
- affected Admin/accessor/filter/adapter/bean-graph tests

### Work

- [ ] Fetch and validate the complete remote catalog outside a DB transaction; reject
  schema over 64 KiB/tool or 4 MiB/catalog before any write.
- [ ] Transactionally upsert 0/1/200 seen tools, preserve policy, default new tools
  disabled, mark absent tools, store JSON-object schema, and set `catalog_synced` only
  under current desired/observed predicates.
- [ ] Use default `READ COMMITTED`, lock only the owning Server row, then execute one
  multi-values upsert and one set-based absent update. Do not batch or lock the table.
- [ ] On failure, leave prior complete catalog visible, persist one safe error, and
  schedule catalog-only retry when eligible.
- [ ] Remove Caffeine from tool Admin and config accessor; mapper reads are direct and
  indexed. Remove all `invalidate*` APIs/calls.
- [ ] Delete the aggregate callback provider, bridge adapters, provider-only filter,
  connection-info/prefix strategy, legacy direct callback, and their configuration. Make
  `McpServerImpl.visibleTo` query committed enabled/present rows for Server + intent;
  keep request-scoped `McpToolCallbackAdapter` as the sole callback construction path.
- [ ] Remove provider beans/configuration and stale bean graph/architecture docs.
- [ ] Add ArchUnit assertions after deletion: Admin controller/service cannot depend on
  MCP SDK, runtime cannot depend on YAML connection maps, removed operation/provider
  types are absent, and Spring AI tool types remain in adapter/client boundaries.
- [ ] Test complete rollback, remote rename, missing tools, policy preservation,
  direct visibility after Admin commit, no remote list during callback discovery, and
  direct-call authorization recheck. Measure 200-tool commit/read/Server-lock p95 and
  WAL delta under concurrent visible-tool reads.

### Gate / rollback

Repository search finds no MCP Caffeine cache, callback provider, invalidation method,
or remote discovery call outside `McpConnectionReconciler`. Direct discovery at the
20k-row fixture meets the agreed request budget or the phase stops for evidence-based
query/index optimization, not a speculative cache.

## 9. Phase F: Security, Audit, Health, and Capacity (1.5-2 days)

### Files

- `src/main/java/com/smart/rag/mcp/runtime/McpEndpointSafetyGuard.java` (new)
- `src/main/java/com/smart/rag/mcp/runtime/McpBearerTokenValidator.java` (new)
- `src/main/java/com/smart/rag/infrastructure/security/HostSafetyValidator.java`
- `src/main/java/com/smart/rag/mcp/runtime/McpClientBuilder.java`
- `src/main/java/com/smart/rag/mcp/health/McpHealthIndicator.java`
- `src/main/java/com/smart/rag/infrastructure/audit/AdminAuditAspect.java`
- `src/main/java/com/smart/rag/mcp/config/McpClientTransportConfiguration.java`
- `src/main/resources/application.yml`
- focused security/health/metrics/capacity tests

### Work

- [ ] Enforce schemes, URL credentials, private/metadata/link-local/multicast ranges,
  every DNS answer, revalidation per connect/request, `Redirect.NEVER`, and
  `ProxySelector.NO_PROXY` through SDK-supported builder/customizer hooks.
- [ ] Verify TLS hostname behavior remains default and record mandatory outbound
  enforcement. Do not claim application-level DNS pinning and do not add a custom MCP
  transport in this phase.
- [ ] Enforce optional 1-4096 visible ASCII Bearer Tokens before encryption/header use.
- [ ] Remove YAML connection maps/example values/comments; retain transport timeout,
  recovery, and circuit tuning only.
- [ ] Audit command actor/action/Server/outcome and boolean URL/token changes; prove
  secrets/endpoints/raw causes never cross audit/log/API/metrics.
- [ ] Use the common projector in Admin health and Actuator health. Add bounded metrics
  without per-Server tags: guard wait/hold, saturation, DB scan failure,
  pending-oldest/fixed stale buckets, and credential current/previous counts.
- [ ] Expose `nextReconcileAt` on authorized Server response and calculate PENDING age
  from DB time without adding a persisted status timestamp.
- [ ] Run 100-Server/200-tool ACK, 100-way Admin mutation, bulk disable, scan, catalog/
  concurrent reads, direct discovery, queue saturation, DB outage, and shutdown
  evidence using the PRD methodology.

### Security review gate

An explicit security review confirms Phase A redirect evidence, mandatory egress
enforcement, bounded key rotation, idempotent token comparison, redaction, audit, and
safe-error allowlist. Missing egress enforcement is a release blocker; it does not
trigger an in-scope custom transport.

## 10. Phase G: Full Verification and Handoff (0.5 day)

### Commands

```bash
# Unit/contract slices while iterating
mvn test -Dtest='McpClientTransportSecurityTest,McpAdminControllerTest,McpConnectionStateProjectorTest,McpToolUtilsTest,McpDependencyRulesTest'
mvn test -Dtest='McpBearerTokenCodecTest,SecretCipherTest,McpServerAdminServiceTest,McpServerRegistryImplTest,McpDesiredStateHasherTest'
mvn test -Dtest='McpConnectionReconcilerTest,McpConnectionRecoverySchedulerTest,McpRemoteCallExecutorTest,McpDatabaseOutageTest'
mvn test -Dtest='McpConnectionReconcilerCatalogTest,McpToolConfigAccessorTest,McpToolCallbackAdapterTest'

# PostgreSQL integration tests are Failsafe tests
mvn verify -Dit.test='McpV19PostgresMigrationIT,McpCatalogPostgresIT,McpAdminGuardCapacityIT,McpRecoverySchedulerCapacityIT'

# Full project gate
mvn clean verify
```

Also run the repository's configured format/static/coverage checks, GitNexus
`detect_changes` against `agentic-rag-dev`, and a security diff review. Record command,
result, duration, and any environment-dependent exclusions.

### Final searches

```bash
rg -n 'runtime_revision|applied_runtime_revision|runtime_generation|PARTIAL_SUCCESS|failureDomain|McpAdminOperation|McpOperationDispatcher|McpConnectionOperationCoordinator' src .trellis/spec
rg -n 'SyncMcpToolCallbackProvider|invalidateCache|toolListCache|Cache<String, Optional<McpToolConfig>>' src/main src/test
rg -n 'streamable-http:[[:space:]]*$|connections:' src/main/resources/application*.yml
```

Expected result: no obsolete lifecycle/operation/cache symbols in MCP source/spec;
only migration comments or explicit negative tests may mention them.

## 11. Commit Sequence

Each commit follows Conventional Commit first line plus Lore trailers.

1. `fix(mcp): lock transport safety before DB cutover`
2. `feat(mcp): reset local MCP state for stable DB identity`
3. `feat(mcp): make Admin commands commit desired state quickly`
4. `feat(mcp): reconcile DB connections without generation fencing`
5. `refactor(mcp): make committed catalog reads cache free`
6. `fix(mcp): enforce endpoint and credential safety at connect time`
7. `test(mcp): verify recovery races and operational limits`

Every commit records `Constraint`, meaningful `Rejected`, `Confidence`, `Scope-risk`,
`Directive`, `Tested`, and honest `Not-tested` trailers. Do not commit before
GitNexus `detect_changes` confirms the expected symbols/flows.

## 12. Go / No-Go Gates

| Gate | Go | No-go / response |
|---|---|---|
| Start B | API/status/hash reviewed; redirect spike green; production egress enforcement confirmed. | Operation/generation ambiguity or no egress enforcement. |
| Start C | V19 Testcontainers IT green via Failsafe. | Migration not atomic or mapper facts unclear. |
| Start D | Admin request does no MCP I/O; optimistic/idempotency tests green. | Request can block on remote work. |
| Start E | Stale publish/delete/HALF_OPEN races green. | Old client can become visible after committed mutation. |
| Start F | Atomic catalog and cache deletion tests green. | Partial catalog or invalidation dependency remains. |
| Merge | Full verify, security review, capacity evidence, all CI green, GitNexus scope green. | Missing egress/key-rotation evidence, known race, failing/disabled test, or unexplained scope. |

## 13. Remaining Risks

- This is explicitly single-instance. A second process requires a new design for
  ownership/coordination; do not silently reuse the process-local guard/in-flight set.
- `autoConnect=false` favors manual control: initial/manual commands are durable due
  rows, but permanent failure requires another Admin command rather than endless retry.
- Direct DB catalog reads are accepted for the 20k-row baseline; larger deployments
  require measured qualification before adding a cache.
- An instance replacement intentionally resets invocation circuit state; a persistently
  bad remote may consume the configured failure threshold before reopening.
- DB outage preserves live clients but DB-authorized MCP tool discovery/invocation
  fails closed until PostgreSQL recovers.
- Destructive V19 intentionally discards local MCP connections, tool policy, and stored
  credentials. This is acceptable only while the feature remains pre-release.
