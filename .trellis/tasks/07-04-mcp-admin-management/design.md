# Technical Design: MCP Admin Management (v4 — 评审修复版)

## 修订说明

### v4 修订（基于设计评审反馈）

v4 在 v3 基础上修复 5 个 BLOCKER + 8 个 CONCERN。下表中**所有 v3 与 v4 冲突的代码示例，一律以 v4 决策为准**。

| # | v3 缺陷 | v4 修复 |
|---|---|---|
| **B1** | 大量引用不存在的 `ServiceErrorCode.RATE_LIMITED` / `REMOTE_INIT_FAILED` / `OPTIMISTIC_LOCK_CONFLICT` | **不分裂枚举**，按语义替换：① `RATE_LIMITED` → `ClientException(ClientErrorCode.RATE_LIMITED)`（已有 100005）；② MCP init / reconnect / bearer-rebuild 失败 → `RemoteException(RemoteErrorCode.MCP_SERVER_UNREACHABLE)`（已有 302001）；③ 新增 `ClientErrorCode.OPTIMISTIC_LOCK_CONFLICT(100014, "资源版本冲突，请刷新重试")` + `ClientException` + HTTP 409 |
| **B2** | 计划在 `McpServer` 接口加 `toolCallbacks(...)` 违反 core 包"零 starter 依赖"铁律（`ToolCallback` 是 Spring AI 类型） | core 不动。在 `mcp/mcpclient/` 加 runtime 层 adapter 接口 `McpServerToolCallbacksAdapter`，由 `McpServerImpl`（runtime 已依赖 Spring AI）实现；`SyncMcpToolCallbackProvider` 注入 adapter + registry |
| **B3** | `@Version` 注解需 `OptimisticLockerInnerInterceptor` 才生效；当前 `MyBatisPlusConfig` 只注册 `PaginationInnerInterceptor` | Phase 0 加 Step 0.0：注册 `OptimisticLockerInnerInterceptor` 并回归现有实体 |
| **B4** | v3 让 ADMIN 在 `CreateServerRequest` 指定 `serverId`，但 prefix generator 仍从握手派生，不一致 → filter 失效 | `serverId` 系统派生：`McpToolUtils.format(initializeResult.serverInfo().name())`。`CreateServerRequest` 移除 `serverId` 字段；`createServer` 流程：先 INSERT（serverId 暂 NULL）→ 握手 → UPDATE 回填；握手失败用合成 id `unreachable-<rowId>` + `init_error` |
| **B5** | `McpSecurityGuard` 在构造期一次性编译 `sensitivePatterns`，切 DB 后无法热更新 | `McpSecurityConfigAccessor` 升级：暴露 `List<Pattern> patterns()`（编译产物缓存，DCL），admin updateSecurityConfig 触发 `invalidate()` 同步清缓存 |
| **C1** | JSON 库未指定（design 用 Fastjson 语法 `JSON.parseObject`） | 统一 Jackson：`objectMapper.readValue` / `objectMapper.writeValueAsString` |
| **C2** | 三 Filter 共存歧义；`AllowlistMcpToolFilter` 在 v3 后成死代码 | strict/lenient 合并为单 Bean，`app.mcp.strict-tool-filter` 控制未入库默认；`McpAdminService.isToolEnabled` 返回 `Boolean`（三态：null=未入库）；`AllowlistMcpToolFilter` + `McpToolPolicy` 在 implement 最后一步删除 |
| **C3** | 审计队列 `DiscardOldestPolicy` 满时丢**最旧**行，违反审计合规 | 改 `CallerRunsPolicy`（业务线程同步执行写入，自然 backpressure，不丢数据） |
| **C4** | `ApplicationRunner` 启动窗口期未文档化 | design §Runtime Behavior 明确：Bean 装配完成 → `run()` 完成期间（典型 < 1s），registry 为空，MCP 工具数为 0；预期行为 |
| **C5** | Bearer Token bootstrap 的 yaml host 粒度限制未文档化 | design §Bearer Token Bootstrap 明确：yaml `bearerTokens: Map<host,token>`，同 host 多 path 共享 token；bootstrap 后 ADMIN 经 REST API per-server 覆盖 |
| **C6** | PRD AC2 `allowlist` 字段与 `McpToolConfig` 实体不一致 | PRD AC2 同步：`allowlist` 字段改为 per-tool `enabled`（已被取代） |
| **C7** | `@AdminAudit` 自调用盲区未约束 | 当前 design 所有 `@AdminAudit` 方法之间无相互调用，不受影响；加硬约束："@AdminAudit 方法之间禁止 `this.xxx()` 直接调用" |
| **C8** | `McpClientTransportProperties` 降级待遇未明写 | 与 `McpSecurityProperties` 对称：**两者都降级为 bootstrap**（仅 `McpAdminService.run()` 启动时读一次，运行时无 Bean 注入）；`McpClientTransportConfiguration.mcpSyncClients()` Bean 删除 |

### v3 在 v2 基础上修复以下缺陷（保留供历史参考）

| # | v2 缺陷 | v3 修复 |
|---|---|---|
| 1.1 | 并发可见性：`ConcurrentHashMap` 直接 mutate → 重连期间请求可能拿到正在异步关闭的旧 client | 改用 `AtomicReference<ImmutableMap<ServerId, McpServer>>` 快照模式（对齐 `LlmClientRegistry.snapshotRef`） |
| 1.2 | 循环依赖：`McpServerRegistryImpl.@PostConstruct` 注入 `McpAdminService` ↔ 反向注入 | Registry 不依赖任何 Service；初始化完全挪到 `McpAdminService.run()`（`ApplicationRunner`，所有 Bean 就绪后执行） |
| 1.3 | 接口污染：`McpAdminService` 注入 `McpServerRegistryImpl` 具体类 | 抽取 `McpServerRegistryAdmin` 接口（`addServer/removeServer/replaceServer/currentVersion`），Impl 同时实现 `McpServerRegistry` + `McpServerRegistryAdmin` |
| 1.4 | 安全洞：`DatabaseToolFilter` "未入库默认允许" | 默认 **deny** + `app.mcp.strict-tool-filter` 开关；未入库工具需经 `refreshTools` 入库（默认 enabled=false）后由 ADMIN 显式启用 |
| 1.5 | 软失败：DB commit 后运行时失败无补偿 | `mcp_server_config.init_error` 列记录失败原因，不回滚 DB；registry 中保留**占位 McpServer**（`initError` 非空）而非直接移除 |
| 1.6 | `SyncMcpToolCallbackProvider` 重构深度被低估 | 注入 `McpServerRegistry` 替代 `List<McpSyncClient>`；cache key 加 registry 版本号（`AtomicLong`），版本变更触发 callback cache 失效 |
| 1.7 | Bearer Token 双轨制 | DB 为唯一事实源；Bearer Token 加密存 `mcp_server_config.bearer_token_encrypted`（per-server 粒度），更新触发该 server client 重建 |
| 1.8 | 文档不一致（"两级"vs实际三级、init 注入矛盾、AC 无对应设计） | 全文术语统一；每条 AC 对应一节设计 |
| 2.1 | 三级缓存过度设计 | 单级 Caffeine + TTL（`expireAfterWrite=10min`）+ ADMIN 操作主动 invalidate；**删除 Redis 层** |
| 2.3 | `McpSecurityConfig` EAV 反模式 | 改为 jsonb 单行表（`id=1, config_json jsonb`），整体读写 |
| 缺失-1 | AC R4.3 承诺审计日志但无设计 | 新建通用 `infrastructure/audit/` 包：`@AdminAudit` 注解 + AOP + `admin_audit_log` 表，**MCP/LLM/RAG 任何模块均可复用** |
| 缺失-2 | 无乐观锁 | `McpServerConfig` / `McpToolConfig` 加 `@Version`，并发冲突抛 `OptimisticLockingFailureException` |
| 缺失-3 | 重连无限流 | per-serverId 30s 内最多 1 次（Caffeine 限流器），超出返回 429 |
| 2.2 | Phase 0 范围蔓延（迁 `ApiKeyCipher`） | **保留**——这是 Bearer Token 进 DB 的前置条件：加密下沉到 `infrastructure/security`，重命名为通用类名（`SecretCipher` / `HostSafetyValidator`） |

---

## Architecture Overview

### Current Architecture

```
yaml配置 → @ConfigurationProperties → McpToolPolicy / McpSecurityProperties
                                          ↓
McpSyncClient（@Bean 静态创建）→ McpServerRegistryImpl（@PostConstruct 一次性 init）
                                          ↓
SyncMcpToolCallbackProvider（List<McpSyncClient> 静态注入）
                                          ↓
AllowlistMcpToolFilter ← McpToolPolicy.explicitlyAllowed()
                                          ↓
LLM请求
```

### Target Architecture (v3)

```
PostgreSQL
  ├── mcp_server_config      （含 bearer_token_encrypted, init_error, @Version）
  ├── mcp_tool_config        （含 @Version）
  ├── mcp_security_config    （jsonb 单行，id=1）
  └── admin_audit_log        （通用审计表）
                          ↓
                  McpAdminService
                  ├─ implements ApplicationRunner（启动初始化，打破循环依赖）
                  ├─ CRUD + 运行时控制
                  ├─ 单级 Caffeine 缓存（不再依赖 Redis 工具缓存层）
                  └─ 所有写方法标注 @AdminAudit
                          ↓
                  McpServerRegistryAdmin 接口（addServer/removeServer/replaceServer/currentVersion）
                          ↓
                  McpServerRegistryImpl
                  ├─ AtomicReference<ImmutableMap<ServerId, McpServer>> snapshotRef
                  ├─ 每次 mutate → 构建新 ImmutableMap → CAS → 异步关闭旧 client
                  └─ McpServerRegistry（只读 list/find）+ McpServerRegistryAdmin（写）双实现
                          ↓
                  McpClientFactory（动态创建/销毁 McpSyncClient）
                  ├─ 注入 HostSafetyValidator（SSRF 校验）
                  ├─ 注入 SecretCipher（解密 bearer token）
                  └─ per-server client，含 initialize 握手
                          ↓
                  DatabaseToolFilter（默认 deny + strict mode）
                  └─ 单级 Caffeine 缓存
                          ↓
                  SyncMcpToolCallbackProvider（注入 Registry，cache 版本化）
                          ↓
                  @AdminAudit AOP → admin_audit_log（异步）
                          ↓
                  LLM 请求
```

---

## Data Model

### McpServerConfig Entity

```java
@TableName("mcp_server_config")
public class McpServerConfig {

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * ServerId.value()，唯一标识。
     * <p>
     * <b>v4：系统派生，ADMIN 不可改</b>。由 {@code McpToolUtils.format(initializeResult.serverInfo().name())}
     * 在握手成功后回填。这保证 serverId 与 {@code McpToolNamePrefixGenerator} 派生的工具前缀同源
     * （都是 {@code McpToolUtils.format(serverInfo.name())}），从而 {@code DatabaseToolFilter.selectByPrefixedName}
     * 查询键 1:1 匹配。
     * <p>
     * INSERT 时允许 NULL（仅握手未完成的临时态）；UPDATE 回填后非 NULL。DB 列加
     * {@code CHECK (server_id IS NOT NULL OR init_error IS NOT NULL)} 约束（要么握手成功有 serverId，
     * 要么握手失败有 init_error 占位）。
     * <p>
     * 握手失败时用合成 id {@code unreachable-<rowId>}（与现行 {@code McpServerRegistryImpl.init()} 一致）。
     */
    private String serverId;

    /** MCP Server URL（经 HostSafetyValidator SSRF 校验） */
    private String url;

    /** 展示名（ADMIN 可改，仅用于 UI 展示，不影响 serverId / 工具前缀） */
    private String name;

    private String description;

    /** 是否启用（禁用 → 级联禁用工具） */
    private Boolean enabled;

    /** 系统启动时自动连接 */
    private Boolean autoConnect;

    /**
     * Bearer Token（加密存储）。
     * 由 {@link com.smart.rag.infrastructure.security.SecretCipher} 加密，
     * McpClientFactory 创建 transport 时解密注入。
     * 更新触发该 server client 重建（见 §Bearer Token 处理）。
     */
    private String bearerTokenEncrypted;

    /**
     * 运行时初始化失败原因（软失败语义，R6.8）。
     * 非 null 表示该 server 上次 client 创建/握手失败；registry 中仍保留占位 McpServer，
     * 调用时返回 "MCP链接断开正在重新连接"（R6.2）。下次 reconnect 成功后清空。
     */
    private String initError;

    /** 上次成功连接时间 */
    private LocalDateTime lastConnectedAt;

    /** 乐观锁版本（R6.9） */
    @Version
    private Long version;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
```

### McpToolConfig Entity

```java
@TableName("mcp_tool_config")
public class McpToolConfig {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 关联 McpServerConfig.serverId */
    private String serverId;

    /** 原始工具名（未前缀） */
    private String toolName;

    /** 前缀全名（knowledge_search），用于 allowlist / policy 查找 */
    private String prefixedToolName;

    private String description;

    /** 是否启用 */
    private Boolean enabled;

    /** McpIntent 枚举值（GENERAL_TOOL / RETRIEVAL / DEEP_RETRIEVAL / DIRECT_ANSWER） */
    private String intent;

    /** low / high */
    private String risk;

    /** admin 可信描述覆盖（替代远端不可信 description） */
    private String descriptionOverride;

    /** 乐观锁版本（R6.9） */
    @Version
    private Long version;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
```

### McpSecurityConfig Entity（jsonb 单行表，v3 修订）

**v3 改动**：从 EAV 多行表（`config_key`/`config_value`）改为 jsonb 单行表。丧失类型安全但避免 EAV 反模式，整体读写、原子更新。

```java
@TableName("mcp_security_config")
public class McpSecurityConfig {

    /** 固定为 1，单行表约定 */
    @TableId(type = IdType.INPUT)
    private Long id;

    /** JSONB 配置文档 */
    private String configJson;

    private LocalDateTime updatedAt;

    /** 反序列化得到的强类型视图（不持久化） */
    @TableField(exist = false)
    public McpSecurityConfigView view() {
        return JSON.parseObject(configJson, McpSecurityConfigView.class);
    }
}

/** 配置文档的强类型视图（运行时反序列化使用） */
public record McpSecurityConfigView(
    List<String> sensitiveArgPatterns,
    Integer defaultOutputCapChars,
    Integer highRiskOutputCapChars,
    Integer toolDescCharLimit
) {
    public static McpSecurityConfigView defaults() {
        return new McpSecurityConfigView(
            List.of(), 4000, 1000, 1024
        );
    }
}
```

**SQL**：

```sql
CREATE TABLE mcp_security_config (
    id              BIGINT PRIMARY KEY DEFAULT 1,
    config_json     JSONB NOT NULL,
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT mcp_security_config_single_row CHECK (id = 1)
);
```

### AdminAuditLog Entity（通用审计表，新增）

**位置**：`infrastructure/audit/entity/AdminAuditLog.java`（不绑定 MCP 模块）

```java
@TableName("admin_audit_log")
public class AdminAuditLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 操作者 user id（NULL = 匿名/系统） */
    private Long operatorId;

    private String operatorName;

    /** 角色：ADMIN / USER / SYSTEM */
    private String operatorRole;

    /** 资源类型：mcp_server / mcp_tool / mcp_security / llm_model / rag_pipeline / ... */
    private String resourceType;

    /** 资源唯一标识（如 serverId、toolConfigId） */
    private String resourceId;

    /** 操作类型：create / update / delete / enable / disable / reconnect / refresh_tools / update_bearer_token / ... */
    private String action;

    /** 请求参数（jsonb，敏感字段已脱敏） */
    private String requestPayload;

    /** SUCCESS / FAILURE */
    private String resultStatus;

    /** 失败时错误码 */
    private String errorCode;

    /** 失败时错误消息 */
    private String errorMessage;

    private String ipAddress;
    private String userAgent;

    /** 执行耗时（毫秒） */
    private Integer durationMs;

    private LocalDateTime createdAt;
}
```

**SQL**：

```sql
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

### Mapper 接口（MyBatis-Plus，SQL 全在 XML）

```java
@Mapper
public interface McpServerConfigMapper extends BaseMapper<McpServerConfig> {
    McpServerConfig selectByServerId(@Param("serverId") String serverId);
    List<McpServerConfig> selectAllEnabled();
    int updateInitError(@Param("serverId") String serverId, @Param("initError") String initError);
    int updateBearerToken(@Param("serverId") String serverId,
                          @Param("bearerTokenEncrypted") String bearerTokenEncrypted,
                          @Param("version") Long version);
}

@Mapper
public interface McpToolConfigMapper extends BaseMapper<McpToolConfig> {
    List<McpToolConfig> selectByServerId(@Param("serverId") String serverId);
    List<McpToolConfig> selectAllEnabled();
    int updateEnabledByServerId(@Param("serverId") String serverId, @Param("enabled") boolean enabled);
    int batchUpdateEnabled(@Param("ids") List<Long> ids, @Param("enabled") boolean enabled);
    int deleteByServerId(@Param("serverId") String serverId);
    /** 按 prefixedToolName 查（DatabaseToolFilter 用） */
    McpToolConfig selectByPrefixedName(@Param("prefixedToolName") String prefixedToolName);
}

@Mapper
public interface McpSecurityConfigMapper extends BaseMapper<McpSecurityConfig> {
    /** 单行表，永远 id=1 */
    McpSecurityConfig selectSingleton();
    int updateConfigJson(@Param("configJson") String configJson);
}

@Mapper
public interface AdminAuditLogMapper extends BaseMapper<AdminAuditLog> {
    List<AdminAuditLog> selectByResource(@Param("resourceType") String resourceType,
                                          @Param("resourceId") String resourceId,
                                          @Param("limit") int limit);
}
```

### XML SQL 文件

- `src/main/resources/mapper/McpServerConfigMapper.xml`
- `src/main/resources/mapper/McpToolConfigMapper.xml`
- `src/main/resources/mapper/McpSecurityConfigMapper.xml`
- `src/main/resources/mapper/AdminAuditLogMapper.xml`

所有自定义 SQL 均在 XML 中编写，**不使用注解 `@Select`/`@Insert`**。

---

## Core Components

### 1. McpServerRegistry 接口拆分（v3 修复 1.3）

**问题**：原 `McpServerRegistry` 只有 `list()/find()`，加入 `addServer/removeServer` 会把"管理员写关注点"混入"内核读关注点"。

**方案**：抽 `McpServerRegistryAdmin` 接口。`McpServerRegistryImpl` 同时实现两个接口。

```java
// mcp/core/McpServerRegistry.java（保持不变，内核只读契约）
public interface McpServerRegistry {
    List<McpServer> list();
    Optional<McpServer> find(ServerId id);
}

// mcp/runtime/McpServerRegistryAdmin.java（新增，管理员写契约）
public interface McpServerRegistryAdmin {
    /**
     * 新增或替换 server（原子快照切换）。
     * 若该 serverId 已存在，旧 client 异步关闭（fire-and-forget）。
     */
    void addServer(McpServerConfig config, @Nullable McpSyncClient client, @Nullable String initError);

    /**
     * 仅移除 server 注册（不销毁 client，client 由调用方管理）。
     */
    void removeServer(ServerId id);

    /**
     * 替换 server（语义等价于 removeServer + addServer，但单次原子切换）。
     * 用于 reconnect 场景。
     */
    void replaceServer(McpServerConfig config, McpSyncClient newClient);

    /**
     * 当前 registry 版本号（每次 mutate 递增）。
     * SyncMcpToolCallbackProvider 用作 cache key 一部分，检测到版本变更即失效内部缓存。
     */
    long currentVersion();
}
```

### 2. McpServerRegistryImpl（v3 改用快照模式，修复 1.1）

**对齐参考**：`LlmClientRegistry.snapshotRef: AtomicReference<RegistrySnapshot>`

**关键设计**：
- `AtomicReference<ImmutableMap<ServerId, McpServer>>` —— 整体快照原子替换
- 每次 add/remove/replace → 构建新 ImmutableMap（copyOf + put/remove）→ CAS → 旧 snapshot 中不再存在的 client 异步关闭
- **不依赖任何 Service**（修复 1.2）—— 无 `@PostConstruct` init，初始化由 `McpAdminService.run()` 负责
- **保留占位 McpServer**（修复 1.5）—— 重连失败时不 remove，而是 replace 为带 `initError` 的占位 server

```java
@Component
public class McpServerRegistryImpl implements McpServerRegistry, McpServerRegistryAdmin {

    private static final Logger log = LoggerFactory.getLogger(McpServerRegistryImpl.class);

    // 注入只读依赖（无 Service 依赖，修复 1.2）
    private final McpAuthorizer authorizer;
    private final McpCircuitBreakerRegistry circuitRegistry;
    private final FallbackEligibility fallbackEligibility;
    private final McpDescriptionSanitizer descriptionSanitizer;
    private final ObjectProvider<SyncMcpToolCallbackProvider> providerProvider;

    // 快照模式（对齐 LlmClientRegistry）
    private final AtomicReference<ImmutableMap<ServerId, McpServer>> snapshotRef =
            new AtomicReference<>(ImmutableMap.of());

    // 版本号（供 callback provider 检测变更）
    private final AtomicLong version = new AtomicLong(0L);

    // 异步关闭旧 client 的专用 executor（fire-and-forget，单线程足够）
    private final ExecutorService asyncCloseExecutor = createAsyncCloseExecutor();

    public McpServerRegistryImpl(McpAuthorizer authorizer,
                                  McpCircuitBreakerRegistry circuitRegistry,
                                  FallbackEligibility fallbackEligibility,
                                  McpDescriptionSanitizer descriptionSanitizer,
                                  ObjectProvider<SyncMcpToolCallbackProvider> providerProvider) {
        this.authorizer = authorizer;
        this.circuitRegistry = circuitRegistry;
        this.fallbackEligibility = fallbackEligibility;
        this.descriptionSanitizer = descriptionSanitizer;
        this.providerProvider = providerProvider;
    }

    // === McpServerRegistry（只读）===

    @Override
    public List<McpServer> list() {
        return List.copyOf(snapshotRef.get().values());
    }

    @Override
    public Optional<McpServer> find(ServerId id) {
        return Optional.ofNullable(snapshotRef.get().get(id));
    }

    // === McpServerRegistryAdmin（写，原子快照切换）===

    @Override
    public void addServer(McpServerConfig config, @Nullable McpSyncClient client, @Nullable String initError) {
        ServerId id = new ServerId(config.getServerId());
        McpServerImpl server = new McpServerImpl(id, client, authorizer, circuitRegistry,
                fallbackEligibility, providerProvider.getIfAvailable(), initError, descriptionSanitizer);

        ImmutableMap<ServerId, McpServer> oldSnapshot;
        ImmutableMap<ServerId, McpServer> newSnapshot;
        do {
            oldSnapshot = snapshotRef.get();
            newSnapshot = new ImmutableMap.Builder<ServerId, McpServer>()
                    .putAll(oldSnapshot)
                    .put(id, server)
                    .build();
        } while (!snapshotRef.compareAndSet(oldSnapshot, newSnapshot));

        // 异步关闭旧快照中被替换的 client
        McpServer previous = oldSnapshot.get(id);
        if (previous instanceof McpServerImpl oldImpl && oldImpl.hasClient()) {
            asyncCloseQuietly(oldImpl);
        }
        version.incrementAndGet();
        log.info("MCP server registered: id={} initError={}", id.value(),
                initError != null ? "present" : "null");
    }

    @Override
    public void removeServer(ServerId id) {
        ImmutableMap<ServerId, McpServer> oldSnapshot;
        ImmutableMap<ServerId, McpServer> newSnapshot;
        do {
            oldSnapshot = snapshotRef.get();
            if (!oldSnapshot.containsKey(id)) {
                return;
            }
            newSnapshot = ImmutableMap.filterKeys(oldSnapshot, k -> !k.equals(id));
        } while (!snapshotRef.compareAndSet(oldSnapshot, newSnapshot));

        McpServer removed = oldSnapshot.get(id);
        if (removed instanceof McpServerImpl oldImpl && oldImpl.hasClient()) {
            asyncCloseQuietly(oldImpl);
        }
        circuitRegistry.evict(id.value());
        version.incrementAndGet();
    }

    @Override
    public void replaceServer(McpServerConfig config, McpSyncClient newClient) {
        addServer(config, newClient, null);  // addServer 内部处理旧 client 异步关闭
    }

    @Override
    public long currentVersion() {
        return version.get();
    }

    @PreDestroy
    void destroy() {
        ImmutableMap<ServerId, McpServer> snapshot = snapshotRef.getAndSet(ImmutableMap.of());
        snapshot.values().forEach(s -> {
            if (s instanceof McpServerImpl impl && impl.hasClient()) {
                try { impl.closeQuietly(); } catch (Exception ignored) {}
            }
        });
        asyncCloseExecutor.shutdown();
    }

    private void asyncCloseQuietly(McpServerImpl server) {
        asyncCloseExecutor.submit(() -> {
            try {
                server.closeQuietly();
            } catch (Exception e) {
                log.warn("async close MCP server {} failed: {}", server.id().value(), e.getMessage());
            }
        });
    }

    private static ExecutorService createAsyncCloseExecutor() {
        return Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "mcp-async-close");
            t.setDaemon(true);
            return t;
        });
    }
}
```

### 3. McpClientFactory

**职责**：动态创建和销毁 `McpSyncClient`，按 server 配置构建 transport（含解密 bearer token 注入）。

```java
@Component
public class McpClientFactory {

    private final HostSafetyValidator urlValidator;        // 下沉后的通用 SSRF 校验
    private final SecretCipher secretCipher;                // 下沉后的通用加密器
    private final McpSecurityProperties securityProperties; // 仅读 request timeout 等非密配置

    /**
     * 从 McpServerConfig 创建 McpSyncClient（含 initialize 握手）。
     *
     * @throws ServiceException SSRF 校验失败 / transport 构建 / initialize 失败
     */
    public McpSyncClient createClient(McpServerConfig config) {
        // 1. SSRF 校验
        urlValidator.validate(config.getUrl());

        // 2. 解密 bearer token（per-server）
        String bearerToken = decryptBearerToken(config);

        // 3. 构建 transport（注入 bearer auth customizer，token baked in）
        HttpClientStreamableHttpTransport transport = buildTransport(config.getUrl(), bearerToken);

        // 4. 构建 client
        Duration timeout = parseTimeout(securityProperties.getRequestTimeout());
        McpSyncClient client = McpClient.sync(transport)
                .requestTimeout(timeout)
                .clientInfo(new McpSchema.Implementation("smart-rag", "1.0.0"))
                .build();

        // 5. 握手（fail-fast，由调用方捕获并写入 init_error）
        client.initialize();
        return client;
    }

    /** 安全关闭 client（try/catch，不抛） */
    public void destroyClient(McpSyncClient client) {
        try {
            client.close();
        } catch (Exception e) {
            log.warn("MCP client close failed: {}", e.getMessage());
        }
    }

    private String decryptBearerToken(McpServerConfig config) {
        if (config.getBearerTokenEncrypted() == null || config.getBearerTokenEncrypted().isBlank()) {
            return null;
        }
        return secretCipher.decrypt(config.getBearerTokenEncrypted());
    }

    private HttpClientStreamableHttpTransport buildTransport(String url, @Nullable String bearerToken) {
        HttpClientStreamableHttpTransport.Builder builder = HttpClientStreamableHttpTransport
                .builder(url)
                .openConnectionOnStartup(false);
        if (bearerToken != null && !bearerToken.isBlank()) {
            builder.httpRequestCustomizer((rb, method, uri, body, ctx) ->
                    rb.header("Authorization", "Bearer " + bearerToken));
        }
        return builder.build();
    }
}
```

### 4. McpAdminService（v3 完整修复）

> **v4 注意**：以下 v3 代码示例有几处被 v4 修订覆盖，实施时以 v4 章节为准：
> - `createServer` / `enableServer` / `reconnectServer` / `updateBearerToken` 中的 `ServiceException(REMOTE_INIT_FAILED)` → `RemoteException(MCP_SERVER_UNREACHABLE)`
> - `reconnectServer` 的 `ServiceException(RATE_LIMITED)` → `ClientException(ClientErrorCode.RATE_LIMITED)`
> - 所有 `OPTIMISTIC_LOCK_CONFLICT` → `ClientException(ClientErrorCode.OPTIMISTIC_LOCK_CONFLICT)`
> - `CreateServerRequest` 无 `serverId` 字段（B4）；`createServer` 流程改为：INSERT(NULL) → 握手 → UPDATE 回填 serverId
> - `@AdminAudit(resourceIdExpr = "#request.serverId")` 改为 `resourceIdExpr = "#result.id"` 或 `"#request.url"`（serverId 在请求时不存在）
> - `McpSecurityConfigView` 的 JSON 序列化用 `ObjectMapper`（C1），不用 Fastjson
> - `McpAdminService` 移除 `securityConfigCache`，统一由 `McpSecurityConfigAccessor` 管理（含 patterns 编译产物，B5）
> - `McpAdminService.isToolEnabled` 返回 `Boolean`（三态，C2）

**关键设计决策**：
- **`implements ApplicationRunner`** —— 启动初始化逻辑放 `run()`，打破与 Registry 的循环依赖（修复 1.2）
- **注入 `McpServerRegistryAdmin` 接口** —— 不再注入 Impl（修复 1.3）
- **软失败语义** —— DB commit 后运行时失败 → catch → UPDATE `init_error`，不回滚（修复 1.5）
- **单级 Caffeine 缓存** —— 不再有 Redis 中间层（修复 2.1）
- **重连限流** —— Caffeine `Cache<serverId, Long> cooldown` 实现 30s 限流（修复 缺失-3）
- **所有写方法标注 `@AdminAudit`** —— AOP 自动记录到 `admin_audit_log`

```java
@Service
public class McpAdminService implements ApplicationRunner {

    private final McpServerConfigMapper serverConfigMapper;
    private final McpToolConfigMapper toolConfigMapper;
    private final McpSecurityConfigMapper securityConfigMapper;
    private final TransactionTemplate txTemplate;
    private final McpClientFactory clientFactory;
    private final McpServerRegistryAdmin registryAdmin;
    private final McpServerRegistry registryRead;   // 只读视图
    private final HostSafetyValidator urlValidator;
    private final SecretCipher secretCipher;
    private final SyncMcpToolCallbackProvider toolCallbackProvider;
    private final McpSecurityProperties bootstrapProps;  // 仅启动时用

    // 单级 Caffeine 缓存（修复 2.1）
    private final Cache<String, List<McpToolConfig>> toolListCache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(10))
            .maximumSize(100)
            .build();
    private final Cache<String, McpSecurityConfigView> securityConfigCache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(10))
            .maximumSize(1)
            .build();
    private final Cache<String, Boolean> toolEnabledCache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(10))
            .maximumSize(10_000)
            .build();

    // 重连限流：serverId → 上次 reconnect 时间戳（修复 缺失-3）
    private static final Duration RECONNECT_COOLDOWN = Duration.ofSeconds(30);
    private final Cache<String, Long> reconnectCooldown = Caffeine.newBuilder()
            .expireAfterWrite(RECONNECT_COOLDOWN)
            .maximumSize(100)
            .build();

    // === ApplicationRunner：启动初始化（打破循环依赖，修复 1.2）===

    @Override
    public void run(ApplicationArguments args) {
        if (serverConfigMapper.selectCount(null) == 0) {
            bootstrapFromYaml();
        }
        initFromDb();
    }

    /** 首次启动从 yaml 导入 DB（含加密 bearer token） */
    private void bootstrapFromYaml() {
        // 读 McpSecurityProperties.bearerTokens → 按 host 加密 → INSERT mcp_server_config
    }

    /** 从 DB 加载所有 enabled server，逐个 createClient + addServer（fail-soft） */
    private void initFromDb() {
        List<McpServerConfig> enabled = serverConfigMapper.selectAllEnabled();
        for (McpServerConfig config : enabled) {
            try {
                McpSyncClient client = clientFactory.createClient(config);
                registryAdmin.addServer(config, client, null);
                serverConfigMapper.updateInitError(config.getServerId(), null);
            } catch (Exception e) {
                log.warn("MCP server {} init failed: {}", config.getServerId(), e.getMessage());
                serverConfigMapper.updateInitError(config.getServerId(), McpErrors.rootMessage(e));
                // 占位 McpServer（initError 非空）让 health 显示 down，工具调用返回友好错误
                registryAdmin.addServer(config, null, McpErrors.rootMessage(e));
            }
        }
    }

    // === Server CRUD（全部 @AdminAudit）===

    @AdminAudit(resourceType = "mcp_server", action = "create",
                resourceIdExpr = "#request.serverId",
                sensitiveFields = {"bearerToken"})
    public McpServerConfig createServer(CreateServerRequest request) {
        urlValidator.validate(request.url());
        McpServerConfig config = buildConfigFromRequest(request);
        if (request.bearerToken() != null) {
            config.setBearerTokenEncrypted(secretCipher.encrypt(request.bearerToken()));
        }

        // 1. tx 内：INSERT
        txTemplate.execute(status -> {
            serverConfigMapper.insert(config);
            return null;
        });

        // 2. tx 外：运行时注册（软失败，修复 1.5）
        try {
            McpSyncClient client = clientFactory.createClient(config);
            registryAdmin.addServer(config, client, null);
            invalidateToolCache(config.getServerId());
        } catch (Exception e) {
            String errMsg = McpErrors.rootMessage(e);
            serverConfigMapper.updateInitError(config.getServerId(), errMsg);
            // 占位 server 让调用方看到 down 状态
            registryAdmin.addServer(config, null, errMsg);
            // 不抛——DB 已 commit，调用方通过 listServers() 看到 initError
        }
        return config;
    }

    @AdminAudit(resourceType = "mcp_server", action = "delete", resourceIdExpr = "#id")
    public void deleteServer(Long id) {
        McpServerConfig config = serverConfigMapper.selectById(id);
        if (config == null) return;

        // 1. tx 内：DELETE server + 级联 DELETE tools
        txTemplate.execute(status -> {
            serverConfigMapper.deleteById(id);
            toolConfigMapper.deleteByServerId(config.getServerId());
            return null;
        });

        // 2. tx 外：运行时移除 + 缓存失效
        registryAdmin.removeServer(new ServerId(config.getServerId()));
        invalidateToolCache(config.getServerId());
    }

    @AdminAudit(resourceType = "mcp_server", action = "enable", resourceIdExpr = "#serverId")
    public void enableServer(String serverId) {
        txTemplate.execute(status -> {
            serverConfigMapper.updateEnabled(serverId, true);
            return null;
        });

        McpServerConfig config = serverConfigMapper.selectByServerId(serverId);
        try {
            McpSyncClient client = clientFactory.createClient(config);
            registryAdmin.addServer(config, client, null);
            serverConfigMapper.updateInitError(serverId, null);
            invalidateToolCache(serverId);
        } catch (Exception e) {
            String errMsg = McpErrors.rootMessage(e);
            serverConfigMapper.updateInitError(serverId, errMsg);
            registryAdmin.addServer(config, null, errMsg);
        }
    }

    @AdminAudit(resourceType = "mcp_server", action = "disable", resourceIdExpr = "#serverId")
    public void disableServer(String serverId) {
        txTemplate.execute(status -> {
            serverConfigMapper.updateEnabled(serverId, false);
            toolConfigMapper.updateEnabledByServerId(serverId, false);
            return null;
        });
        registryAdmin.removeServer(new ServerId(serverId));
        invalidateToolCache(serverId);
    }

    @AdminAudit(resourceType = "mcp_server", action = "reconnect", resourceIdExpr = "#serverId")
    public void reconnectServer(String serverId) {
        // 重连限流（修复 缺失-3）；v4 B1：用 ClientErrorCode.RATE_LIMITED（已有 100005）
        Long lastRun = reconnectCooldown.getIfPresent(serverId);
        if (lastRun != null) {
            throw new ClientException(ClientErrorCode.RATE_LIMITED,
                    "MCP server " + serverId + " reconnect cooldown (30s)");
        }
        reconnectCooldown.put(serverId, System.currentTimeMillis());

        McpServerConfig config = serverConfigMapper.selectByServerId(serverId);
        try {
            McpSyncClient client = clientFactory.createClient(config);
            registryAdmin.replaceServer(config, client);
            serverConfigMapper.updateInitError(serverId, null);
            invalidateToolCache(serverId);
        } catch (Exception e) {
            String errMsg = McpErrors.rootMessage(e);
            serverConfigMapper.updateInitError(serverId, errMsg);
            // 保留占位 server（带 initError），不 remove
            // v4 B1：用 RemoteErrorCode.MCP_SERVER_UNREACHABLE（已有 302001），不分裂枚举
            throw new RemoteException(RemoteErrorCode.MCP_SERVER_UNREACHABLE,
                    "MCP reconnect failed: " + errMsg, e);
        }
    }

    // === Bearer Token 单独管理（v3 新增，per-server 粒度）===

    @AdminAudit(resourceType = "mcp_server", action = "update_bearer_token",
                resourceIdExpr = "#serverId", sensitiveFields = {"bearerToken"})
    public void updateBearerToken(String serverId, String bearerToken) {
        McpServerConfig config = serverConfigMapper.selectByServerId(serverId);
        String encrypted = secretCipher.encrypt(bearerToken);

        txTemplate.execute(status -> {
            int rows = serverConfigMapper.updateBearerToken(serverId, encrypted, config.getVersion());
            if (rows == 0) {
                // v4 B1：用 ClientErrorCode.OPTIMISTIC_LOCK_CONFLICT（新增 100014）
                throw new ClientException(ClientErrorCode.OPTIMISTIC_LOCK_CONFLICT,
                        "concurrent modification of server: " + serverId);
            }
            return null;
        });

        // tx 外：重建该 server 的 client（token baked in transport）
        McpServerConfig refreshed = serverConfigMapper.selectByServerId(serverId);
        try {
            McpSyncClient client = clientFactory.createClient(refreshed);
            registryAdmin.replaceServer(refreshed, client);
            serverConfigMapper.updateInitError(serverId, null);
        } catch (Exception e) {
            String errMsg = McpErrors.rootMessage(e);
            serverConfigMapper.updateInitError(serverId, errMsg);
            throw new RemoteException(RemoteErrorCode.MCP_SERVER_UNREACHABLE,
                    "client rebuild after bearer update failed: " + errMsg, e);
        }
    }

    // === 工具管理 ===

    @AdminAudit(resourceType = "mcp_tool", action = "refresh_tools", resourceIdExpr = "#serverId")
    public void refreshTools(String serverId) {
        McpServer server = registryRead.find(new ServerId(serverId))
                .orElseThrow(() -> new ServiceException(ServiceErrorCode.NOT_FOUND,
                        "server not found: " + serverId));  // NOT_FOUND 已存在 200001，无需替换
        if (server.initError() != null) {
            // v4 B1：用 RemoteErrorCode.MCP_SERVER_UNREACHABLE
            throw new RemoteException(RemoteErrorCode.MCP_SERVER_UNREACHABLE,
                    "server down, cannot refresh tools: " + server.initError());
        }

        List<McpSchema.Tool> remoteTools = server.listToolsFromRemote();
        txTemplate.execute(status -> {
            // UPSERT 远端工具（不存在的入库为 enabled=false，ADMIN 显式启用 —— 修复 1.4）
            for (McpSchema.Tool tool : remoteTools) {
                upsertToolConfig(serverId, tool);
            }
            return null;
        });
        invalidateToolCache(serverId);
        toolCallbackProvider.invalidateCache();
    }

    @AdminAudit(resourceType = "mcp_tool", action = "enable", resourceIdExpr = "#toolConfigId")
    public void enableTool(Long toolConfigId) {
        txTemplate.execute(status -> {
            McpToolConfig tool = toolConfigMapper.selectById(toolConfigId);
            if (tool == null) throw new ServiceException(ServiceErrorCode.NOT_FOUND, "tool not found");
            tool.setEnabled(true);
            toolConfigMapper.updateById(tool);
            return null;
        });
        toolEnabledCache.invalidateAll();
        toolCallbackProvider.invalidateCache();
    }

    // disableTool 同理（action = "disable"）

    // === 安全配置 ===

    @AdminAudit(resourceType = "mcp_security", action = "update", resourceIdExpr = "'singleton'")
    public void updateSecurityConfig(McpSecurityConfigView view) {
        String json = JSON.toJSONString(view);
        txTemplate.execute(status -> {
            securityConfigMapper.updateConfigJson(json);
            return null;
        });
        securityConfigCache.invalidate("singleton");
    }

    public McpSecurityConfigView getSecurityConfig() {
        return securityConfigCache.get("singleton", k -> {
            McpSecurityConfig config = securityConfigMapper.selectSingleton();
            if (config == null) return McpSecurityConfigView.defaults();
            return config.view();
        });
    }

    /** DatabaseToolFilter 调用（默认 deny，修复 1.4） */
    public boolean isToolEnabled(String prefixedToolName) {
        return toolEnabledCache.get(prefixedToolName, k -> {
            McpToolConfig tool = toolConfigMapper.selectByPrefixedName(k);
            // 不存在 → 默认 deny（未入库工具需 refreshTools + ADMIN 显式启用）
            return tool != null && Boolean.TRUE.equals(tool.getEnabled());
        });
    }

    // === 缓存失效 ===

    private void invalidateToolCache(String serverId) {
        toolListCache.invalidate(serverId);
        toolEnabledCache.invalidateAll();
    }
}
```

### 5. DatabaseToolFilter（v4 修复 C2：合并 strict/lenient 为单 Bean）

**v3 缺陷**：① 默认 allow 是安全洞（v3 已修复）；② v3 又拆 strict / lenient 为两个 `@Component`，加上现存 `AllowlistMcpToolFilter` 共三个 Bean，但 `McpClientTransportConfiguration.syncMcpToolCallbackProvider` 单 `@Autowired McpToolFilter` 注入会冲突。

**v4 方案**：合并 strict / lenient 为**单 Bean**，配置项控制未入库默认行为；`McpAdminService.isToolEnabled` 返回 `Boolean`（三态）：

```java
@Component
public class DatabaseToolFilter implements McpToolFilter {

    private final McpAdminService adminService;
    private final McpToolNamePrefixGenerator prefixGen;
    private final boolean strictMode;  // @Value("${app.mcp.strict-tool-filter:true}")

    public DatabaseToolFilter(McpAdminService adminService,
                              McpToolNamePrefixGenerator prefixGen,
                              @Value("${app.mcp.strict-tool-filter:true}") boolean strictMode) {
        this.adminService = adminService;
        this.prefixGen = prefixGen;
        this.strictMode = strictMode;
    }

    @Override
    public boolean test(McpConnectionInfo conn, McpSchema.Tool tool) {
        if (conn == null || tool == null || tool.name() == null) return false;
        String prefixed = prefixGen.prefixedToolName(conn, tool);
        Boolean enabled = adminService.isToolEnabled(prefixed);  // v4：三态
        if (enabled != null) return enabled;                      // 入库：按 DB 配置
        return !strictMode;                                        // 未入库：strict→deny, lenient→allow
    }
}
```

**`McpAdminService.isToolEnabled` 改三态**：

```java
/** v4：null = 未入库；true/false = 入库且启用/禁用 */
public Boolean isToolEnabled(String prefixedToolName) {
    return toolEnabledCache.get(prefixedToolName, k -> {
        McpToolConfig tool = toolConfigMapper.selectByPrefixedName(k);
        return tool == null ? null : Boolean.TRUE.equals(tool.getEnabled());
    });
}
```

**`AllowlistMcpToolFilter` 处理**（implement.md Phase 8）：本期最后一步删除（与 `McpToolPolicy` 一起）。在删除前，因 `McpClientTransportConfiguration.syncMcpToolCallbackProvider` 是 `@Autowired(required = false) McpToolFilter`（单候选），Phase 3 落地 `DatabaseToolFilter` 后 `AllowlistMcpToolFilter` 必须先摘 `@Component`（或加 `@ConditionalOnProperty` 关闭），避免多候选冲突。

> **filtered by prefixedToolName 的语义保障**：B4 修复后，prefix generator 派生的 `prefixedToolName`（基于 `serverInfo.name`）与 `McpToolConfig.prefixed_tool_name` 入库值（`refreshTools` 时同样基于 `serverInfo.name` 派生）1:1 同源，确保 DB 查询命中。

### 6. SyncMcpToolCallbackProvider 重构（v3 修复 1.6，v4 修复 B2）

**v2 低估的问题**：现有 bean 构造函数注入 `List<McpSyncClient>`，改为 "registry 驱动" 不是改字段，是改 API 签名 + 缓存失效语义。

**v3 缺陷（B2）**：plan 在 `McpServer` core 接口加 `toolCallbacks(filter, prefixGen, metaConverter)`——但 `ToolCallback` 是 Spring AI starter 类型，违反 `mcp/core/` 包"零 starter 依赖"铁律。

**v4 方案**：
- core 不动（`McpServer` 保持 `id()` / `health()` / `tools()` / `resources()` / `prompts()`）
- 在 `mcp/mcpclient/` 加 **runtime 层 adapter 接口**：`McpServerToolCallbacksAdapter`
- `McpServerImpl`（runtime，已依赖 Spring AI）实现此 adapter
- `SyncMcpToolCallbackProvider` 注入 `McpServerRegistry`（取 McpServer 列表）+ `McpServerToolCallbacksAdapter`（每个 server 抽出工具 callbacks）
- 缓存 key 加 registry 版本号：`cachedVersion = registryAdmin.currentVersion()`
- 版本变更 → 下次 `getToolCallbacks()` miss → 重新发现

```java
// mcp/mcpclient/McpServerToolCallbacksAdapter.java（v4 新增）
public interface McpServerToolCallbacksAdapter {
    /**
     * 从单个 McpServer 抽出经 filter/prefix 处理后的 Spring AI ToolCallback 列表。
     * <p>
     * 实现位于 runtime 层（{@code McpServerImpl}），允许引用 Spring AI {@link ToolCallback}。
     * core 层的 {@code McpServer} 接口不感知此 adapter（依赖方向 runtime → core）。
     */
    List<ToolCallback> toolCallbacks(McpServer server,
                                     McpToolFilter filter,
                                     McpToolNamePrefixGenerator prefixGen,
                                     ToolContextToMcpMetaConverter metaConverter);
}
```

```java
// mcp/runtime/McpServerImpl.java（实现 adapter，不污染 McpServer core 接口）
final class McpServerImpl implements McpServer, McpServerToolCallbacksAdapter {
    // ... 现有字段 ...

    @Override
    public List<ToolCallback> toolCallbacks(McpServer server,
                                            McpToolFilter filter,
                                            McpToolNamePrefixGenerator prefixGen,
                                            ToolContextToMcpMetaConverter metaConverter) {
        // 把原本散在 SyncMcpToolCallbackProvider.getToolCallbacks() 里的 per-server 遍历逻辑下沉到此
        // 使用本 server 的 McpSyncClient + connInfo 调 listTools() → filter → prefix → build callback
        McpConnectionInfo connInfo = McpConnectionInfo.builder()
                .clientCapabilities(client.getClientCapabilities())
                .clientInfo(client.getClientInfo())
                .initializeResult(client.getCurrentInitializationResult())
                .build();
        return client.listTools().tools().stream()
                .filter(tool -> filter.test(connInfo, tool))
                .map(tool -> SyncMcpToolCallback.builder()
                        .mcpClient(client)
                        .tool(tool)
                        .prefixedToolName(prefixGen.prefixedToolName(connInfo, tool))
                        .toolContextToMcpMetaConverter(metaConverter)
                        .build())
                .toList();
    }
}
```

```java
public class SyncMcpToolCallbackProvider {

    private final McpToolFilter filter;
    private final McpToolNamePrefixGenerator prefixGen;
    private final McpServerRegistry registry;                       // 注入只读接口
    private final McpServerToolCallbacksAdapter adapter;            // v4：注入 adapter，不污染 core
    private final ToolContextToMcpMetaConverter metaConverter;

    // 双重检查的 volatile cache（registry 版本 + callbacks）
    private volatile long cachedVersion = -1L;
    private volatile ToolCallback[] cachedCallbacks;

    public ToolCallback[] getToolCallbacks() {
        long currentVersion = registry instanceof McpServerRegistryAdmin admin
                ? admin.currentVersion()
                : 0L;  // 测试 stub 退化为每次重新发现

        if (currentVersion == cachedVersion && cachedCallbacks != null) {
            return cachedCallbacks;
        }

        // 重新发现
        List<ToolCallback> callbacks = new ArrayList<>();
        for (McpServer server : registry.list()) {
            if (server.initError() != null) continue;  // 跳过 down 的 server
            // v4：经 adapter 调用，避免在 core McpServer 接口加 Spring AI 类型
            callbacks.addAll(adapter.toolCallbacks(server, filter, prefixGen, metaConverter));
        }
        ToolCallback[] result = callbacks.toArray(new ToolCallback[0]);
        cachedVersion = currentVersion;
        cachedCallbacks = result;
        return result;
    }

    /** McpAdminService 在 server/tool 变更后调用 */
    public void invalidateCache() {
        cachedVersion = -1L;
        cachedCallbacks = null;
    }
}
```

> **注**：`McpServerImpl` 同时实现 `McpServer`（core 契约）+ `McpServerToolCallbacksAdapter`（runtime 契约）。`SyncMcpToolCallbackProvider` 通过 Spring 注入拿到 adapter bean，不感知 `McpServerImpl` 具体类。Bean 装配时 `McpServerImpl` 自身可作为 `McpServerToolCallbacksAdapter` 注入（@Component 已标，无需额外 Bean 声明）。

### 7. 缓存策略（v3 单级，修复 2.1）

**不使用 CacheManager / @Cacheable / Redis 工具缓存层**。直接 Caffeine `Cache<K,V>` 单级 + TTL + 主动 invalidate。

| 缓存 | key | value | TTL | size | 失效触发 |
|---|---|---|---|---|---|
| `toolListCache` | serverId | `List<McpToolConfig>` | 10min | 100 | ADMIN 改 server/tools 时 invalidate(serverId) |
| `securityConfigCache` | "singleton" | `McpSecurityConfigView` | 10min | 1 | updateSecurityConfig 时 invalidate |
| `toolEnabledCache` | prefixedToolName | `Boolean` | 10min | 10_000 | 任何 tool 变更时 invalidateAll |
| `reconnectCooldown` | serverId | `Long`(timestamp) | 30s | 100 | 自动 expire |

**TTL 兜底语义**：即使 ADMIN 操作忘记 invalidate，TTL 也会在 10min 内自动收敛。

### 8. SSRF 防护（复用下沉后的通用校验器）

**v3 修复 2.2**：把 `BaseUrlValidator` + `ApiKeyCipher` 一起下沉到 `infrastructure/security/`，**重命名为通用类名**（不再绑 LLM）：

| v2 类名（LLM 专属） | v3 类名（通用） | 说明 |
|---|---|---|
| `infrastructure/llm/config/BaseUrlValidator` | `infrastructure/security/HostSafetyValidator` | SSRF 校验，去掉 LlmByokProperties 依赖 |
| `infrastructure/llm/crypto/ApiKeyCipher` | `infrastructure/security/SecretCipher` | 通用加密，去掉 LlmCryptoProperties 依赖 |
| `LlmByokProperties`（部分字段） | `SecuritySsrProperties`（`app.security.ssrf.*`） | SSRF 配置 |
| `LlmCryptoProperties` | `SecurityCryptoProperties`（`app.security.crypto.*`） | 加密 master key |

`McpAdminService` / `McpClientFactory` 注入 `HostSafetyValidator` + `SecretCipher`。`infrastructure/llm/` 模块的旧 import 全量替换。

### 10. McpSecurityConfigAccessor（避免循环依赖 + B5 编译产物缓存）

**问题 1（v3）**：`McpDescriptionSanitizer` 被 `McpServerRegistryImpl` 注入；改造后 `McpSecurityGuard` / `McpDescriptionSanitizer` 需读 DB 安全配置（经 `McpAdminService.getSecurityConfig()`）。若直接注入 `McpAdminService`，依赖链成环：

```
McpAdminService → McpServerRegistryAdmin
                  ↑ McpServerRegistryImpl 实现
                  ↓ 注入
                  McpDescriptionSanitizer → McpAdminService（循环）
```

**问题 2（v4 B5）**：`McpSecurityGuard` 当前在构造期一次性编译 `sensitivePatterns`，切 DB 后无法热更新。直接每次 `guard()` 调用 `Pattern.compile` 是 chat 热路径——性能不可接受。

**v4 推荐**：抽出 `McpSecurityConfigAccessor`（独立 Bean，仅依赖 `McpSecurityConfigMapper` + Jackson `ObjectMapper`）。
- 同时承担 **view 缓存** + **编译产物缓存** 双重职责
- 由 `McpSecurityGuard` / `McpDescriptionSanitizer` / `McpAdminService` 共同注入
- `McpAdminService.updateSecurityConfig()` 调 `accessor.invalidate()` 触发失效

```
McpSecurityConfigAccessor（无业务依赖）
  ↑ 注入                    ↑ 注入              ↑ 注入
McpSecurityGuard    McpDescriptionSanitizer    McpAdminService
                                                  ↓ 调用 invalidate()
```

依赖链断开，`McpServerRegistryImpl → McpDescriptionSanitizer → McpSecurityConfigAccessor`（不回到 McpAdminService）。

```java
// mcp/admin/service/McpSecurityConfigAccessor.java（v4：升级版，含编译产物缓存）
@Component
public class McpSecurityConfigAccessor {

    private static final Logger log = LoggerFactory.getLogger(McpSecurityConfigAccessor.class);
    private static final Duration TTL = Duration.ofMinutes(10);

    private final McpSecurityConfigMapper mapper;
    private final ObjectMapper objectMapper;  // v4 C1：统一 Jackson

    /** TTL 闸门：view 的 10min 缓存 */
    private final Cache<String, McpSecurityConfigView> viewCache = Caffeine.newBuilder()
            .expireAfterWrite(TTL).maximumSize(1).build();

    /** 编译产物缓存（DCL）。invalidate 时置 null，下次 patterns() 重新编译 */
    private volatile List<Pattern> patternsCache;

    public McpSecurityConfigAccessor(McpSecurityConfigMapper mapper, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    /** 反序列化视图（10min TTL） */
    public McpSecurityConfigView get() {
        return viewCache.get("singleton", k -> loadFromDb());
    }

    /**
     * 已编译的敏感参数正则列表（DCL + 视图缓存）。
     * <p>
     * v4 B5：替代 McpSecurityGuard 构造期一次性编译。命中缓存 O(1)，admin 更新触发 {@link #invalidate()}
     * 后下次调用重新编译。chat 热路径上每次 guard() 调用零额外开销。
     */
    public List<Pattern> patterns() {
        List<Pattern> cached = patternsCache;
        if (cached != null) return cached;
        synchronized (this) {
            if (patternsCache == null) {
                patternsCache = compile(get().sensitiveArgPatterns());
            }
            return patternsCache;
        }
    }

    /** admin 更新后调，清两层缓存 */
    public void invalidate() {
        viewCache.invalidate("singleton");
        patternsCache = null;
    }

    private McpSecurityConfigView loadFromDb() {
        try {
            McpSecurityConfig row = mapper.selectSingleton();
            if (row == null || row.getConfigJson() == null) {
                return McpSecurityConfigView.defaults();
            }
            return objectMapper.readValue(row.getConfigJson(), McpSecurityConfigView.class);
        } catch (Exception e) {
            log.warn("McpSecurityConfig load failed, fallback to defaults: {}", e.getMessage());
            return McpSecurityConfigView.defaults();
        }
    }

    private static List<Pattern> compile(List<String> patterns) {
        if (patterns == null || patterns.isEmpty()) return List.of();
        return patterns.stream().map(Pattern::compile).toList();
    }
}
```

**`McpSecurityGuard` 改造**：

```java
@Component
public class McpSecurityGuard {

    private final McpToolPolicy policy;  // 保留——risk() 仍读 policy（v3 决定本期不迁）
    private final McpSecurityConfigAccessor accessor;  // 替代 McpSecurityProperties

    public McpSecurityGuard(McpToolPolicy policy, McpSecurityConfigAccessor accessor) {
        this.policy = policy;
        this.accessor = accessor;
    }

    public McpToolResult guard(McpTools tools, String name, McpArgs args, Subject subj) {
        String risk = policy.risk(name);
        if (sensitiveArgHit(args)) {
            // ...
        }
        McpToolResult r = tools.call(name, args, subj);
        return capAndMark(r, risk, accessor.get());  // 每次取最新 view（10min TTL）
    }

    private boolean sensitiveArgHit(McpArgs args) {
        List<Pattern> patterns = accessor.patterns();  // v4 B5：编译产物缓存
        if (patterns.isEmpty() || args == null) return false;
        // ... 同现行逻辑，但 patterns 改用 accessor.patterns()
    }

    private McpToolResult capAndMark(McpToolResult r, String risk, McpSecurityConfigView view) {
        int cap = "high".equals(risk) ? view.highRiskOutputCapChars() : view.defaultOutputCapChars();
        // ... 同现行
    }
}
```

> **注**：`McpToolPolicy.risk()` 仍在 yaml（implement.md Step 5.3 决定本期不动）。`risk` 字段的 DB 化留给后续 task。

### 9. 通用 AdminAudit AOP（v3 新增，缺失-1）

**位置**：`infrastructure/audit/`（不绑定 MCP）

```
infrastructure/audit/
├── AdminAudit.java                # @AdminAudit 注解
├── AdminAuditAspect.java          # AOP 切面
├── AdminAuditLog.java             # Entity
├── AdminAuditLogMapper.java       # Mapper
├── AdminAuditLogMapper.xml
├── AdminAuditAsyncWriter.java     # 异步写入器
└── AdminAuditLogService.java      # 查询 service（ADMIN 看 audit log 用）
```

#### 9.1 `@AdminAudit` 注解

```java
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface AdminAudit {

    /** 资源类型，e.g. "mcp_server", "mcp_tool", "llm_model", "rag_pipeline" */
    String resourceType();

    /** 操作类型，e.g. "create", "update", "delete", "enable", "disable", "reconnect" */
    String action();

    /**
     * 资源 ID SpEL 表达式，相对于方法入参。
     * e.g. "#request.serverId", "#id", "#result.id"
     * 留空则不记录 resourceId。
     */
    String resourceIdExpr() default "";

    /** 是否记录请求 payload（默认 true） */
    boolean logRequest() default true;

    /** 敏感字段路径（自动脱敏为 "***"），e.g. {"bearerToken", "password"} */
    String[] sensitiveFields() default {};

    /**
     * <b>v4 自调用约束（C7）</b>：被 {@code @AdminAudit} 标注的方法之间禁止直接相互调用
     * （{@code this.method()}），否则内层审计不生效（Spring AOP 代理限制：CGLIB 代理不拦截同类内调用）。
     * <p>
     * 如需共用逻辑，抽到 private 方法或单独 Bean。
     * <p>
     * <b>当前 design 不受影响</b>：所有 {@code @AdminAudit} 方法（createServer / deleteServer /
     * enableServer / disableServer / reconnectServer / updateBearerToken / refreshTools /
     * enableTool / disableTool / updateSecurityConfig）之间无任何相互调用。此约束为未来扩展时遵守。
     */
    String _selfInvocationConstraint() default "";  // 占位，文档用，不读
}
```

#### 9.2 AOP 切面

```java
@Aspect
@Component
public class AdminAuditAspect {

    private final AdminAuditAsyncWriter writer;
    private final SpelExpressionParser parser = new SpelExpressionParser();

    @Around("@annotation(adminAudit)")
    public Object audit(ProceedingJoinPoint pjp, AdminAudit adminAudit) throws Throwable {
        long start = System.currentTimeMillis();
        String resourceId = evalResourceId(adminAudit, pjp);
        MethodSignature sig = (MethodSignature) pjp.getSignature();
        OperatorInfo operator = OperatorInfo.fromSecurityContext();
        RequestMeta requestMeta = RequestMeta.fromRequestContext();

        boolean success = false;
        String errorCode = null;
        String errorMessage = null;
        try {
            Object result = pjp.proceed();
            success = true;
            return result;
        } catch (ServiceException e) {
            errorCode = e.getErrorCode().name();
            errorMessage = e.getMessage();
            throw e;
        } catch (Exception e) {
            errorCode = "INTERNAL_ERROR";
            errorMessage = e.getMessage();
            throw e;
        } finally {
            long durationMs = System.currentTimeMillis() - start;
            String payload = adminAudit.logRequest()
                    ? sanitizePayload(sig.getMethod(), pjp.getArgs(), adminAudit.sensitiveFields())
                    : null;
            // 异步写入，不阻塞响应
            writer.writeAsync(AdminAuditLog.builder()
                    .operatorId(operator.userId())
                    .operatorName(operator.username())
                    .operatorRole(operator.role())
                    .resourceType(adminAudit.resourceType())
                    .resourceId(resourceId)
                    .action(adminAudit.action())
                    .requestPayload(payload)
                    .resultStatus(success ? "SUCCESS" : "FAILURE")
                    .errorCode(errorCode)
                    .errorMessage(errorMessage)
                    .ipAddress(requestMeta.ip())
                    .userAgent(requestMeta.userAgent())
                    .durationMs((int) durationMs)
                    .build());
        }
    }

    private String evalResourceId(AdminAudit ann, ProceedingJoinPoint pjp) {
        if (ann.resourceIdExpr().isBlank()) return null;
        MethodSignature sig = (MethodSignature) pjp.getSignature();
        EvaluationContext ctx = new MethodBasedEvaluationContext(
                pjp.getTarget(), sig.getMethod(), pjp.getArgs());
        // 注册方法参数名（#request 等按参数名引用）
        String[] paramNames = sig.getParameterNames();
        for (int i = 0; i < paramNames.length; i++) {
            ctx.setVariable(paramNames[i], pjp.getArgs()[i]);
        }
        try {
            return String.valueOf(parser.parseExpression(ann.resourceIdExpr()).getValue(ctx));
        } catch (Exception e) {
            return null;  // 表达式解析失败不阻塞业务
        }
    }

    private String sanitizePayload(Method method, Object[] args, String[] sensitiveFields) {
        // 序列化 args 为 JSON，敏感字段（含嵌套）替换为 "***"
        // 实现略，使用 Jackson + JsonPath 替换
    }
}
```

#### 9.3 异步写入器（v4 修复 C3：CallerRunsPolicy 替代 DiscardOldestPolicy）

> **v3 缺陷**：`DiscardOldestPolicy` 队列满时丢**最旧**审计行——违反合规审计"不丢历史"语义。
>
> **v4 决策**：改 `CallerRunsPolicy`，让调用线程同步执行写入（短暂阻塞业务、自然 backpressure、不丢数据）。队列大小调到 2000，配合 logger warning 监控溢出。

```java
@Component
public class AdminAuditAsyncWriter {

    private final AdminAuditLogMapper mapper;
    private final ExecutorService executor;  // 单线程，daemon，CallerRunsPolicy（v4）

    public AdminAuditAsyncWriter(AdminAuditLogMapper mapper) {
        this.mapper = mapper;
        this.executor = new ThreadPoolExecutor(
                1, 1, 60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(2000),  // v4：队列从 1000 调到 2000
                r -> {
                    Thread t = new Thread(r, "admin-audit-writer");
                    t.setDaemon(true);
                    return t;
                },
                // v4：CallerRunsPolicy——队列满时让业务线程同步执行写入，不丢数据
                // 代价：审计写入慢时短暂阻塞业务（约几毫秒），但合规性优先
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
    }

    public void writeAsync(AdminAuditLog log) {
        executor.submit(() -> {
            try {
                mapper.insert(log);
            } catch (Exception e) {
                // 审计失败不影响业务，但记 error log（含完整行信息便于人工补录）
                LoggerFactory.getLogger(getClass())
                        .error("admin audit log insert failed: action={} resource={}/{} operator={}/{}",
                                log.getAction(), log.getResourceType(), log.getResourceId(),
                                log.getOperatorId(), log.getOperatorName(), e);
            }
        });
    }

    @PreDestroy
    void shutdown() {
        executor.shutdown();
        try {
            executor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
```

#### 9.4 MCP 模块使用示例

```java
@AdminAudit(resourceType = "mcp_server", action = "create",
            resourceIdExpr = "#request.serverId",
            sensitiveFields = {"bearerToken"})
public McpServerConfig createServer(CreateServerRequest request) { ... }

@AdminAudit(resourceType = "mcp_server", action = "reconnect", resourceIdExpr = "#serverId")
public void reconnectServer(String serverId) { ... }

@AdminAudit(resourceType = "mcp_server", action = "update_bearer_token",
            resourceIdExpr = "#serverId", sensitiveFields = {"bearerToken"})
public void updateBearerToken(String serverId, String bearerToken) { ... }
```

#### 9.5 跨模块复用示例（未来 LLM/RAG）

```java
// 未来 LLM 模块
@AdminAudit(resourceType = "llm_model", action = "update",
            resourceIdExpr = "#request.candidateId")
public void updateModel(UpdateModelRequest request) { ... }

// 未来 RAG 模块
@AdminAudit(resourceType = "rag_pipeline", action = "update_params",
            resourceIdExpr = "'singleton'")
public void updateRagParams(RagParamsRequest request) { ... }
```

无需额外开发，注解 + AOP 即插即用。

---

## Runtime Behavior

### 热重载流程（v3 完整修复）

```
ADMIN 操作 → McpAdminService（@AdminAudit AOP 切入）
  →
  1. tx 内：DB 写入（TransactionTemplate）
  →
  2. tx 外：运行时操作
     - Server 创建/重连：McpClientFactory.createClient() → registryAdmin.addServer/replaceServer()
       - 失败：catch → UPDATE init_error → addServer(config, null, errMsg) 占位
     - Server 删除/禁用：registryAdmin.removeServer()
     - 工具变更：toolCallbackProvider.invalidateCache()
     - Snapshot 原子切换：AtomicReference.CAS → 旧 client 异步关闭
  →
  3. 缓存失效：Caffeine.invalidate (单级，无 Redis)
  →
  4. AOP 异步：admin_audit_log 写入（不阻塞响应）
```

### 重连期间行为（v3 修复 1.1 + 1.5）

- **registry 中保留占位 McpServer**（`initError` 非空，client=null），而非直接 remove
- 工具调用时检查 `initError`，非空则返回 `McpToolResult.error("MCP链接断开正在重新连接")`
- **重连成功**：`replaceServer` 原子替换为新 client，`init_error` 字段清空
- **重连失败**：保留占位 server + `init_error` 更新为最新错误，ADMIN 可重试
- 不影响其他 Server 的正常调用（snapshot 模式保证）

### LLM 调用时工具加载

```
LLM 请求
  → SyncMcpToolCallbackProvider.getToolCallbacks()
    → 检查 registry.currentVersion() vs cachedVersion
    → 版本变更 → 重新遍历 registry.list()
      → 跳过 initError != null 的 server
      → DatabaseToolFilter.test() 过滤（默认 deny，Caffeine 缓存）
    → 缓存新 callbacks + 版本号
```

### 软失败语义（v3 修复 1.5）

| 场景 | DB 状态 | Registry 状态 | 用户可见 |
|---|---|---|---|
| createServer + client 创建失败 | INSERT 成功（enabled=true） | 占位 server（initError=errMsg） | `GET /servers` 显示 init_error，`GET /health` 显示 down |
| reconnect 失败 | UPDATE init_error=errMsg | 占位 server 保留 | 工具调用返回友好错误 |
| enableServer 后 client 失败 | UPDATE enabled=true | 占位 server | 同上 |
| 重启后从 DB 加载 | (上次的 init_error 还在) | 再次尝试 createClient，成功则清 init_error | 自动恢复 |

### 乐观锁（v3 新增 缺失-2，v4 修复 B3 注册拦截器）

`McpServerConfig` / `McpToolConfig` 加 `@Version`：

```java
@Version
private Long version;
```

**v4 B3 前置条件**：MyBatis-Plus `@Version` 必须 **配合 `OptimisticLockerInnerInterceptor` 才生效**。当前 `MyBatisPlusConfig` 只注册了 `PaginationInnerInterceptor`，需在 Phase 0 加 Step 注册：

```java
@Bean
public MybatisPlusInterceptor mybatisPlusInterceptor() {
    MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
    interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());  // v4 新增
    interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.POSTGRE_SQL));
    return interceptor;
}
```

**回归验证**：grep `@Version` 在 `src/main/java` 的现有使用，确认没有任何 entity 已经依赖但失效（若有则属既有 bug，单独修复，本期不扩大范围）。

MyBatis-Plus `updateById` 自动带 `WHERE version = ?`，冲突时返回 0 行。Service 层判断：

```java
int rows = serverConfigMapper.updateById(config);
if (rows == 0) {
    // v4 B1：ClientErrorCode.OPTIMISTIC_LOCK_CONFLICT（新增 100014），HTTP 409
    throw new ClientException(ClientErrorCode.OPTIMISTIC_LOCK_CONFLICT,
            "concurrent modification detected, please retry");
}
```

### 重连限流（v3 新增 缺失-3，v4 B1 异常类替换）

```java
private static final Duration RECONNECT_COOLDOWN = Duration.ofSeconds(30);
private final Cache<String, Long> reconnectCooldown = Caffeine.newBuilder()
        .expireAfterWrite(RECONNECT_COOLDOWN)
        .maximumSize(100)
        .build();

public void reconnectServer(String serverId) {
    Long lastRun = reconnectCooldown.getIfPresent(serverId);
    if (lastRun != null) {
        // v4 B1：复用 ClientErrorCode.RATE_LIMITED（已有 100005），HTTP 429
        throw new ClientException(ClientErrorCode.RATE_LIMITED,
                "reconnect cooldown (30s) for server: " + serverId);
    }
    reconnectCooldown.put(serverId, System.currentTimeMillis());
    // ... 正常 reconnect 逻辑
}
```

> **v4 B1 异常替换原则**：
> - 客户端频繁请求 → `ClientException(ClientErrorCode.RATE_LIMITED)`（A 类 100005）
> - 资源不存在 → `ServiceException(ServiceErrorCode.NOT_FOUND)`（B 类 200001，已有）
> - 资源版本冲突 → `ClientException(ClientErrorCode.OPTIMISTIC_LOCK_CONFLICT)`（A 类 100014，**v4 新增**）
> - MCP client init / reconnect / bearer-rebuild 失败 → `RemoteException(RemoteErrorCode.MCP_SERVER_UNREACHABLE)`（C 类 302001）
> - **不允许**在 `ServiceErrorCode` 加 `RATE_LIMITED` / `REMOTE_INIT_FAILED` 等分裂现有枚举结构的码

> **注**：限流在**方法入口**而非 controller，避免绕过限流直接调 service 的场景。

---

## Package Structure（v3）

```
infrastructure/
├── security/                         # 新建：通用安全原语（v3 修复 2.2）
│   ├── HostSafetyValidator.java      # 下沉 + 重命名（原 BaseUrlValidator）
│   ├── SecretCipher.java             # 下沉 + 重命名（原 ApiKeyCipher）
│   ├── SecuritySsrProperties.java    # @ConfigurationProperties("app.security.ssrf")
│   └── SecurityCryptoProperties.java # @ConfigurationProperties("app.security.crypto")
├── audit/                            # 新建：通用审计基础设施（v3 新增，缺失-1）
│   ├── AdminAudit.java               # @AdminAudit 注解
│   ├── AdminAuditAspect.java         # AOP 切面
│   ├── AdminAuditAsyncWriter.java    # 异步写入器
│   ├── AdminAuditLogService.java     # 查询 service（ADMIN 看 audit log 用）
│   ├── entity/AdminAuditLog.java
│   └── mapper/AdminAuditLogMapper.java
└── llm/                              # 改 import（指向 infrastructure/security）

mcp/
├── admin/
│   ├── controller/McpAdminController.java
│   ├── dto/
│   ├── entity/{McpServerConfig, McpToolConfig, McpSecurityConfig}.java
│   ├── mapper/{McpServerConfigMapper, McpToolConfigMapper, McpSecurityConfigMapper}.java
│   └── service/McpAdminService.java  # implements ApplicationRunner
├── config/
│   ├── DatabaseToolFilter.java       # v4 C2：单 Bean，strict/lenient 由 app.mcp.strict-tool-filter 控制
│   └── McpClientTransportConfiguration.java  # 仅保留 Properties bean（bootstrap）；移除 mcpSyncClients / mcpBearerAuthRequestCustomizer
├── core/
│   ├── McpServerRegistry.java        # 只读接口（保持不变）
│   └── McpServer.java                # 加 toolCallbacks(filter, prefixGen, metaConverter) 方法
├── runtime/
│   ├── McpServerRegistryAdmin.java   # 新建：管理员写接口
│   ├── McpServerRegistryImpl.java    # 双实现，AtomicReference<ImmutableMap>
│   ├── McpClientFactory.java         # 新建：动态 client 管理
│   └── ...
└── mcpclient/
    └── SyncMcpToolCallbackProvider.java  # 重构：注入 Registry + 版本化 cache

resources/
├── mapper/
│   ├── McpServerConfigMapper.xml
│   ├── McpToolConfigMapper.xml
│   ├── McpSecurityConfigMapper.xml
│   └── AdminAuditLogMapper.xml
└── db/migration/
    └── V17__create_mcp_admin_tables.sql  # 含 admin_audit_log（通用）
```

---

## Bearer Token 处理（v3 修订）

**v2 决策**：Bearer Token 留 yaml，DB 不管理。
**v3 决策**：Bearer Token 进 DB（per-server 粒度，加密存储），DB 为唯一事实源。

### 数据流

```
ADMIN POST /api/admin/mcp/servers/{serverId}/update-bearer-token
  body: {"bearerToken": "sk-xxx"}
    ↓
McpAdminService.updateBearerToken(serverId, bearerToken)
  @AdminAudit(action="update_bearer_token", sensitiveFields={"bearerToken"})
    ↓
  1. SecretCipher.encrypt(bearerToken) → encrypted
  2. tx: UPDATE mcp_server_config SET bearer_token_encrypted = ?, version = version + 1
       WHERE server_id = ? AND version = ?
  3. tx 外：McpClientFactory.createClient(refreshedConfig)（解密 → 注入 transport）
  4. registryAdmin.replaceServer(refreshedConfig, newClient)
  5. UPDATE init_error = NULL
```

### Bootstrap（首次启动）

`McpAdminService.run()` 检测 `mcp_server_config` 为空时：
1. 读 `McpClientTransportProperties.streamableHttp.connections`（URL 清单）→ 一条 connection 对应一条 `mcp_server_config` 记录（`server_id=NULL`，握手后回填，见 B4）
2. 读 `McpSecurityProperties.bearerTokens`（Map<host, token>）→ 按 host 匹配 connection URL → `SecretCipher.encrypt(token)` → 写入对应 server 的 `bearer_token_encrypted`
3. 后续运行时 `McpSecurityProperties` 和 `McpClientTransportProperties` 都不再被任何 Bean 注入（v4 C8 对称降级）

> **v4 C5 — yaml host 粒度限制（已知限制）**：
>
> yaml `mcp.security.bearer-tokens` 是 `Map<host, token>`，按 URL host 匹配。同 host 多 path 的 MCP server（如 `mcp.example.com/svc-a` 与 `mcp.example.com/svc-b`）会**共享同一 token**——这是 yaml 格式固有限制。
>
> **bootstrap 后**，ADMIN 可经 `POST /api/admin/mcp/servers/{serverId}/update-bearer-token` per-server 精细化覆盖（DB 层已是 per-server 粒度）。

> **v4 C4 — 启动窗口期（预期行为）**：
>
> 从 ApplicationContext 装配完成到 `McpAdminService.run()` 执行完成之间（典型 < 1s），`McpServerRegistry` 为空，LLM 请求获得的 MCP 工具数为 0。
>
> 此为**预期行为**，因 Bean 装配完成 ≠ MCP 握手完成。若需消除窗口，可改用 `SmartInitializingSingleton` 或在 `run()` 末尾发 `ApplicationReadyEvent` 阻塞 web server 启动（本期不做，留给后续 task 评估）。

> **v4 C8 — McpClientTransportProperties 对称降级**：
>
> | Properties | 启动期 | 运行时 |
> |---|---|---|
> | `McpSecurityProperties` | `McpAdminService.bootstrapFromYaml()` 读一次（DB 空时） | **无 Bean 注入** |
> | `McpClientTransportProperties` | `McpAdminService.bootstrapFromYaml()` 读一次（DB 空时） | **无 Bean 注入** |
>
> `McpClientTransportConfiguration.mcpSyncClients()` Bean **删除**（不再静态创建 `List<McpSyncClient>`，改由 `McpClientFactory.createClient()` 动态创建）。`SyncMcpToolCallbackProvider` Bean 保留但构造改注入 `McpServerRegistry` + `McpServerToolCallbacksAdapter`（见 §6）。

---

## Testing Strategy

### 单元测试

| 测试类 | 覆盖点 |
|---|---|
| `McpAdminServiceTest` | CRUD、@Version 冲突、软失败 init_error、reconnect 限流、缓存失效 |
| `McpServerRegistryImplTest` | AtomicReference CAS、并发 add/remove、占位 server、版本号递增 |
| `McpClientFactoryTest` | SSRF 校验、bearer token 解密注入、initialize 失败抛 ServiceException |
| `DatabaseToolFilterTest` | strict 模式默认 deny、LENIENT 模式默认 allow、缓存命中 |
| `AdminAuditAspectTest` | SpEL 解析、敏感字段脱敏、失败路径记录、异步写入 |
| `SecretCipherTest`（迁移后） | 加密/解密 round-trip、key 变更 |
| `HostSafetyValidatorTest`（迁移后） | 内网 IP 拒绝、危险 scheme、合法 URL 通过 |

### 集成测试

| 测试类 | 覆盖点 |
|---|---|
| `McpAdminControllerTest` | @PreAuthorize 权限、@AdminAudit 切入、@Version 冲突响应 |
| `McpAdminServiceInitTest` | ApplicationRunner 启动 bootstrap、fail-soft 隔离 |
| `McpAdminServiceReconnectTest` | 重连限流、占位 server、init_error 流转 |
| `BearerTokenUpdateFlowTest` | 更新 bearer → client 重建 → registry.replaceServer → init_error 清空 |

### 并发测试（新增）

| 测试类 | 覆盖点 |
|---|---|
| `McpServerRegistryConcurrencyTest` | 10 线程并发 addServer、removeServer、replaceServer，验证 snapshot 一致性 |
| `McpAdminServiceConcurrentUpdateTest` | 两线程并发 update 同一 server，验证 @Version 冲突 |

### ArchUnit 测试

- 更新 `McpDependencyRulesTest`：MCP admin 包不依赖 LLM 包
- 新增 `AuditDependencyRulesTest`：`infrastructure/audit/` 不依赖任何业务模块（仅被业务模块依赖）
- 新增 `SecurityDependencyRulesTest`：`infrastructure/security/` 不依赖任何业务模块
