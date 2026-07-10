# MCP Code Review Remediation Technical Design

## 1. Design Goal

在不改变 `/api/admin/mcp/**` 路由、`GlobalResponse` 格式和 MCP core 公共接口的前提下，修复审查确认的安全、数据库、Bean 装配、资源管理、故障隔离和维护性问题。实现采用小步边界修复：先锁定行为，再拆分职责，最后进行完整 checklist 复核。

## 2. Constraints

- 不新增依赖，不引入新的 per-user MCP RBAC 产品模型。
- 保留 V17，所有 schema 修复放入 V18 前向迁移。
- `mcp/core` 不依赖 Spring AI starter 类型；ArchUnit 规则继续成立。
- 不使用 `@Transactional`；多表原子操作继续使用现有 `TransactionTemplate`。
- Controller 不捕获异常，统一交给 `GlobalExceptionHandler`。
- `McpAdminService` 保留为 Controller facade，避免 REST 层和现有测试大面积改签名。
- 对 `SyncMcpToolCallbackProvider`、`McpServerImpl`、`McpAuthorizer` 的改动属于高风险执行流，公共构造/接口兼容只在有测试证明后收缩。

## 3. Chosen Approach

采用“边界修复 + 有针对性的职责拆分”。

### 3.1 Target component graph

```text
McpAdminController
        |
        v
McpAdminService (thin facade, <= 8 dependencies, <= 300 lines)
   |                |                   |
   v                v                   v
McpServerAdminService  McpToolAdminService  McpSecurityAdminService
   |                |                   |
   |                +--> McpToolConfigAccessor <-- DatabaseToolFilter
   |                               ^             <-- McpAuthorizer
   v                               |
McpClientFactory --> McpBearerTokenCodec         |
   |                                             |
   v                                             |
McpServerRegistryAdmin --> McpServerRegistry --> SyncMcpToolCallbackProvider

McpBootstrapRunner --> server mapper + McpServerAdminService startup operation
```

关键约束：Provider 不依赖 Admin；Filter 不依赖 Admin facade；Admin facade 不实现 `ApplicationRunner`。该图消除当前构造环，并让启动、Server、Tool、安全配置各有单一所有者。

### 3.2 Alternatives rejected

- 最小补丁：只把 Filter 改为注入 accessor。虽然能打破循环，但保留 14 依赖 God Class、重复缓存和混合启动职责，不满足 checklist。
- 全量重写 MCP runtime：可以重塑接口，但 `McpServerImpl` 与 Provider 位于多条关键执行流，回归面过大，不适合本次缺陷修复。
- 修改已应用 V17：会导致不同环境 Flyway checksum 分叉，拒绝采用。
- 为 Bearer Token 增加 cipher/IV 两列：数据模型更显式，但本任务已有单 TEXT 兼容约束；版本化文本 envelope 足以安全保存两段二进制数据。

## 4. Contracts

### 4.1 Canonical tool identity

`McpToolUtils` 是唯一命名入口：

```java
String canonicalServerId(String serverName);
String prefixedToolName(String serverId, String toolName);
```

规则：

1. 输入先 `trim`，非法字符连续段替换为 `_`，`-` 转 `_`，连续 `_` 合并并去除首尾 `_`。
2. serverName 或 toolName 清洗后为空时拒绝，不生成 `unknown` 配置键。
3. 基础名称为 `<canonicalServerId>_<canonicalToolName>`。
4. 长度不超过 64；超长时保留前 51 字符，加 `_` 和原完整基础名称 SHA-256 的前 12 个十六进制字符。
5. 相同输入稳定得到相同名称，不使用进程内计数器。

握手派生 serverId、Admin 批量 upsert、Filter 和 callback provider 都调用该入口。V18 增加 `(server_id, tool_name)` 唯一约束；refresh 按该业务键 upsert，可把旧 prefixed name 原地修正，不产生重复工具行。

### 4.2 Authorization and intent

`McpAuthorizer` 注入 `McpToolConfigAccessor`：

```text
canSee(subject, name, requestedIntent)
  = authenticated
    AND config exists
    AND config.enabled == true
    AND effective(config.intent) == effective(requestedIntent)

requireAuthorized(subject, name)
  = authenticated AND config exists AND config.enabled == true
```

`effective(null) = GENERAL_TOOL`。未知、disabled、anonymous、intent 不匹配均 deny。硬调用不依赖之前的 discovery 结果，必须重新读取 accessor。当前没有用户到 MCP 工具的角色映射，因此不虚构额外授权层。

负查询也要缓存：accessor 缓存 `Optional<McpToolConfig>`，避免 unknown 工具在热路径持续访问 DB。所有 Admin 写操作通过 accessor 的单一失效入口清理对应 key 或全部缓存。

### 4.3 Bearer Token envelope

新增 `McpBearerTokenCodec`，独占 `SecretCipher` 的 MCP 编码语义：

```text
v1:<base64(cipher-with-gcm-tag)>:<base64(12-byte-iv)>
```

- `encode(null/blank)` 不允许用于更新接口；“无 token”只由数据库 null 表示。
- master key 不可用时，带 token 的 create/update 抛 `ClientException(BAD_REQUEST)`。
- `decode(null/blank)` 返回 null；其余值必须严格符合 v1 envelope。
- 未知版本、Base64 错误、IV 长度错误、认证标签失败均抛保留 cause 的 `ServiceException(INTERNAL_ERROR)`，消息只说明“Bearer Token 配置不可解密”，不得打印密文或匿名重试。
- 旧的不可解密字符串按损坏配置处理：启动时形成不可连接占位，Admin 重新写 token 后恢复；不隐式信任旧值。

`McpClientFactory` 只依赖 codec，不自行解析密文。client 构建完成但 `initialize()` 失败时必须立即 close，再抛分类异常。

### 4.4 Server lifecycle and ownership

资源所有权规则：

```text
factory create + initialize success
        |
        v
McpServerAdminService owns new client
        |
        +-- DB/derive/registry switch success --> registry owns client
        |
        +-- any failure before switch ----------> admin closes new client

registry atomic replace success --> async close old client
```

- 创建：先插入合法 pending row，再握手派生 serverId；远端握手失败保存 synthetic serverId 和安全 initError 占位。成功后写 `lastConnectedAt=OffsetDateTime.now(UTC)`。
- reconnect/update-token：新 client 初始化成功且 registry 原子替换后，才关闭旧 client；失败不破坏旧快照。
- startup：仅查询 `enabled=true AND auto_connect=true`。`autoConnect=false` 表示应用启动不连接；显式 create 仍允许一次握手以派生 serverId。
- registry close executor 使用 `Thread.ofPlatform().daemon(true).name(...)` 的 ThreadFactory。销毁时先 shutdown，限时 await，超时或中断时 shutdownNow，并恢复 interrupt。
- `initError` 只持有安全摘要，不落内部类名、SQL、token 或完整远端响应。

### 4.5 Forward migration V18

V18 执行以下兼容修复：

1. 删除 V17 `mcp_server_config_state`，允许 `server_id` 与 `init_error` 同时为空的短暂 pending insert。
2. 将非法/空 intent 归一为 `GENERAL_TOOL`，非法/空 risk 归一为 `low`。
3. 增加 intent、risk CHECK 约束。
4. 增加 `(server_id, tool_name)` 唯一约束，支持业务键 batch upsert。

实体 `createdAt`、`updatedAt`、`lastConnectedAt` 全部改为 `OffsetDateTime`；Mapper XML 不做无时区转换。API response 暂时保持 String 字段兼容，但由 `OffsetDateTime.toString()` 输出 offset。

### 4.6 Tool refresh and failure isolation

`McpToolAdminService.refreshTools(serverId)`：

1. 从 registry 查 server；不存在或占位抛 `RemoteException(MCP_SERVER_UNREACHABLE)`。
2. 远端 `listTools()` 异常必须向上分类为 RemoteException，不返回空列表。
3. 一次加载安全配置，按 `toolDescCharLimit` 截断远端描述。
4. 组装全量 `McpToolConfig`，调用 mapper 单次 batch upsert；冲突时只更新远端拥有的字段 `tool_name/prefixed_tool_name/description/updated_at`，保留 ADMIN 拥有的 `enabled/intent/risk/description_override/version`。
5. 成功提交后一次性失效 accessor/provider 缓存。

Provider 遍历 registry 时每个 server 单独 try/catch：某 server 发现失败记录结构化 WARN 并跳过，继续聚合健康 server；全局 duplicate callback name 仍视为配置错误并失败，不能静默覆盖。

### 4.7 Validation and error taxonomy

Controller 所有 `@RequestBody` 增加 `@Valid`。DTO 约束：

- URL 非空且最长 2048；name 最长 256；description 最长 512；token 非空且最长 8192。
- batch ids 非空、最多 1000，每个 id 为正数。
- risk 仅 `low|high`；intent 仅四个 `McpIntent` 名称；descriptionOverride 最长 2000。
- security pattern 最多 100 条、单条最长 512；cap 范围 1..100000；description cap 1..10000。

Jakarta Validation 负责字段级拒绝；service 负责 trim、正则编译和 `highRiskOutputCapChars <= defaultOutputCapChars` 交叉规则。输入错误使用 `ClientException(BAD_REQUEST/VALIDATION_ERROR)`；本地不变量/序列化使用 `ServiceException`；远端网络/协议失败使用 `RemoteException`。所有异常保留 cause，外部消息使用安全中文。

配置键统一读取已有 `mcp.strict-tool-filter`。MCP 主代码不得保留 `IllegalArgumentException`、字符串拼接日志、原始远端错误输出。

### 4.8 Class decomposition

- `McpAdminService`：仅 facade 委托和组合只读响应。
- `McpServerAdminService`：Server CRUD、连接、token 更新、registry 切换。
- `McpToolAdminService`：工具查询、refresh、enable/disable/update、缓存失效。
- `McpSecurityAdminService`：安全配置读取、校验、序列化和更新。
- `McpBootstrapRunner`：yaml 首次导入和 auto-connect 初始化。
- `McpSchemaMapper`：SDK schema 到 core model 的纯映射。
- `McpRemoteCallExecutor`：熔断、错误分类、成功/失败计数模板。

`McpServerImpl` 保持 core `McpServer` 实现与 client 生命周期入口，但将映射和统一远程调用模板委托给上述小组件，使自身低于 checklist 300 行阈值。Provider 的 per-server callback 构建移入独立 adapter 实现，不让 `McpServerImpl` 同时充当 Spring Bean adapter。

## 5. Test Strategy

### 5.1 Unit tests

- canonical name：清洗、空输入、特殊字符、超长稳定 hash、DB/runtime 一致。
- authorizer：anonymous/unknown/disabled/intent mismatch deny；null intent 默认；硬调用重新检查。
- token codec：round-trip、错误版本、坏 Base64、错误 IV、篡改密文、master key 缺失。
- security validation：regex、cap、交叉关系、trim。
- resource ownership：initialize 和 post-create failure 均 close；replace 成功后才 close old。
- provider：单 server 失败不影响健康 server；duplicate name 仍失败。

### 5.2 Slice/contract tests

- MockMvc 验证所有 request DTO 的非法输入不会进入 service。
- Spring context 装配测试验证 Admin/Provider/Filter/Accessor 无循环。
- Mapper XML/SQL 契约测试验证 batch upsert 保留 ADMIN 字段、startup 查询包含 auto_connect。
- Flyway V18 文本契约测试验证只前向修复 V17 约束并增加 CHECK/unique。
- ArchUnit 验证 core 无 starter 依赖和新增服务边界。

### 5.3 Regression gates

每个阶段先运行单测类，再运行 `mvn test -Dtest='com.smart.rag.mcp.**'` 或项目支持的 MCP 定向集合；最后运行 `mvn test`、ArchUnit 和 checklist 静态搜索。禁用的授权/intent 测试必须启用，不允许新增 skip。

## 6. Rollout And Rollback

- V18 只放宽 pending 状态并增加已有枚举/唯一性约束，应用可滚动部署；部署前先让 Flyway 执行。
- 新 token 写入为 v1 envelope；旧值 fail closed。运维恢复方式是通过 Admin API 重设 token，不进行静默迁移。
- 代码回滚到旧版本后，V18 schema 仍兼容旧列结构；旧代码无法解读 v1 token，因此应用代码回滚时也必须回滚/重设 MCP bearer 配置，这是明确的运行手册风险。
- 每个实施阶段保持测试绿；若某阶段失败，只回退该阶段代码，不修改 V17 或删除用户数据。

## 7. Known Residual Risk

- 没有新建 per-user MCP RBAC，因此授权粒度仍是“已认证 + 全局 ADMIN allowlist + intent”。这是产品模型限制，不是本任务遗留实现漏洞。
- 环境可能没有 Docker/PostgreSQL，V18 至少由 SQL 契约测试和 Maven migration 资源扫描保护；若本机可用 PostgreSQL，再追加真实 Flyway 应用验证。
- 旧 bearer 密文不可恢复；这是当前实现已经丢失 IV 导致的事实，修复只能 fail closed 并要求重新录入。
