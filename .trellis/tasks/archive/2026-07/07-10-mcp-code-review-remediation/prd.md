# 修复 MCP 模块代码审查问题

## Goal

修复 `src/main/java/com/smart/rag/mcp` 在 2026-07-10 checklist 审查中确认的全部安全、数据库、运行时、资源管理、配置和可维护性问题，使 MCP Admin、工具发现、工具调用和 Bearer 鉴权链路可启动、可配置、可隔离故障且有回归测试保护。

## User Value

- MCP 配置能够在真实 PostgreSQL schema 上创建和启动，不再被约束或 Bean 循环阻断。
- ADMIN 启用的工具按统一名称、配置意图和认证边界准确暴露，不会被错误拒绝或越权调用。
- Bearer Token 可正确加解密，损坏密文不会静默降级为匿名连接。
- 单个远端 MCP Server 故障不会拖垮其他健康 Server，失败资源能够可靠释放。
- Admin 输入、错误响应、缓存、批量 I/O 和时间类型符合 backend checklist。

## Confirmed Facts

1. `McpAuthorizer` 只检查 `Subject.isAuthenticated()`，不检查 DB enabled 状态或 `McpIntent`；对应授权与 intent 测试被禁用。
2. `McpAdminService -> SyncMcpToolCallbackProvider -> DatabaseToolFilter -> McpAdminService` 构成构造依赖环。
3. V17 要求 `server_id IS NOT NULL OR init_error IS NOT NULL`，但 create/bootstrap 首次 INSERT 两者都为 null。
4. DB upsert 使用会缩写 serverId 的 `McpToolUtils.prefixedToolName`，运行时 generator 使用完整 serverId，strict filter 无法命中。
5. Bearer Token 存储丢弃 AES-GCM IV，并把二进制密文直接解释为 UTF-8；解密失败后返回 null 并匿名连接。
6. Provider 在一个全局循环中发现所有 Server 工具，单个 `listTools()` 异常会使所有 `visibleTo()` 返回空集。
7. Admin Request DTO 与 Controller 缺少 Jakarta Validation；负 cap、非法 regex、空批量 ID、非法 intent/risk 可进入运行时。
8. `McpClientFactory` initialize 失败以及 Admin 初始化后续步骤失败时可能泄漏 client/transport。
9. refresh-tools 吞掉远端异常，并在循环内逐工具 select/insert。
10. `autoConnect` 不参与启动查询，`lastConnectedAt` 从未更新；TIMESTAMPTZ 使用 `LocalDateTime`。
11. `McpAdminService` 554 行且有 14 个构造依赖，`McpServerImpl` 405 行；缓存失效与命名逻辑存在重复和漂移。
12. 模块仍包含禁用的 `IllegalArgumentException`、原始内部错误泄露、拼接日志、配置 key 不匹配、死代码和过时注释。
13. 当前 MCP 定向测试为 60 passed / 2 skipped；缺少真实 schema、完整 Spring 上下文、token round-trip、filter/admin 集成和多 Server 故障隔离测试。

## Requirements

### R1: 授权与意图路由

- 工具可见性必须同时要求：主体已认证、DB 配置存在且 enabled、配置 intent 与请求 intent 匹配。
- intent 为空时使用明确且有测试的 `GENERAL_TOOL` 默认值。
- `McpTools.call` 的硬授权必须重新检查 DB enabled，禁止绕过发现层直接调用 disabled/unknown 工具。
- 当前产品没有 per-user MCP RBAC 数据模型；本任务不虚构角色映射，授权边界保持“已认证主体 + ADMIN 管理的全局工具 allowlist + intent”。

### R2: 统一工具命名契约

- serverId 清洗、prefixed tool name 生成和长度处理必须只有一个 canonical 实现。
- Admin 入库、DatabaseToolFilter、Provider callback 和 `McpServerImpl` 前缀检查必须使用同一结果。
- 同名、空白、超长和特殊字符输入必须确定性处理并有测试。

### R3: Bean 边界与职责拆分

- 消除 Admin/Provider/Filter 构造循环，Filter 直接依赖只读配置 accessor，不依赖 Admin facade。
- 将启动 bootstrap、Server 管理、Tool 管理、安全配置从 God Class 中拆出；Controller 可保留小型 facade 以避免 API 扩散。
- 每个新增组件保持单一职责，避免新增无实际用途的策略层或依赖。

### R4: 数据库与连接状态

- 使用 Flyway 前向迁移修复 V17 状态约束，不直接依赖修改已应用 migration。
- `autoConnect=false` 的 Server 不在应用启动时自动连接；创建时仍允许进行一次握手以派生 serverId，并在文档中明确语义。
- 成功 initialize/reconnect 后更新 `lastConnectedAt`，失败时保存安全、可诊断但不泄露内部类名的 `initError`。
- TIMESTAMPTZ 映射和 API 输出使用带 UTC/offset 语义的时间类型。

### R5: Bearer Token

- 使用可逆、带版本可能性的标准编码保存 AES-GCM cipher 与 IV；不得把随机二进制直接转 UTF-8。
- 密文缺失表示无 token；密文格式错误、IV 缺失或解密失败必须 fail closed，不能匿名重试。
- master key 不可用且请求包含 token 时必须拒绝创建/更新。
- 现有不可解密旧值通过明确兼容策略处理，不隐式信任或继续使用。

### R6: 故障与资源隔离

- 每个 Server 的工具发现独立失败；健康 Server 仍返回工具。
- Admin refresh 失败必须向调用方返回 RemoteException，不能以空列表伪装成功。
- initialize、派生 ID、写库或 registry 替换失败时关闭新建 client；旧 client 只在原子切换后关闭。
- close executor 使用项目允许的 ThreadFactory，`@PreDestroy` 执行 shutdown、限时等待和必要的 `shutdownNow()`。

### R7: 输入、异常与配置

- 所有 Admin Request 使用 `@Valid` 和 Jakarta Validation；字符串 trim，并限制长度、集合大小、正数范围、intent/risk 枚举值。
- security config 在写库前验证 regex、cap 范围和 `highRiskOutputCap <= defaultOutputCap`。
- Controller 不增加 try-catch；异常使用 Client/Service/Remote 三级体系并保留 cause。
- 面向用户/LLM 的错误为中文安全消息，不包含内部类名、SQL、token 或远端原始敏感详情。
- 修正 `mcp.strict-tool-filter` 配置绑定，移除禁用的 `IllegalArgumentException` 和字符串拼接日志。

### R8: 性能、缓存与代码质量

- refresh-tools 使用批量读取/批量 upsert，不在工具循环内发 SQL，并保留 ADMIN 配置字段。
- 缓存有容量上限，远端 description 在入缓存/DB 前封顶；所有配置更新具有单一失效入口。
- 删除死代码、重复失效逻辑和与当前行为矛盾的注释。
- 公开方法和关键异常路径有单元测试；数据库、Spring 装配和跨组件链路有集成或契约测试。

## Acceptance Criteria

- [ ] 完整 Spring MCP 配置上下文可启动，无循环依赖。
- [ ] V17 后续迁移应用后，create/bootstrap 首次 INSERT 合法；已有 schema 可前向升级。
- [ ] Admin 刷新并启用工具后，strict filter 使用完全相同的 prefixed name 并返回工具。
- [ ] authenticated + enabled + matching intent 可见；disabled、unknown、intent mismatch、anonymous 均不可见。
- [ ] 直接调用 disabled/unknown 工具抛 `ClientException(FORBIDDEN)`，不触发远端 client。
- [ ] Bearer Token encrypt/decrypt round-trip 通过，Authorization header 使用原始明文。
- [ ] 损坏/旧格式 token 解密失败时不建立匿名连接，并返回分类正确的异常。
- [ ] 单个 Server `listTools()` 失败时其他 Server 工具仍可用。
- [ ] refresh-tools 远端失败返回 RemoteException；成功刷新只执行批量 DB 操作。
- [ ] initialize 和初始化后失败路径均验证新 client 被关闭。
- [ ] `autoConnect=false` 不参与启动连接；成功连接写入非空 UTC/offset `lastConnectedAt`。
- [ ] 非法 URL/空 token/空 IDs/非法 risk/intent/regex/非正 cap/交叉 cap 关系均在 Controller 或 Service 边界拒绝。
- [ ] 远端 description 和工具输出均有有效正数上限，配置错误不会导致热路径 substring/regex 异常。
- [ ] 无 `@Transactional`、`BusinessException`、`IllegalArgumentException`、`new Thread`、拼接式日志残留于 MCP 主代码。
- [ ] `McpAdminService` 不再是 300+ 行/8+ 依赖 God Class；`McpServerImpl` 的职责拆分至少使各类小于 checklist 阈值，或在设计中给出不可拆理由并由测试保护。
- [ ] 所有新增回归测试先红后绿；MCP 定向测试、ArchUnit、全量 Maven 测试通过且无新增 skip。
- [ ] 重新按 `.trellis/spec/backend/code-review-checklist.md` 逐项复审，不保留 HIGH/CRITICAL 问题。

## Compatibility And Scope

- 本任务整合并完成 `07-07-bearer-token-storage-format` 与 `07-07-mcp-policy-db-migration` 当前代码暴露出的缺口，但不修改那两个任务的现有记录。
- 不新增依赖，不创建新的 per-user MCP RBAC/审批产品模型，不改变 Admin REST 路径和 `GlobalResponse` 格式。
- 不重写 MCP SDK 或通用 circuit breaker；只修复当前模块边界和已确认问题。

## Decisions

- 按 V17 可能已在环境中应用处理：保留 V17 不变，新增 V18 前向迁移修复状态约束。
- Bearer Token 沿用单 TEXT 列，但改为带格式版本的 Base64 cipher/IV 编码；不新增 schema 列。
- 技术方案采用“边界修复 + 有针对性的职责拆分”：保留 Controller、core 接口和 Admin facade 的外部契约，拆分内部职责并通过回归测试保护。
- 用户要求在规划后直接按测试先行修复全部审查问题；本任务按该持续授权进入实施，不再设置额外确认点。
