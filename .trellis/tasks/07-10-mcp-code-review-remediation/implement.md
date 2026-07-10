# MCP Code Review Remediation Implementation Plan

> 执行要求：先加载 `trellis-before-dev` 与 `superpowers:test-driven-development`。每个行为变更严格执行 RED -> GREEN -> REFACTOR；失败测试必须先证明命中真实缺陷。生产代码改动前对目标 symbol 运行 GitNexus impact，全部改动后运行 detect_changes。

## Goal

按 `prd.md` 的 17 条验收标准和 `design.md` 的边界契约，修复 MCP 模块全部已确认问题，并用单元、装配、SQL 契约和全量回归测试逐项核验。

## Execution Rules

- 不修改 `.trellis/tasks/07-07-*` 的现有记录。
- 不新增依赖，不修改 V17，不改变 Admin REST 路径和 response envelope。
- 每个阶段先跑列出的测试并观察预期失败；若测试意外通过，先修正测试使其能证明缺陷。
- 每次只实现让当前红测变绿的最小改动，再做职责拆分。
- 计划中的 commit 是最终单个 Lore commit；中间以测试绿和 `git diff` 作为 rollback point，避免把未通过完整门禁的阶段提交到历史。

## Task 1: Lock Bean graph and split Admin ownership

**Files**

- Add: `src/test/java/com/smart/rag/mcp/config/McpBeanGraphTest.java`
- Add: `src/test/java/com/smart/rag/mcp/admin/service/McpAdminServiceStructureTest.java`
- Add: `src/main/java/com/smart/rag/mcp/admin/service/McpServerAdminService.java`
- Add: `src/main/java/com/smart/rag/mcp/admin/service/McpToolAdminService.java`
- Add: `src/main/java/com/smart/rag/mcp/admin/service/McpSecurityAdminService.java`
- Add: `src/main/java/com/smart/rag/mcp/admin/service/McpBootstrapRunner.java`
- Modify: `src/main/java/com/smart/rag/mcp/admin/service/McpAdminService.java`
- Modify: `src/main/java/com/smart/rag/mcp/config/DatabaseToolFilter.java`

### 1.1 RED: prove the constructor cycle and God Class

- Write `McpBeanGraphTest` with a minimal `AnnotationConfigApplicationContext` registering mocked mapper/runtime dependencies plus real Admin facade, provider, filter and accessor beans. Assert refresh succeeds without `BeanCurrentlyInCreationException`.
- Write `McpAdminServiceStructureTest` asserting facade constructor dependency count `<= 8`, facade does not implement `ApplicationRunner`, and production class source line count `<= 300`.
- Run:

```bash
mvn test -Dtest=McpBeanGraphTest,McpAdminServiceStructureTest
```

- Expected RED: context reports `McpAdminService -> SyncMcpToolCallbackProvider -> DatabaseToolFilter -> McpAdminService`; structure assertions fail.

### 1.2 GREEN: separate components without changing Controller API

- Change `DatabaseToolFilter` constructor to `(McpToolConfigAccessor, McpToolNamePrefixGenerator, @Value("${mcp.strict-tool-filter:true}"))` and read `Boolean.TRUE/FALSE/null` from accessor.
- Move server operations to `McpServerAdminService`, tool operations to `McpToolAdminService`, security JSON operations to `McpSecurityAdminService`, and `ApplicationRunner` logic to `McpBootstrapRunner`.
- Keep every existing public method on `McpAdminService`; implement one-line delegation so `McpAdminController` and tests remain source-compatible.
- Place `@AdminAudit` on called delegate methods so Spring AOP intercepts them; do not rely on facade self-invocation.
- Re-run the test command until GREEN, then run:

```bash
mvn test -Dtest=McpAdminControllerTest,McpDependencyRulesTest
```

Rollback point: only new services/facade/filter wiring; no behavior changes beyond breaking the cycle.

## Task 2: Canonical naming and real authorization

**Files**

- Add: `src/test/java/com/smart/rag/mcp/mcpclient/McpToolUtilsTest.java`
- Add: `src/test/java/com/smart/rag/mcp/config/DatabaseToolFilterTest.java`
- Add: `src/test/java/com/smart/rag/mcp/policy/McpAuthorizerTest.java`
- Modify: `src/main/java/com/smart/rag/mcp/mcpclient/McpToolUtils.java`
- Modify: `src/main/java/com/smart/rag/mcp/config/McpClientConfiguration.java`
- Modify: `src/main/java/com/smart/rag/mcp/admin/service/McpToolConfigAccessor.java`
- Modify: `src/main/java/com/smart/rag/mcp/policy/McpAuthorizer.java`
- Modify: `src/main/java/com/smart/rag/mcp/runtime/McpServerImpl.java`

### 2.1 RED: naming identity cases

- Parameterize tests for spaces/special characters, CJK, hyphens, repeated separators, empty-after-cleaning and names longer than 64.
- Assert `prefixedToolName(server, tool)` is deterministic, legal, `<= 64`, preserves server namespace and gives different hash suffixes for long names differing only at the tail.
- In `DatabaseToolFilterTest`, build connection info with serverInfo name and assert lookup key equals `McpToolUtils.prefixedToolName(derivedServerId, tool.name())`.
- Run:

```bash
mvn test -Dtest=McpToolUtilsTest,DatabaseToolFilterTest
```

- Expected RED: current Admin helper abbreviates prefix while runtime generator does not; long names are tail-truncated and collide.

### 2.2 GREEN: one algorithm

- Implement canonical normalization and SHA-256 suffix in `McpToolUtils`; replace `IllegalArgumentException` with `ClientException(BAD_REQUEST)` for invalid user-derived names and `ServiceException` for schema serialization.
- Make `McpClientConfiguration.mcpToolNamePrefixGenerator()` delegate to the canonical API.
- Remove any independent prefix concatenation from Admin refresh and `McpServerImpl`.

### 2.3 RED/GREEN: authorization matrix

- Enable/recreate tests covering anonymous, unknown, disabled, enabled+GENERAL default, matching non-default intent, mismatched intent and direct call after disable.
- Change accessor cache to `Cache<String, Optional<McpToolConfig>>`, expose `enabledState`/`get`, and add one invalidation path.
- Inject accessor into `McpAuthorizer`; implement `canSee` and `requireAuthorized` exactly as design §4.2.
- Verify a direct `McpTools.call` on disabled/unknown throws `ClientException(FORBIDDEN)` before mocked `McpSyncClient.callTool` is invoked.
- Run:

```bash
mvn test -Dtest=McpAuthorizerTest,McpServerImplTest,DatabaseToolFilterTest,McpToolUtilsTest
```

## Task 3: Validate Admin inputs and security config

**Files**

- Modify: `src/test/java/com/smart/rag/mcp/admin/controller/McpAdminControllerTest.java`
- Add: `src/test/java/com/smart/rag/mcp/admin/service/McpSecurityAdminServiceTest.java`
- Modify: `src/main/java/com/smart/rag/mcp/admin/controller/McpAdminController.java`
- Modify: all request records under `src/main/java/com/smart/rag/mcp/admin/dto/`
- Move/modify: `src/main/java/com/smart/rag/mcp/admin/service/CreateServerRequest.java` as an Admin request DTO while preserving imports used by Controller tests
- Modify: `src/main/java/com/smart/rag/mcp/admin/service/McpSecurityConfigAccessor.java`

### 3.1 RED: field validation never reaches service

- Extend MockMvc tests for blank/oversize URL, blank/oversize token, empty/oversize/non-positive ids, illegal intent/risk, oversized description and invalid caps.
- Verify every invalid body yields validation error and `verifyNoInteractions(service)` for the relevant call.
- Add `@Valid` to every body parameter; add `@NotBlank`, `@Size`, `@Positive`, `@Min`, `@Max`, `@Pattern` and element constraints as specified in design §4.7.
- Run:

```bash
mvn test -Dtest=McpAdminControllerTest
```

### 3.2 RED: service cross-field/regex validation

- Test malformed regex, more restrictive cap greater than default, zero/negative limits, null lists and valid normalized config.
- Implement one validator in `McpSecurityAdminService`: trim patterns, compile before persistence, enforce cross-field relation, serialize with Jackson, preserve cause on serialization failures.
- Change `McpSecurityConfigAccessor` so persisted invalid config does not silently create unsafe non-positive caps: log structured error and use positive defaults.
- Run:

```bash
mvn test -Dtest=McpSecurityAdminServiceTest,McpAdminControllerTest
```

## Task 4: Bearer Token envelope and client resource ownership

**Files**

- Add: `src/main/java/com/smart/rag/mcp/runtime/McpBearerTokenCodec.java`
- Add: `src/test/java/com/smart/rag/mcp/runtime/McpBearerTokenCodecTest.java`
- Add: `src/test/java/com/smart/rag/mcp/runtime/McpClientFactoryTest.java`
- Modify: `src/main/java/com/smart/rag/mcp/runtime/McpClientFactory.java`
- Modify: `src/main/java/com/smart/rag/mcp/admin/service/McpServerAdminService.java`

### 4.1 RED: token round-trip and fail-closed matrix

- Construct `SecretCipher` with a fixed 32-byte Base64 master key and assert `encode("secret")` starts with `v1:` and decodes exactly.
- Assert null stored value decodes null; unknown version, missing segment, invalid Base64, non-12-byte IV and tampered cipher throw `ServiceException` with cause where applicable.
- Assert unavailable cipher rejects non-empty encode with `ClientException`.
- Run:

```bash
mvn test -Dtest=McpBearerTokenCodecTest
```

### 4.2 GREEN: shared codec

- Implement envelope with `Base64.getEncoder/Decoder`; never log token/envelope.
- Replace Admin encryption and Factory decryption with the codec. Remove direct UTF-8 binary conversion and empty IV use.
- Ensure decode failures propagate; do not catch and return null.

### 4.3 RED/GREEN: close on failures

- Introduce package-visible factory seams for building transport/client if necessary, without adding production abstractions beyond one overridable/package-private method.
- Test client is closed when initialize throws.
- In `McpServerAdminServiceTest`, test client is closed if derived server info is invalid, DB update fails, or registry add fails; test successful registry handoff does not close the new client.
- Implement `try/finally` ownership transfer boolean. Preserve local `ServiceException`; wrap remote initialize failures as `RemoteException(MCP_SERVER_UNREACHABLE)` with safe Chinese message.
- Run:

```bash
mvn test -Dtest=McpBearerTokenCodecTest,McpClientFactoryTest,McpServerAdminServiceTest
```

## Task 5: V18 state model, offset time and auto-connect

**Files**

- Add: `src/main/resources/db/migration/V18__repair_mcp_admin_constraints.sql`
- Add: `src/test/java/com/smart/rag/mcp/admin/McpV18MigrationContractTest.java`
- Add: `src/test/java/com/smart/rag/mcp/admin/mapper/McpMapperContractTest.java`
- Modify: `src/main/java/com/smart/rag/mcp/admin/entity/McpServerConfig.java`
- Modify: `src/main/java/com/smart/rag/mcp/admin/entity/McpToolConfig.java`
- Modify: `src/main/java/com/smart/rag/mcp/admin/mapper/McpServerConfigMapper.java`
- Modify: `src/main/resources/mapper/McpServerConfigMapper.xml`
- Modify: `src/main/java/com/smart/rag/mcp/admin/dto/ServerConfigResponse.java`
- Modify: `src/main/java/com/smart/rag/mcp/admin/service/McpBootstrapRunner.java`
- Modify: `src/main/java/com/smart/rag/mcp/admin/service/McpServerAdminService.java`

### 5.1 RED: migration and SQL contracts

- Test V18 resource exists, drops only `mcp_server_config_state`, normalizes invalid intent/risk, adds enum CHECK constraints and `(server_id, tool_name)` unique constraint; assert V17 file remains unchanged by comparing its tracked content/diff.
- Test Mapper XML startup query contains both `enabled = TRUE` and `auto_connect = TRUE`, and successful connection update writes `last_connected_at = NOW()` while clearing init error.
- Run:

```bash
mvn test -Dtest=McpV18MigrationContractTest,McpMapperContractTest
```

### 5.2 GREEN: forward-only schema and state update

- Add V18 in the exact order from design §4.5; do not edit V17.
- Add mapper methods `selectAutoConnectEnabled()` and `markConnected(serverId)`; bootstrap uses the former and every successful create/reconnect uses the latter.
- Replace all MCP entity `LocalDateTime` fields mapped from TIMESTAMPTZ with `OffsetDateTime`; keep response strings generated from offset-aware values.
- Add bootstrap test asserting `autoConnect=false` rows are not passed to factory.
- Run:

```bash
mvn test -Dtest=McpV18MigrationContractTest,McpMapperContractTest,McpBootstrapRunnerTest,McpServerAdminServiceTest
```

## Task 6: Batch refresh, caps and per-server discovery isolation

**Files**

- Add: `src/test/java/com/smart/rag/mcp/admin/service/McpToolAdminServiceTest.java`
- Add: `src/test/java/com/smart/rag/mcp/mcpclient/SyncMcpToolCallbackProviderTest.java`
- Modify: `src/main/java/com/smart/rag/mcp/admin/mapper/McpToolConfigMapper.java`
- Modify: `src/main/resources/mapper/McpToolConfigMapper.xml`
- Modify: `src/main/java/com/smart/rag/mcp/admin/service/McpToolAdminService.java`
- Modify: `src/main/java/com/smart/rag/mcp/runtime/McpServerImpl.java`
- Modify: `src/main/java/com/smart/rag/mcp/mcpclient/SyncMcpToolCallbackProvider.java`

### 6.1 RED: refresh must fail honestly and use batch I/O

- Test placeholder/remote `listTools` failure becomes `RemoteException`, mapper is not called, cache is not invalidated.
- Test success with multiple tools invokes one `batchUpsert` and no per-tool select/insert; capture rows and assert canonical names plus descriptions capped by `toolDescCharLimit`.
- Test conflict update contract preserves enabled/intent/risk/description_override.
- Run:

```bash
mvn test -Dtest=McpToolAdminServiceTest,McpMapperContractTest
```

### 6.2 GREEN: batch upsert and single invalidation

- Add mapper `batchUpsert(@Param("tools") List<McpToolConfig>)`; XML uses one PostgreSQL multi-row INSERT with `ON CONFLICT (server_id, tool_name) DO UPDATE` and only remote-owned update columns.
- `McpServerImpl.listToolsFromRemote()` throws classified RemoteException instead of returning empty.
- After successful batch write, invalidate tool accessor and provider once.

### 6.3 RED/GREEN: isolate provider failures

- Build registry with one adapter/server throwing and one returning a callback; assert healthy callback remains.
- Retain duplicate-name test to prove global data corruption still raises `IllegalStateException`.
- Catch per-server discovery exception inside provider loop, log server id with parameterized WARN, continue.
- Remove deprecated List-client discovery path only after configuration and tests prove no consumer remains.
- Run:

```bash
mvn test -Dtest=SyncMcpToolCallbackProviderTest,McpToolAdminServiceTest,McpServerImplTest
```

## Task 7: Decompose McpServerImpl and harden shutdown/errors

**Files**

- Add: `src/main/java/com/smart/rag/mcp/runtime/McpSchemaMapper.java`
- Add: `src/main/java/com/smart/rag/mcp/runtime/McpRemoteCallExecutor.java`
- Add: `src/main/java/com/smart/rag/mcp/mcpclient/DefaultMcpServerToolCallbacksAdapter.java`
- Add corresponding unit tests under `src/test/java/com/smart/rag/mcp/runtime/` and `mcpclient/`
- Modify: `src/main/java/com/smart/rag/mcp/runtime/McpServerImpl.java`
- Modify: `src/main/java/com/smart/rag/mcp/runtime/McpServerRegistryImpl.java`
- Modify: `src/main/java/com/smart/rag/mcp/config/McpClientTransportConfiguration.java`
- Modify: `src/main/java/com/smart/rag/mcp/runtime/McpErrors.java`

### 7.1 RED: preserve runtime behavior before extraction

- Expand `McpServerImplTest` for tool/resource/prompt mapping, blocked URI scheme, eligible remote failure/circuit counts, programming error classification and placeholder behavior.
- Add registry destroy test: queued closes drain, timeout calls shutdownNow, interrupted wait restores interrupt.
- Run:

```bash
mvn test -Dtest=McpServerImplTest,McpServerRegistryImplTest
```

### 7.2 GREEN/REFACTOR: extract pure concerns

- Move schema conversion to stateless `McpSchemaMapper`.
- Move circuit check/record/error classification template to `McpRemoteCallExecutor` while keeping tool fail-soft versus resource/prompt fail-hard behavior explicit at call sites.
- Move callback discovery from `McpServerImpl` to Spring component `DefaultMcpServerToolCallbacksAdapter`; configuration injects this bean through existing interface.
- Keep `McpServerImpl` public behavior unchanged and reduce it below 300 lines; each extracted class remains below 300 lines and has at most 8 constructor dependencies.
- Replace manual `new Thread` with `Thread.ofPlatform().name("mcp-async-close-", 0).daemon(true).factory()` and implement shutdown/await/shutdownNow.
- Replace raw error concatenation with safe Chinese messages; preserve causes; remove stale v3/v4 transition comments, dead compatibility constructors and concatenated logging.
- Run:

```bash
mvn test -Dtest=McpServerImplTest,McpServerRegistryImplTest,SyncMcpToolCallbackProviderTest,McpDependencyRulesTest
```

## Task 8: Full checklist verification and completion

### 8.1 Static checklist probes

Run and require no prohibited MCP production matches:

```bash
rg -n '@Transactional|BusinessException|IllegalArgumentException|new Thread\(|log\.(trace|debug|info|warn|error)\([^,]*\+' src/main/java/com/smart/rag/mcp
find src/main/java/com/smart/rag/mcp -name '*.java' -print0 | xargs -0 wc -l | sort -nr | head -20
rg -n '@RequestBody(?!.*@Valid)' src/main/java/com/smart/rag/mcp/admin/controller --pcre2
rg -n 'LocalDateTime' src/main/java/com/smart/rag/mcp
```

Review every item in `.trellis/spec/backend/code-review-checklist.md` and record PASS/N/A plus evidence in the final report. Re-check every original finding against code and tests; no HIGH/CRITICAL item may remain.

### 8.2 Test gates

```bash
mvn test -Dtest='Mcp*Test,*Mcp*Test'
mvn test -Dtest=McpDependencyRulesTest
mvn test
```

If Docker/PostgreSQL is available, apply Flyway through V18 to an empty database and a V17 database; otherwise explicitly report the environment gap and rely on migration contract tests.

### 8.3 Change-scope and Trellis gates

- Run GitNexus `detect_changes(scope="compare", base_ref="agentic-rag-dev")`; investigate every unexpected symbol/flow.
- Run `trellis-check` in inline mode, fix all findings, then run `trellis-update-spec` and record only genuinely reusable conventions.
- Run `git diff --check`, inspect `git status`, and ensure the pre-existing change in `.trellis/tasks/07-07-mcp-policy-db-migration/task.json` was neither overwritten nor included accidentally.
- Create one Lore commit whose intent explains why MCP runtime trust boundaries were repaired; include `Tested:` and honest `Not-tested:` trailers.
- Run `/trellis:finish-work` flow only after tests, checklist, detect_changes and commit all succeed.

## Completion Evidence Matrix

| Acceptance area | Primary proof |
|---|---|
| Bean cycle / class size | `McpBeanGraphTest`, structure test, line-count probe |
| V18 / state / time | migration + mapper contract tests, bootstrap tests |
| Naming / filter | `McpToolUtilsTest`, `DatabaseToolFilterTest` |
| Auth / intent / hard call | `McpAuthorizerTest`, `McpServerImplTest` |
| Token / fail closed | `McpBearerTokenCodecTest`, `McpClientFactoryTest` |
| Failure isolation | provider + tool admin tests |
| Resource ownership | client factory, server admin, registry tests |
| Validation | MockMvc + security service tests |
| Batch I/O / caps / cache | tool admin + Mapper XML contract tests |
| Architecture / all regressions | ArchUnit, MCP suite, full Maven suite, checklist replay |
