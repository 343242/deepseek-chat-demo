# Implementation Plan: MCP Admin Management (v4 — 评审修复版)

> 对应 design.md v4。每个 Phase 完成后跑 `mvn compile && mvn test`（或对应测试子集），通过后进入下一 Phase。

---

## v4 修订摘要（详见 design.md v4 章节）

实施时需对照 design.md v4 修订表，主要变更：

| v4 修订 | 影响 Phase | 关键调整 |
|---|---|---|
| **B1** 异常类按语义替换（不分裂枚举） | 全部 | `RATE_LIMITED` → `ClientException(ClientErrorCode.RATE_LIMITED)`；MCP init/reconnect 失败 → `RemoteException(RemoteErrorCode.MCP_SERVER_UNREACHABLE)`；新增 `ClientErrorCode.OPTIMISTIC_LOCK_CONFLICT(100014)` |
| **B2** Adapter 接口替代污染 core | Phase 2.5 | 不在 `McpServer` 加 `toolCallbacks`；新建 `mcp/mcpclient/McpServerToolCallbacksAdapter` 接口，`McpServerImpl` 实现 |
| **B3** 注册 OptimisticLockerInnerInterceptor | Phase 0.0（新增） | `MyBatisPlusConfig` 加 `OptimisticLockerInnerInterceptor` |
| **B4** serverId 系统派生 | Phase 3.1 / 4.1 | `CreateServerRequest` 去 `serverId`；`createServer` 流程：INSERT(NULL) → 握手 → UPDATE 回填 |
| **B5** accessor 缓存编译产物 | Phase 5.0 | `McpSecurityConfigAccessor.patterns()` DCL 缓存 `List<Pattern>` |
| **C1** 统一 Jackson | 全部 | `JSON.parseObject/toJSONString` → `objectMapper.readValue/writeValueAsString` |
| **C2** Filter 单 Bean | Phase 3.2 | strict/lenient 合并；`isToolEnabled` 返 `Boolean`（三态） |
| **C3** 审计 CallerRunsPolicy | Phase 0.4 | 队列满 caller 同步执行，不丢数据 |
| **C5** Bearer bootstrap host 粒度 | Phase 3.1 | 文档化已知限制，bootstrap 后 per-server 覆盖 |
| **C7** @AdminAudit 自调用约束 | Phase 0.4 | 注解 Javadoc 加约束（当前 design 不受影响） |
| **C8** Transport Properties 对称降级 | Phase 2.6 / 5 | `McpClientTransportProperties` 同 `McpSecurityProperties`，仅 bootstrap 期读 |

---

## Phase 0: 通用安全原语 + 通用审计基础设施下沉

> **背景**：Bearer Token 进 DB 需要加密 → `ApiKeyCipher` 必须下沉到 `infrastructure/security/`；同时把 `BaseUrlValidator` 一起下沉重命名为通用类名。**这两个迁移是后续 MCP Server URL 校验和加密存储的前置条件**（逻辑相同，都需 DB 存储 + 加密），不是范围蔓延。
>
> 同时建立通用审计基础设施（`infrastructure/audit/`），未来 LLM/RAG 等模块可直接复用，本期 MCP 是首个用户。

### Step 0.0: 前置——注册乐观锁拦截器 + 新增错误码（v4 B1 + B3）

> **v4 新增**：B3 发现 `@Version` 注解需配合 `OptimisticLockerInnerInterceptor` 才生效；B1 决定新增一个 `ClientErrorCode.OPTIMISTIC_LOCK_CONFLICT` 而非分裂枚举。两者都是后续 Phase 的前置依赖。

#### 0.0.1 注册 `OptimisticLockerInnerInterceptor`

**文件**：`src/main/java/com/smart/rag/config/MyBatisPlusConfig.java`

- [ ] 在 `mybatisPlusInterceptor()` 中**先**加 `OptimisticLockerInnerInterceptor`（必须在 `PaginationInnerInterceptor` 之前，否则分页 SQL 干扰乐观锁 WHERE 子句）：

```java
interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());
interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.POSTGRE_SQL));
```

- [ ] **回归验证**：grep `@Version` 在 `src/main/java`，逐一检查现有实体是否已经在用但失效（如有则属既有 bug，单独 task 修复，不扩大本期范围）

**验证**：`mvn compile && mvn test`（确认无回归）

#### 0.0.2 新增 `ClientErrorCode.OPTIMISTIC_LOCK_CONFLICT`

**文件**：`src/main/java/com/smart/rag/infrastructure/exception/errorcode/ClientErrorCode.java`

- [ ] 在"通用 100001–100999"段尾（`CONTENT_FILTERED(100006)` 之后）加：

```java
CONFLICT(100013, "资源已被修改，请刷新重试"),  // 通用冲突（HTTP 409）
OPTIMISTIC_LOCK_CONFLICT(100014, "资源版本冲突，请刷新重试"),  // v4 B1：乐观锁专用
```

> 注：100013 ~ 100014 区间未被占用，紧贴 `RATE_LIMITED(100005)` / `CONTENT_FILTERED(100006)` 之后。

- [ ] 检查 `GlobalExceptionHandler`：确认 `ClientException` 映射到合适 HTTP 状态（`OPTIMISTIC_LOCK_CONFLICT` 应该 → 409 Conflict；若现有 handler 是按异常类映射而非按 code，需新增映射规则）

**验证**：`mvn compile && mvn test -Dtest=ClientErrorCodeTest`（若有）或 `mvn test -Dtest=GlobalExceptionHandlerTest`

### Step 0.1: 下沉 `BaseUrlValidator` → `HostSafetyValidator`

**源**：`src/main/java/com/smart/rag/infrastructure/llm/config/BaseUrlValidator.java`
**目标**：`src/main/java/com/smart/rag/infrastructure/security/HostSafetyValidator.java`

- [ ] 创建 `infrastructure/security/` 包
- [ ] 移动并重命名 `BaseUrlValidator` → `HostSafetyValidator`
- [ ] 移除对 `LlmByokProperties` 的依赖，改注入新建的 `SecuritySsrProperties`（`app.security.ssrf.*`，字段：`allowedPorts`）
- [ ] `DnsResolver` 也一并迁移到 `infrastructure/security/`
- [ ] 类名/方法名去掉 LLM 专属语义（如 `validateByokBaseUrl` → `validate`）

**验证**：`mvn compile && mvn test -Dtest=HostSafetyValidatorTest`

### Step 0.2: 下沉 `ApiKeyCipher` → `SecretCipher`

**源**：`src/main/java/com/smart/rag/infrastructure/llm/crypto/ApiKeyCipher.java`
**目标**：`src/main/java/com/smart/rag/infrastructure/security/SecretCipher.java`

- [ ] 移动并重命名 `ApiKeyCipher` → `SecretCipher`
- [ ] 移除对 `LlmCryptoProperties` 的依赖，改注入新建的 `SecurityCryptoProperties`（`app.security.crypto.*`，字段：`masterKey`）
- [ ] 方法名通用化（`encryptApiKey` → `encrypt`，`decryptApiKey` → `decrypt`）

**验证**：`mvn compile && mvn test -Dtest=SecretCipherTest`

### Step 0.3: 更新 LLM 模块 import

- [ ] `infrastructure/llm/` 下所有 import 路径：`BaseUrlValidator` → `HostSafetyValidator`，`ApiKeyCipher` → `SecretCipher`
- [ ] LLM 模块注入 `SecuritySsrProperties` / `SecurityCryptoProperties`（不再用 LLM 专属 Properties）

**验证**：`mvn compile && mvn test -Dtest=*Llm*,*Crypto*,*Ssrf*`

### Step 0.4: 新建通用审计基础设施 `infrastructure/audit/`

**位置**：`src/main/java/com/smart/rag/infrastructure/audit/`

- [ ] `AdminAudit.java` —— `@Target(METHOD) @Retention(RUNTIME)` 注解，字段：`resourceType` / `action` / `resourceIdExpr`（SpEL，默认空）/ `logRequest`（默认 true）/ `sensitiveFields`（默认空数组）。**Javadoc 加 v4 C7 自调用约束**：被 `@AdminAudit` 标注的方法之间禁止 `this.xxx()` 直接调用（Spring AOP 代理限制）
- [ ] `AdminAuditAspect.java` —— `@Around("@annotation(adminAudit)")`：
  - 解析 SpEL `resourceIdExpr`（注册方法参数名，失败时返回 null 不阻塞业务）
  - `OperatorInfo.fromSecurityContext()`：从 `SecurityContextHolder` 取 userId/username/role
  - `RequestMeta.fromRequestContext()`：从 `RequestContextHolder` 取 IP（X-Forwarded-For 优先）/ User-Agent
  - `finally` 块异步写入（成功/失败都写），失败时记录 `errorCode` / `errorMessage`
  - `sanitizePayload`：Jackson 序列化 + 按 `sensitiveFields` 路径替换为 `"***"`
- [ ] `AdminAuditAsyncWriter.java` —— 单线程 daemon executor，**队列 2000 + `CallerRunsPolicy`**（v4 C3：队列满让 caller 同步执行，不丢审计数据；而非 v3 的 `DiscardOldestPolicy`），`@PreDestroy` 等 5s 排空
- [ ] `AdminAuditLogService.java` —— 查询 service（ADMIN 看 audit log 用，分页查询）
- [ ] `entity/AdminAuditLog.java` —— 见 design.md Data Model
- [ ] `mapper/AdminAuditLogMapper.java` + `resources/mapper/AdminAuditLogMapper.xml` —— `selectByResource` / `selectByOperator` / 分页查询
- [ ] `pom.xml` 确认 `spring-boot-starter-aop` 已引入（应该已有）

**依赖约束**（ArchUnit）：
- `infrastructure/audit/` **不依赖任何业务模块**（仅依赖 `infrastructure/security/`、`infrastructure/exception/`、Spring/MyBatis-Plus）
- 业务模块（MCP/LLM/RAG）单向依赖 `infrastructure/audit/`

**验证**：`mvn compile && mvn test -Dtest=AdminAuditAspectTest`

### Step 0.5: 数据库迁移（V17）

**文件**：`src/main/resources/db/migration/V17__create_mcp_admin_tables.sql`

> 最新迁移为 V16（`V16__llm_config.sql`），本次使用 V17。

```sql
-- ============================================================
-- MCP Admin 配置表
-- ============================================================

CREATE TABLE mcp_server_config (
    id                       BIGSERIAL PRIMARY KEY,
    server_id                VARCHAR(128) NOT NULL UNIQUE,
    url                      TEXT NOT NULL,
    description              VARCHAR(512),
    enabled                  BOOLEAN NOT NULL DEFAULT TRUE,
    auto_connect             BOOLEAN NOT NULL DEFAULT TRUE,
    bearer_token_encrypted   TEXT,                              -- v3 新增：加密 Bearer Token
    init_error               TEXT,                              -- v3 新增：软失败原因
    last_connected_at        TIMESTAMPTZ,
    version                  BIGINT NOT NULL DEFAULT 0,         -- v3 新增：乐观锁
    created_at               TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at               TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE mcp_tool_config (
    id                   BIGSERIAL PRIMARY KEY,
    server_id            VARCHAR(128) NOT NULL,
    tool_name            VARCHAR(256) NOT NULL,
    prefixed_tool_name   VARCHAR(512) NOT NULL UNIQUE,
    description          TEXT,
    enabled              BOOLEAN NOT NULL DEFAULT FALSE,        -- v3 修订：默认 false（修复 1.4）
    intent               VARCHAR(64),
    risk                 VARCHAR(32) DEFAULT 'low',
    description_override TEXT,
    version              BIGINT NOT NULL DEFAULT 0,             -- v3 新增：乐观锁
    created_at           TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_mcp_tool_config_server_id ON mcp_tool_config(server_id);

-- v3 修订：jsonb 单行表（原 EAV 改为单行 jsonb）
CREATE TABLE mcp_security_config (
    id              BIGINT PRIMARY KEY DEFAULT 1,
    config_json     JSONB NOT NULL,
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT mcp_security_config_single_row CHECK (id = 1)
);

INSERT INTO mcp_security_config (id, config_json) VALUES
(1, '{"sensitiveArgPatterns":[],"defaultOutputCapChars":4000,"highRiskOutputCapChars":1000,"toolDescCharLimit":1024}');

-- ============================================================
-- 通用审计日志表（不绑定 MCP，未来 LLM/RAG 等模块复用）
-- ============================================================

CREATE TABLE admin_audit_log (
    id              BIGSERIAL PRIMARY KEY,
    operator_id     BIGINT,
    operator_name   VARCHAR(128),
    operator_role   VARCHAR(64),
    resource_type   VARCHAR(64) NOT NULL,
    resource_id     VARCHAR(128),
    action          VARCHAR(64) NOT NULL,
    request_payload JSONB,
    result_status   VARCHAR(16) NOT NULL,
    error_code      VARCHAR(64),
    error_message   TEXT,
    ip_address      VARCHAR(64),
    user_agent      VARCHAR(256),
    duration_ms     INTEGER,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_admin_audit_log_operator   ON admin_audit_log(operator_id, created_at DESC);
CREATE INDEX idx_admin_audit_log_resource   ON admin_audit_log(resource_type, resource_id, created_at DESC);
CREATE INDEX idx_admin_audit_log_action     ON admin_audit_log(action, created_at DESC);
CREATE INDEX idx_admin_audit_log_created_at ON admin_audit_log(created_at DESC);
```

**验证**：`mvn flyway:migrate` 或重启应用观察 Flyway 自动执行

---

## Phase 1: Entity + Mapper

### Step 1.1: Entity 类

**目录**：`src/main/java/com/smart/rag/mcp/admin/entity/`

- [ ] `McpServerConfig.java` —— 字段：`id` / `serverId` / `url` / `description` / `enabled` / `autoConnect` / `bearerTokenEncrypted` / `initError` / `lastConnectedAt` / `@Version version` / `createdAt` / `updatedAt`
- [ ] `McpToolConfig.java` —— 字段：见 design.md，含 `@Version version`
- [ ] `McpSecurityConfig.java` —— 单行表 Entity，`id` 固定 INPUT（手动设 1），`configJson` 字段 + `view()` 反序列化方法
- [ ] `McpSecurityConfigView.java`（record）—— `sensitiveArgPatterns` / `defaultOutputCapChars` / `highRiskOutputCapChars` / `toolDescCharLimit`，含 `defaults()` 静态工厂

参考 `LlmModelConfig` 实体风格（`@TableName` / `@TableId(type = IdType.AUTO)`）。

**验证**：`mvn compile`

### Step 1.2: Mapper 接口

**目录**：`src/main/java/com/smart/rag/mcp/admin/mapper/`

- [ ] `McpServerConfigMapper extends BaseMapper<McpServerConfig>` —— 自定义：`selectByServerId` / `selectAllEnabled` / `updateInitError` / `updateBearerToken`（带 `version` 条件）
- [ ] `McpToolConfigMapper extends BaseMapper<McpToolConfig>` —— 自定义：`selectByServerId` / `selectAllEnabled` / `updateEnabledByServerId` / `batchUpdateEnabled` / `deleteByServerId` / **`selectByPrefixedName`**（DatabaseToolFilter 用）
- [ ] `McpSecurityConfigMapper extends BaseMapper<McpSecurityConfig>` —— 自定义：`selectSingleton` / `updateConfigJson`

**注意**：所有自定义方法在 XML 中实现，不用注解 SQL。

**验证**：`mvn compile`

### Step 1.3: XML SQL 文件

**目录**：`src/main/resources/mapper/`

- [ ] `McpServerConfigMapper.xml` —— 含 `updateInitError` / `updateBearerToken` 的 `WHERE version = #{version}` 条件
- [ ] `McpToolConfigMapper.xml`
- [ ] `McpSecurityConfigMapper.xml`
- [ ] `AdminAuditLogMapper.xml`（Phase 0.4 已建）

**验证**：`mvn compile && mvn test -Dtest=*MapperTest`

---

## Phase 2: 运行时层（Registry 快照模式 + ClientFactory + CallbackProvider 重构）

### Step 2.1: 抽取 `McpServerRegistryAdmin` 接口（修复 1.3）

**文件**：`src/main/java/com/smart/rag/mcp/runtime/McpServerRegistryAdmin.java`（新建）

- [ ] 定义：`addServer(McpServerConfig, @Nullable McpSyncClient, @Nullable String initError)` / `removeServer(ServerId)` / `replaceServer(McpServerConfig, McpSyncClient)` / `currentVersion()`
- [ ] Javadoc 说明：写契约；调用方为 `McpAdminService`

**验证**：`mvn compile`

### Step 2.2: 改造 `McpServerRegistryImpl`（修复 1.1 + 1.2）

**文件**：`src/main/java/com/smart/rag/mcp/runtime/McpServerRegistryImpl.java`

- [ ] 实现 **两个接口**：`implements McpServerRegistry, McpServerRegistryAdmin`
- [ ] 字段：`AtomicReference<ImmutableMap<ServerId, McpServer>> snapshotRef`（初始 `ImmutableMap.of()`）
- [ ] 字段：`AtomicLong version`（初始 0L）
- [ ] 字段：`ExecutorService asyncCloseExecutor`（单线程 daemon，名字 `mcp-async-close`）
- [ ] **移除** `@PostConstruct init()`（打破循环依赖，修复 1.2）—— 初始化逻辑挪到 `McpAdminService.run()`
- [ ] **移除** `ObjectProvider<List<McpSyncClient>>` 依赖（不再静态注入 client 列表）
- [ ] 实现 `addServer`：CAS 循环 `snapshotRef`，构建新 ImmutableMap（`builder().putAll(old).put(id, server).build()`），增量后 `version.incrementAndGet()`；旧 server（同 id）异步 close
- [ ] 实现 `removeServer`：CAS 循环，用 `ImmutableMap.filterKeys(old, k -> !k.equals(id))` 构建新快照；旧 server 异步 close + `circuitRegistry.evict(id.value())`
- [ ] 实现 `replaceServer`：delegate `addServer(config, client, null)`
- [ ] 实现 `currentVersion`：返回 `version.get()`
- [ ] `@PreDestroy destroy()`：getAndSet 空 ImmutableMap → 同步 close 所有 server 的 client → shutdown executor

**关键点**：`addServer` 支持 `client=null`（占位 server，`initError` 非空），`McpServerImpl.hasClient()` 用于判断是否需要 close。

**参考**：`LlmClientRegistry.snapshotRef` + `asyncClose()` 模式（但简化——MCP 不需要 BYOK per-user 快照）。

**验证**：`mvn compile && mvn test -Dtest=McpServerRegistryImplTest`

### Step 2.3: 改造 `McpServerImpl` 支持占位（修复 1.5）

- [ ] 构造函数支持 `client=null` + `initError != null`（占位模式）
- [ ] `hasClient()` 方法（用于判断是否需要 close）
- [ ] `tools()` / `callTool()` 在 `client == null` 时返回 `McpToolResult.error("MCP链接断开正在重新连接")`
- [ ] `closeQuietly()` 方法（不抛）

**验证**：`mvn compile`

### Step 2.4: 新建 `McpClientFactory`（修复 1.7）

**文件**：`src/main/java/com/smart/rag/mcp/runtime/McpClientFactory.java`

- [ ] 注入 `HostSafetyValidator` / `SecretCipher` / `McpSecurityProperties`（仅读 requestTimeout 等非密配置）
- [ ] `createClient(McpServerConfig)`：
  1. `urlValidator.validate(config.getUrl())`
  2. 解密 `bearerTokenEncrypted`（`secretCipher.decrypt`，null/blank 则跳过）
  3. 构建 `HttpClientStreamableHttpTransport`（含 `httpRequestCustomizer` 注入 `Authorization: Bearer <token>`）
  4. 构建 `McpSyncClient.sync(transport).requestTimeout(...).clientInfo(...).build()`
  5. `client.initialize()`（握手，fail-fast 抛异常由调用方捕获写入 `init_error`）
- [ ] `destroyClient(McpSyncClient)`：try/catch 关闭

**验证**：`mvn compile && mvn test -Dtest=McpClientFactoryTest`

### Step 2.5: 改造 `SyncMcpToolCallbackProvider`（v3 修复 1.6，v4 B2 修复 core 污染）

> **v4 B2 关键修订**：不在 `McpServer` core 接口加 `toolCallbacks(...)` 方法（违反 core 零 starter 依赖铁律）。改为在 `mcp/mcpclient/` 加 runtime 层 adapter 接口。

#### 2.5.1 新建 `McpServerToolCallbacksAdapter` 接口

**文件**：`src/main/java/com/smart/rag/mcp/mcpclient/McpServerToolCallbacksAdapter.java`（新建）

- [ ] 定义接口：

```java
public interface McpServerToolCallbacksAdapter {
    /**
     * 从单个 McpServer 抽出经 filter/prefix 处理后的 Spring AI ToolCallback 列表。
     * 实现位于 runtime 层（{@code McpServerImpl}），允许引用 Spring AI {@link ToolCallback}。
     * core 层的 {@code McpServer} 接口不感知此 adapter。
     */
    List<ToolCallback> toolCallbacks(McpServer server,
                                     McpToolFilter filter,
                                     McpToolNamePrefixGenerator prefixGen,
                                     ToolContextToMcpMetaConverter metaConverter);
}
```

#### 2.5.2 `McpServerImpl` 实现 adapter

**文件**：`src/main/java/com/smart/rag/mcp/runtime/McpServerImpl.java`

- [ ] 类签名改为 `implements McpServer, McpServerToolCallbacksAdapter`（不修改 `McpServer` core 接口）
- [ ] 实现 `toolCallbacks(...)`：把原本散在 `SyncMcpToolCallbackProvider.getToolCallbacks()` 里的 per-server 遍历逻辑下沉到此（用本 server 的 `McpSyncClient` + `connInfo` 调 `listTools()` → filter → prefix → build callback）

#### 2.5.3 改造 `SyncMcpToolCallbackProvider`

**文件**：`src/main/java/com/smart/rag/mcp/mcpclient/SyncMcpToolCallbackProvider.java`

- [ ] **移除** 构造函数的 `List<McpSyncClient>` 参数
- [ ] **新增** 注入 `McpServerRegistry registry` + `McpServerToolCallbacksAdapter adapter`
- [ ] 字段：`volatile long cachedVersion = -1L` / `volatile ToolCallback[] cachedCallbacks`
- [ ] `getToolCallbacks()`：
  1. 取 `currentVersion = (registry instanceof McpServerRegistryAdmin a) ? a.currentVersion() : 0L`
  2. 若 `currentVersion == cachedVersion && cachedCallbacks != null` → 返回 cache
  3. 否则遍历 `registry.list()`，跳过 `initError != null` 的 server
  4. 调 `adapter.toolCallbacks(server, filter, prefixGen, metaConverter)`（v4：经 adapter 而非 server.toolCallbacks）
  5. 聚合为 array，更新 `cachedVersion` + `cachedCallbacks`
- [ ] `invalidateCache()`：`cachedVersion = -1L; cachedCallbacks = null;`

**验证**：`mvn compile && mvn test -Dtest=SyncMcpToolCallbackProviderTest`

### Step 2.6: 改造 `McpClientTransportConfiguration`（v4 C8：对称降级为 bootstrap）

**文件**：`src/main/java/com/smart/rag/mcp/config/McpClientTransportConfiguration.java`

> **v4 C8 决策**：`McpClientTransportProperties` 与 `McpSecurityProperties` 对称——都降级为 bootstrap（仅 `McpAdminService.run()` 启动时读一次，运行时无 Bean 注入）。

- [ ] **保留** `McpClientTransportProperties` Bean（**仅** `McpAdminService.bootstrapFromYaml()` 启动时注入一次）
- [ ] **删除** `mcpSyncClients()` Bean（不再静态创建 `List<McpSyncClient>`，改由 `McpClientFactory.createClient()` 动态创建）
- [ ] **保留** `SyncMcpToolCallbackProvider` Bean，构造改注入 `McpServerRegistry` + `McpServerToolCallbacksAdapter`（见 Step 2.5）
- [ ] **删除** `mcpBearerAuthRequestCustomizer()` Bean（Bearer Token 改在 `McpClientFactory.buildTransport` 内注入）
- [ ] Javadoc 加注释：本类所有 Bean 仅启动期使用，运行时配置源是 DB

**验证**：`mvn compile && mvn test -Dtest=Mcp*`
- 重点验证：`grep -r "McpClientTransportProperties" src/main/java`，确认只有 `McpClientTransportConfiguration`（声明）和 `McpAdminService`（注入）两处引用

---

## Phase 3: 核心 Service（McpAdminService）

### Step 3.1: 新建 `McpAdminService`（v4 B1 异常替换 + B4 serverId 系统派生）

**文件**：`src/main/java/com/smart/rag/mcp/admin/service/McpAdminService.java`

> **v4 关键变更**：
> - **B1 异常替换**（全文应用）：所有 `ServiceException(RATE_LIMITED)` → `ClientException(ClientErrorCode.RATE_LIMITED)`；所有 `ServiceException(REMOTE_INIT_FAILED)` → `RemoteException(RemoteErrorCode.MCP_SERVER_UNREACHABLE)`；所有 `ServiceException(OPTIMISTIC_LOCK_CONFLICT)` → `ClientException(ClientErrorCode.OPTIMISTIC_LOCK_CONFLICT)`
> - **B4 serverId 系统派生**：`createServer` 不接受 `serverId` 入参，握手成功后由 `McpToolUtils.format(serverInfo.name)` 回填；握手失败用 `unreachable-<rowId>` 占位
> - **B5 accessor 接管安全配置**：移除 `securityConfigCache` 字段，统一注入 `McpSecurityConfigAccessor`；`updateSecurityConfig` 调 `accessor.invalidate()`
> - **C1 Jackson**：所有 `JSON.parseObject/toJSONString` → `objectMapper.readValue/writeValueAsString`
> - **C2 isToolEnabled 三态**：返回 `Boolean`（null = 未入库），配合 `DatabaseToolFilter` 单 Bean

- [ ] `@Service` + `implements ApplicationRunner`（启动初始化，打破循环依赖）
- [ ] 注入：三个 Mapper / `TransactionTemplate` / `McpClientFactory` / **`McpServerRegistryAdmin`**（接口）+ `McpServerRegistry`（只读）/ `HostSafetyValidator` / `SecretCipher` / `SyncMcpToolCallbackProvider` / `McpSecurityConfigAccessor`（v4 B5）/ `ObjectMapper`（v4 C1）/ `McpClientTransportProperties` + `McpSecurityProperties`（仅 bootstrap）
- [ ] 字段：
  - `Cache<String, List<McpToolConfig>> toolListCache`（10min TTL, size=100）
  - `Cache<String, Boolean> toolEnabledCache`（10min, size=10_000）—— **三态**：null 表示未缓存（DB 未找到），Boolean.TRUE/FALSE 表示已缓存
  - `Cache<String, Long> reconnectCooldown`（30s, size=100）—— 限流
  - **移除** `securityConfigCache`（统一由 `McpSecurityConfigAccessor` 管）
- [ ] `run(ApplicationArguments)`：DB 空（`serverConfigMapper.selectCount(null) == 0`）则 `bootstrapFromYaml()`，然后 `initFromDb()`
- [ ] `bootstrapFromYaml()`：读 `McpClientTransportProperties.streamableHttp.connections`（URL 清单）→ INSERT `mcp_server_config`（`server_id=NULL`，握手后回填）→ 读 `McpSecurityProperties.bearerTokens`（Map<host,token>）→ 按 host 匹配 URL → `secretCipher.encrypt` → UPDATE `bearer_token_encrypted`
  - **v4 C5 已知限制文档化**：yaml host 粒度，同 host 多 path 共享 token；ADMIN 后续经 REST API per-server 覆盖
- [ ] `initFromDb()`：遍历 `selectAllEnabled()`，逐个 `createClient + addServer`；失败则 `updateInitError` + `addServer(config, null, errMsg)` 占位

#### Server CRUD（全部 `@AdminAudit`，异常类按 v4 B1 替换）

- [ ] `createServer(CreateServerRequest)` —— `@AdminAudit(resourceType="mcp_server", action="create", resourceIdExpr="#result.id", sensitiveFields={"bearerToken"})`（v4 B4：resourceIdExpr 改为 `#result.id`，因 `serverId` 在请求时不存在）：
  1. SSRF 校验 URL
  2. 加密 bearer token（如有）
  3. tx INSERT（`server_id=NULL`，仅 url/name/description/enabled/bearer_token_encrypted 字段）
  4. tx 外：`clientFactory.createClient(config)` 握手
     - 成功：派生 `serverId = McpToolUtils.format(client.getCurrentInitializationResult().serverInfo().name())` → UPDATE 回填 → `registryAdmin.addServer(refreshedConfig, client, null)`
     - 失败：UPDATE `init_error` + 派生合成 id `unreachable-<rowId>` → `addServer(config, null, errMsg)` 占位（**不抛**，让 ADMIN 经 `GET /servers/{id}` 看到 init_error）
- [ ] `updateServer(UpdateServerRequest)` —— `@AdminAudit(action="update")`，含 `@Version` 冲突检测（`updateById` 返回 0 行 → `ClientException(ClientErrorCode.OPTIMISTIC_LOCK_CONFLICT)`）
- [ ] `deleteServer(Long id)` —— `@AdminAudit(action="delete")`：tx 级联 DELETE → tx 外 `removeServer + invalidateToolCache`
- [ ] `enableServer(String)` —— `@AdminAudit(action="enable")`：tx UPDATE → tx 外 `createClient + addServer`；失败时软失败（`RemoteException` 转为 UPDATE `init_error` + 占位 server，**不重抛**）
- [ ] `disableServer(String)` —— `@AdminAudit(action="disable")`：tx UPDATE server + 级联 UPDATE tools → tx 外 `removeServer`
- [ ] `reconnectServer(String)` —— `@AdminAudit(action="reconnect")`：方法入口限流（`reconnectCooldown.getIfPresent != null` → `throw new ClientException(ClientErrorCode.RATE_LIMITED, ...)`）→ `replaceServer` → 失败时 `updateInitError` + `throw new RemoteException(RemoteErrorCode.MCP_SERVER_UNREACHABLE, ...)`（保留占位）
- [ ] `updateBearerToken(String, String)` —— `@AdminAudit(action="update_bearer_token", sensitiveFields={"bearerToken"})`：tx UPDATE bearer_token_encrypted（含 version 条件，0 行 → `ClientException(ClientErrorCode.OPTIMISTIC_LOCK_CONFLICT)`）→ tx 外 `createClient + replaceServer`；失败 `throw new RemoteException(MCP_SERVER_UNREACHABLE, ...)`

#### 工具管理

- [ ] `refreshTools(String serverId)` —— `@AdminAudit(resourceType="mcp_tool", action="refresh_tools")`：检查 server.initError（非空则 `throw new RemoteException(MCP_SERVER_UNREACHABLE, ...)`）→ `listToolsFromRemote()` → tx UPSERT 远端工具
  - **`prefixed_tool_name` 派生**：通过 `prefixGen.prefixedToolName(connInfo, tool)` 派生，与系统派生的 serverId 同源（都基于 `serverInfo.name`）
  - 新工具默认 `enabled=false`（修复 1.4）
- [ ] `listTools(String serverId)` —— 查询，走 `toolListCache`
- [ ] `enableTool(Long)` / `disableTool(Long)` —— `@AdminAudit(action="enable"/"disable")`，tx UPDATE → tx 外 `toolEnabledCache.invalidateAll + toolCallbackProvider.invalidateCache()`
- [ ] `batchEnableTools(List<Long>)` / `batchDisableTools(List<Long>)` —— `@AdminAudit(action="batch_enable"/"batch_disable")`

#### 安全配置

- [ ] `updateSecurityConfig(McpSecurityConfigView)` —— `@AdminAudit(resourceType="mcp_security", action="update", resourceIdExpr="'singleton'")`：tx UPDATE jsonb（用 `objectMapper.writeValueAsString`，v4 C1）→ tx 外 `accessor.invalidate()`（v4 B5：清 view + patterns 双缓存）
- [ ] `getSecurityConfig()` —— `accessor.get()`（不再走 service 自己的 cache）
- [ ] `isToolEnabled(String prefixedToolName)` —— **v4 C2 三态**：
  ```java
  public Boolean isToolEnabled(String prefixedToolName) {
      return toolEnabledCache.get(prefixedToolName, k -> {
          McpToolConfig tool = toolConfigMapper.selectByPrefixedName(k);
          return tool == null ? null : Boolean.TRUE.equals(tool.getEnabled());
      });
  }
  ```
  返回 `null` 表示"未入库"，`DatabaseToolFilter` 按 strict mode 决定 allow/deny。

**约束**：
- 严禁 `@Transactional`，全部用 `txTemplate.execute(...)`
- DB 写在 tx 内，运行时操作（registry / 缓存失效）在 tx 外
- 所有 catch (Exception) 路径都要写入 `init_error`，不能静默吞异常
- **v4 C7**：`@AdminAudit` 方法之间禁止 `this.xxx()` 直接调用（Spring AOP 限制）

**验证**：`mvn compile && mvn test -Dtest=McpAdminServiceTest`

### Step 3.2: 新建 `DatabaseToolFilter`（v4 C2：合并 strict/lenient 单 Bean）

> **v4 C2 决策**：v3 拆 `DatabaseToolFilter` + `LenientDatabaseToolFilter` 两个 `@Component` 是过度设计，且与现存 `AllowlistMcpToolFilter` 共存会导致 `McpClientTransportConfiguration.syncMcpToolCallbackProvider` 的单 `@Autowired McpToolFilter` 注入冲突。v4 合并为单 Bean + 配置项控制。

**文件**：`src/main/java/com/smart/rag/mcp/config/DatabaseToolFilter.java`

- [ ] 单 `@Component`，构造注入 `McpAdminService` + `McpToolNamePrefixGenerator` + `@Value("${app.mcp.strict-tool-filter:true}") boolean strictMode`
- [ ] `test(McpConnectionInfo, McpSchema.Tool)`：

```java
@Override
public boolean test(McpConnectionInfo conn, McpSchema.Tool tool) {
    if (conn == null || tool == null || tool.name() == null) return false;
    String prefixed = prefixGen.prefixedToolName(conn, tool);
    Boolean enabled = adminService.isToolEnabled(prefixed);  // v4 C2：三态
    if (enabled != null) return enabled;                      // 入库：按 DB 配置
    return !strictMode;                                        // 未入库：strict→deny, lenient→allow
}
```

- [ ] **不再创建** `LenientDatabaseToolFilter`（合并到上述单 Bean）
- [ ] **临时处理** `AllowlistMcpToolFilter`：本期它还在仓库中，会和新的 `DatabaseToolFilter` 冲突。两种处理方案（任选其一）：
  - **方案 A**（推荐）：在 Phase 3.2 落地时，给 `AllowlistMcpToolFilter` 加 `@ConditionalOnProperty(prefix="app.mcp", name="legacy-allowlist-filter", havingValue="true")` 默认关闭——保留代码但不注册 Bean；Phase 8 删除文件
  - **方案 B**：Phase 3.2 直接删除 `AllowlistMcpToolFilter`（更激进，但要确认 `McpToolPolicy` 在 Phase 8 前还能被 `McpSecurityGuard` / `McpDescriptionSanitizer` 读到）

**验证**：`mvn compile && mvn test -Dtest=DatabaseToolFilterTest`（覆盖：strict 模式未入库 deny、lenient 模式未入库 allow、入库 enabled=true/false 三态）

---

## Phase 4: API 层（17 端点，仅 GET/POST，全部 @PreAuthorize ADMIN）

### Step 4.1: DTO

**目录**：`src/main/java/com/smart/rag/mcp/admin/dto/`

> **v4 B4 关键变更**：所有 `CreateServerRequest` / `UpdateServerRequest` 移除 `serverId` 字段（系统派生，ADMIN 不可改）。

- [ ] `CreateServerRequest(url, name, description, autoConnect, bearerToken)` —— **v4：移除 `serverId`**（系统握手后回填）；`name` 是展示名（不影响 serverId / 工具前缀）
- [ ] `UpdateServerRequest(url, name, description, version)` —— **v4：移除 `serverId`**，含 version 用于乐观锁
- [ ] `UpdateBearerTokenRequest(bearerToken)`
- [ ] `ServerConfigResponse` —— 含 health 状态（从 registry.find 取 initError）+ version + **serverId**（响应里展示，由系统派生）
- [ ] `ToolConfigResponse` —— 含 version
- [ ] `UpdateToolRequest(enabled, intent, risk, descriptionOverride, version)`
- [ ] `BatchToolUpdateRequest(ids, enabled)`
- [ ] `UpdateSecurityConfigRequest(view fields)`
- [ ] `SecurityConfigResponse`

**验证**：`mvn compile`

### Step 4.2: `McpAdminController`

**文件**：`src/main/java/com/smart/rag/mcp/admin/controller/McpAdminController.java`

类级 `@RestController @RequestMapping("/api/admin/mcp") @PreAuthorize("hasRole('ADMIN')")`（类级 PreAuthorize 覆盖所有方法）

| 方法 | 路径 | 调用 Service |
|---|---|---|
| GET | `/servers` | `listServers(page, size)` |
| GET | `/servers/{id}` | `getServer(id)` |
| POST | `/servers` | `createServer(CreateServerRequest)` |
| POST | `/servers/{id}/update` | `updateServer(id, UpdateServerRequest)` |
| POST | `/servers/{id}/delete` | `deleteServer(id)` |
| POST | `/servers/{id}/enable` | `enableServer(serverId)` |
| POST | `/servers/{id}/disable` | `disableServer(serverId)` |
| POST | `/servers/{id}/reconnect` | `reconnectServer(serverId)` |
| POST | `/servers/{serverId}/update-bearer-token` | `updateBearerToken(serverId, token)` |
| GET | `/servers/{serverId}/tools` | `listTools(serverId, page, size)` |
| POST | `/servers/{serverId}/refresh-tools` | `refreshTools(serverId)` |
| POST | `/tools/{id}/enable` | `enableTool(id)` |
| POST | `/tools/{id}/disable` | `disableTool(id)` |
| POST | `/tools/batch-enable` | `batchEnableTools(ids)` |
| POST | `/tools/batch-disable` | `batchDisableTools(ids)` |
| GET | `/security` | `getSecurityConfig()` |
| POST | `/security/update` | `updateSecurityConfig(view)` |
| GET | `/health` | 聚合各 server 健康状态（v3 新增） |

**注**：
- `/api/admin/mcp/health` 为聚合健康检查（v3 新增，缺失-5），遍历 `registryRead.list()` 返回各 server 的 initError
- Controller 层不写业务逻辑，只做 DTO ↔ Entity 转换 + delegate to service
- 异常通过 `GlobalExceptionHandler` 统一处理（`OptimisticLockingFailureException` → 409，`RATE_LIMITED` → 429）

**验证**：`mvn compile && mvn test -Dtest=McpAdminControllerTest`

---

## Phase 5: 改造现有组件

### Step 5.0: 新建 `McpSecurityConfigAccessor`（v4 B5 编译产物缓存 + C8 断环）

**文件**：`src/main/java/com/smart/rag/mcp/admin/service/McpSecurityConfigAccessor.java`

> **v4 升级**：v3 只承担 view 缓存；v4 B5 加 `List<Pattern>` 编译产物缓存（DCL），避免 chat 热路径每次 `Pattern.compile`。

- [ ] `@Component`，依赖 `McpSecurityConfigMapper` + `ObjectMapper`（v4 C1 Jackson）
- [ ] 字段：
  - `Cache<String, McpSecurityConfigView> viewCache`（key="singleton", TTL=10min, size=1）
  - `volatile List<Pattern> patternsCache`（编译产物，DCL）
- [ ] `get()`：`viewCache.get("singleton", k -> loadFromDb())`；DB 为空 / 反序列化失败 → `McpSecurityConfigView.defaults()`
- [ ] `patterns()`：DCL 模式，命中 `patternsCache` 直接返回；miss 时 `Pattern.compile` 所有 `get().sensitiveArgPatterns()`
- [ ] `invalidate()`：`viewCache.invalidate("singleton")` + `patternsCache = null`（清两层缓存）
- [ ] 反序列化用 `objectMapper.readValue(json, McpSecurityConfigView.class)`（v4 C1）
- [ ] **McpAdminService** 注入此 accessor：
  - `getSecurityConfig()` → `accessor.get()`
  - `updateSecurityConfig(view)` → tx UPDATE jsonb 后调 `accessor.invalidate()`
  - 移除 service 自带的 `securityConfigCache` 字段（避免双写）

**验证**：`mvn compile && mvn test -Dtest=McpSecurityConfigAccessorTest`（覆盖：view 缓存命中 / patterns DCL / invalidate 清双缓存 / DB 空 fallback defaults / Jackson 反序列化失败 fallback）

### Step 5.1: 改造 `McpSecurityGuard`（v3 修复 1.7 + v4 B5 编译产物）

**文件**：`src/main/java/com/smart/rag/mcp/policy/McpSecurityGuard.java`

- [ ] **移除** 对 `McpSecurityProperties` 的注入（运行时不再读 yaml）
- [ ] **移除** 构造期 `compile(props.getSensitiveArgPatterns())` 字段初始化（B5：改用 accessor 实时缓存）
- [ ] **新增** 注入 `McpSecurityConfigAccessor`，调用：
  - `accessor.patterns()` 替代构造期编译的 `sensitivePatterns` 字段
  - `accessor.get()` 取 `defaultOutputCapChars` / `highRiskOutputCapChars`（替代 `props.getXxx()`）
- [ ] `guard()` 方法：`sensitiveArgHit(args)` 内部用 `accessor.patterns()`；`capAndMark(r, risk)` 内部用 `accessor.get().defaultOutputCapChars()` 等
- [ ] `McpSecurityProperties` 仅由 `McpAdminService.bootstrapFromYaml()` 在启动时读一次

**验证**：`mvn compile && mvn test -Dtest=McpSecurityGuardTest`

### Step 5.2: 改造 `McpDescriptionSanitizer`

- [ ] 检查是否从 `McpSecurityProperties` 读 `toolDescCharLimit`
- [ ] 若是，改为注入 `McpSecurityConfigAccessor`，调 `accessor.get().toolDescCharLimit()`
- [ ] 通过 accessor 模式避免循环依赖（design.md §10）
- [ ] 若不需要 toolDescCharLimit，跳过此 Step

**验证**：`mvn compile && mvn spring-boot:start`（启动不报循环依赖）

### Step 5.3: 改造 `McpToolPolicy`（v4：本期保留，Phase 8 删除）

> **v4 决策**：v3 说"逐步废弃但不删除"是模糊表述。v4 明确——本期 Phase 5 保留 `McpToolPolicy`（因 `McpSecurityGuard.risk()` 仍读它，risk 字段 DB 化留给后续 task），Phase 8 落地时与 `AllowlistMcpToolFilter` 一起删除。

- [ ] Phase 5：`McpToolPolicy` 保持不变（`McpSecurityGuard.policy.risk(name)` 仍读 yaml `mcp.policy.tools.<name>.risk`）
- [ ] Phase 8：与 `AllowlistMcpToolFilter` 一起删除（详见 Phase 8）

### Step 5.4: 改造 `McpHealthIndicator`

- [ ] 复用现有 `McpHealthIndicator`，从 `registryRead.list()` 读各 server 的 initError，聚合输出
- [ ] Phase 4 的 `GET /api/admin/mcp/health` 直接 delegate 给 HealthIndicator

**验证**：`mvn compile && mvn test -Dtest=McpHealthIndicatorTest`

---

## Phase 6: 配置 + 文档

### Step 6.1: 更新配置文件

**文件**：`src/main/resources/application.yml`

- [ ] 新增 `app.security.ssrf.allowed-ports` 配置（从 `app.llm.byok.ssrf.allowed-ports` 迁移，保留向后兼容）
- [ ] 新增 `app.security.crypto.master-key` 配置（从 `app.llm.crypto.master-key` 迁移）
- [ ] 新增 `app.mcp.strict-tool-filter: true`（默认开启 strict 模式）
- [ ] **保留** `app.mcp.security.*` yaml 配置（仅 bootstrap 使用，导入 DB 后运行时不读）

**文件**：`src/main/resources/application-dev.yml`

- [ ] 可选：`app.mcp.strict-tool-filter: false`（dev 模式允许未入库工具）

### Step 6.2: 更新 README / 文档

- [ ] `docs/API-DOCS.md` 添加 MCP 管理 API 文档（17 端点 + 1 health）
- [ ] `docs/SECURITY.md` 添加 Bearer Token 加密存储说明
- [ ] README 添加 `V17` 迁移说明

---

## Phase 7: 测试

### Step 7.1: 单元测试

> **v4 调整**：测试断言对应 v4 B1 异常替换；`DatabaseToolFilterTest` 覆盖合并后的单 Bean（不再有 `LenientDatabaseToolFilter`）。

| 测试类 | 重点覆盖 |
|---|---|
| `McpAdminServiceTest` | CRUD / 软失败 init_error / @Version 冲突抛 `ClientException(OPTIMISTIC_LOCK_CONFLICT)` / reconnect 限流抛 `ClientException(RATE_LIMITED)` / 缓存失效 / bootstrapFromYaml / initFromDb fail-soft（`RemoteException` 转入 init_error 占位） |
| `McpServerRegistryImplTest` | AtomicReference CAS 成功路径 / 并发场景（10 线程 addServer 不丢）/ 占位 server（client=null）/ 版本号递增 / `@PreDestroy` 关闭 |
| `McpClientFactoryTest` | SSRF 拒绝 / bearer token 解密注入 / initialize 失败抛 `RemoteException(MCP_SERVER_UNREACHABLE)` |
| `DatabaseToolFilterTest` | strict 模式（未入库 deny）/ lenient 模式（未入库 allow，`app.mcp.strict-tool-filter=false`）/ 入库 enabled=true/false 三态 / 缓存命中 |
| `McpServerToolCallbacksAdapterTest`（v4 B2 新增） | adapter.toolCallbacks() 跳过 initError server / filter + prefix 正确应用 |
| `McpSecurityConfigAccessorTest`（v4 B5 升级） | view 缓存命中 / patterns DCL 编译 / invalidate 清双缓存 / DB 空 fallback defaults / Jackson 反序列化失败 fallback |
| `AdminAuditAspectTest` | SpEL resourceIdExpr 解析（成功 + 失败 fallback，包括 `#result.id`）/ sensitiveFields 脱敏 / SUCCESS+FAILURE 路径 / `CallerRunsPolicy` 行为（队列满 caller 同步执行，不丢数据） |
| `SecretCipherTest` / `HostSafetyValidatorTest` | 迁移后回归 |
| `OptimisticLockerRegressionTest`（v4 B3 新增） | 验证现有实体（如有 `@Version`）在 `OptimisticLockerInnerInterceptor` 注册后正常工作 |

### Step 7.2: 集成测试

| 测试类 | 重点覆盖 |
|---|---|
| `McpAdminControllerTest` | 403 权限 / 200 成功响应 / @Version 冲突返回 409 / RATE_LIMITED 返回 429 / `@AdminAudit` 切入并写入审计表 |
| `McpAdminServiceBootstrapTest` | DB 空 → bootstrapFromYaml → 后续启动跳过 bootstrap |
| `McpAdminServiceReconnectTest` | 限流 / 占位 server / init_error 流转 |
| `BearerTokenUpdateFlowTest` | updateBearerToken → client 重建 → registryAdmin.replaceServer → init_error 清空 |

### Step 7.3: 并发测试（v3 新增）

| 测试类 | 重点覆盖 |
|---|---|
| `McpServerRegistryConcurrencyTest` | `CompletableFuture` 10 线程并发 addServer / removeServer / replaceServer，验证 snapshot 一致性（最终状态可推理） |
| `McpAdminServiceConcurrentUpdateTest` | 两线程并发 update 同一 server（带相同 version）→ 验证一个成功一个抛 `ClientException(OPTIMISTIC_LOCK_CONFLICT)` |

### Step 7.4: ArchUnit 测试

- [ ] 更新 `McpDependencyRulesTest`：MCP admin 包不依赖 LLM 包
- [ ] 新增 `AuditDependencyRulesTest`：`infrastructure/audit/` 不依赖任何业务模块
- [ ] 新增 `SecurityDependencyRulesTest`：`infrastructure/security/` 不依赖任何业务模块

### Step 7.5: 全量测试

**验证**：`mvn clean verify`

---

## Phase 8: 清理废弃代码（v4 新增）

> **v4 C2 / Step 5.3 决策落地**：Phase 3-7 完成后，`AllowlistMcpToolFilter` + `McpToolPolicy` 已被 DB 链路完全取代。本期最后一步删除它们（v3 模糊地说"逐步废弃但不删除"，v4 明确为"本期删除"）。

### Step 8.1: 删除 `AllowlistMcpToolFilter`

**删除文件**：
- `src/main/java/com/smart/rag/mcp/config/AllowlistMcpToolFilter.java`
- `src/test/java/com/smart/rag/mcp/config/AllowlistMcpToolFilterTest.java`

**前置检查**：
- [ ] `grep -r "AllowlistMcpToolFilter" src/main/java` 确认无引用（除文件自身）
- [ ] `grep -r "McpToolFilter" src/main/java` 确认 `McpClientTransportConfiguration.syncMcpToolCallbackProvider` 的 `@Autowired(required = false) McpToolFilter` 现在能解析到 `DatabaseToolFilter`（唯一 Bean）

### Step 8.2: 删除 `McpToolPolicy`（如不再被 `McpSecurityGuard` 引用）

> **前置条件**：Phase 5.1 已把 `McpSecurityGuard.risk()` 改为读 DB（per-tool `McpToolConfig.risk`），否则**不能**删除 `McpToolPolicy`。
>
> 如果 `McpSecurityGuard.risk()` 仍读 `McpToolPolicy`（因 risk 字段 DB 化留给后续 task），Phase 8 **跳过 Step 8.2**，保留 `McpToolPolicy` 到后续 task。

**删除文件**（仅在 risk 已 DB 化时）：
- `src/main/java/com/smart/rag/mcp/policy/McpToolPolicy.java`
- `src/main/java/com/smart/rag/mcp/policy/McpToolRule.java`（如存在）
- 对应 test 文件
- yaml 配置 `mcp.policy.tools.*`（在 application.yml / application-dev.yml 中删除，仅留注释指明"已迁移到 mcp_tool_config 表"）

**前置检查**：
- [ ] `grep -r "McpToolPolicy" src/main/java` 确认只有 `McpSecurityGuard`（如未改造）/ `McpAuthorizer`（如还在用）/ `McpDescriptionSanitizer`（如还在用）引用
- [ ] 若 `McpSecurityGuard` / `McpAuthorizer` / `McpDescriptionSanitizer` 仍注入 `McpToolPolicy`，**跳过此 Step**

### Step 8.3: 删除 `McpSecurityProperties` / `McpClientTransportProperties`（可选，激进）

> **风险评估**：Phase 5 完成后，这两个 Properties 都只在 `McpAdminService.bootstrapFromYaml()` 启动时读一次。删除它们等于关闭"yaml 兜底 bootstrap"能力——首次启动时如果 DB 为空，无 yaml 可导入，ADMIN 必须手工经 REST API 配置。
>
> **v4 默认决策：保留**——保留 yaml bootstrap 兜底能力，方便首次部署。删除留给后续运维 task。

- [ ] **本期不删**，仅在 Javadoc 标注 `@Deprecated(forRemoval = true, since = "v4")` + 注释"运行时不再注入，仅 bootstrap 用，未来可能移除"

### Step 8.4: 删除 `McpBearerAuthRequestCustomizer` Bean（已在 Phase 2.6 删除）

- 已在 Step 2.6 处理，此处仅回归检查：`grep -r "mcpBearerAuthRequestCustomizer" src/main/java` 应无结果

**验证**：`mvn clean verify`（确认全量测试通过）

---

## Rollback Points

| 完成节点 | 回滚策略 |
|---|---|
| Phase 0.0 后（拦截器 + 错误码） | 移除 `OptimisticLockerInnerInterceptor`、移除 `ClientErrorCode.OPTIMISTIC_LOCK_CONFLICT`/`CONFLICT` |
| Phase 0.5 后（迁移已执行） | `flyway undo` 或手动 DROP 4 张表；恢复 HostSafetyValidator/SecretCipher 原位置 |
| Phase 2 后（Registry 改造） | 恢复 `McpServerRegistryImpl` 旧版本（含 `@PostConstruct init`），保留新接口文件 |
| Phase 3 后（Service 上线） | McpAdminController 用 `@ConditionalOnProperty(prefix="app.mcp", name="admin-enabled", havingValue="false")` 关闭 |
| Phase 5 后（既有组件改造） | 恢复 McpSecurityGuard/McpDescriptionSanitizer 的 yaml 注入 |
| Phase 8 后（清理废弃代码） | git revert Phase 8 commit 即可（不涉及业务逻辑改动，仅删除操作） |

---

## 注意事项（v4 汇总）

| # | 约束 |
|---|---|
| 1 | DB 为唯一事实源；yaml 仅 bootstrap（首次启动 + DB 空） |
| 2 | Bearer Token 进 DB（per-server 加密存储），更新触发该 server client 重建 |
| 3 | `McpSecurityProperties` / `McpClientTransportProperties` 运行时不再被任何 Bean 注入（v4 C8 对称降级） |
| 4 | 单级 Caffeine 缓存（无 Redis 工具缓存层） |
| 5 | TransactionTemplate 显式事务（无 `@Transactional`） |
| 6 | 所有 SQL 在 XML 中（无注解 SQL） |
| 7 | `McpServerRegistryImpl` 用 AtomicReference 快照模式（无直接 mutate） |
| 8 | Registry 不依赖 Service；初始化在 `McpAdminService.run()` |
| 9 | `McpAdminService` 注入接口（`McpServerRegistryAdmin`）不注入 Impl |
| 10 | DatabaseToolFilter 单 Bean + strict/lenient 配置项（v4 C2 合并）；`isToolEnabled` 三态返回 `Boolean`（null = 未入库） |
| 11 | 软失败：DB commit 后运行时失败 → 写 `init_error`，不回滚 DB |
| 12 | 占位 server：registry 中保留带 `initError` 的 McpServer，不直接 remove |
| 13 | 乐观锁：所有写操作支持 `@Version` + `OptimisticLockerInnerInterceptor` 已注册（v4 B3），并发冲突抛 `ClientException(OPTIMISTIC_LOCK_CONFLICT)` 返回 409 |
| 14 | 重连限流：per-serverId 30s 内最多 1 次（service 层入口限流），抛 `ClientException(ClientErrorCode.RATE_LIMITED)` 返回 429 |
| 15 | 通用审计：`@AdminAudit` AOP，`CallerRunsPolicy` 不丢审计（v4 C3），敏感字段脱敏，跨模块复用 |
| 16 | Bearer Token 变更 / 任何 ADMIN 写操作 → `toolCallbackProvider.invalidateCache()` |
| 17 | 仅 GET / POST 方法（R5.1） |
| 18 | 所有 MCP 写 API 仅 ADMIN 角色（R5.2） |
| 19 | Phase 0 下沉 HostSafetyValidator + SecretCipher 一起做（Bearer 加密前置） |
| 20 | 类名通用化：`BaseUrlValidator→HostSafetyValidator`，`ApiKeyCipher→SecretCipher` |
| 21 | **v4 B1**：不分裂枚举。`RATE_LIMITED` 复用 `ClientErrorCode`，MCP init/reconnect/bearer-rebuild 失败用 `RemoteErrorCode.MCP_SERVER_UNREACHABLE`，新增 `ClientErrorCode.OPTIMISTIC_LOCK_CONFLICT(100014)` |
| 22 | **v4 B2**：core `McpServer` 接口零 starter 依赖铁律——`toolCallbacks(...)` 走 `mcp/mcpclient/McpServerToolCallbacksAdapter` runtime adapter，不进 core |
| 23 | **v4 B4**：`serverId` 系统派生（`McpToolUtils.format(serverInfo.name)`），ADMIN 不可改；`CreateServerRequest` 无 `serverId` 字段 |
| 24 | **v4 B5**：`McpSecurityConfigAccessor.patterns()` DCL 缓存编译产物，admin 更新触发 `invalidate()` |
| 25 | **v4 C1**：统一 Jackson（`ObjectMapper`），不用 Fastjson |
| 26 | **v4 C5**：yaml `bearerTokens` host 粒度限制（同 host 多 server 共享 token），bootstrap 后 ADMIN 经 REST API per-server 覆盖 |
| 27 | **v4 C7**：`@AdminAudit` 方法之间禁止 `this.xxx()` 直接调用（Spring AOP 自调用盲区） |
| 28 | **v4 Phase 8**：本期最后一步删除 `AllowlistMcpToolFilter`（及 `McpToolPolicy` 如 risk 已 DB 化）；不再保留为死代码 |
