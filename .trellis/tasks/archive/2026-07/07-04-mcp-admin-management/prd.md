# MCP Admin Management — 从配置驱动改为ADMIN统一管理

## Goal

将MCP模块从配置驱动改为ADMIN统一管理，实现MCP Server连接、工具策略、安全配置的完整CRUD管理，支持热重载和工具级别启用/禁用。

## User Value

- **系统管理员**：通过REST API统一管理MCP配置，无需修改yaml重启应用
- **运维人员**：实时监控MCP Server状态，手动刷新工具列表，快速故障排查
- **安全管理员**：细粒度控制工具启用/禁用，SSRF防护，安全配置动态调整

## Confirmed Facts

1. **当前架构**：McpToolPolicy/McpSecurityProperties使用@ConfigurationProperties从yaml读取
2. **数据库基础设施**：项目已有PostgreSQL + MyBatis-Plus，复用成本低
3. **RBAC体系**：已有ADMIN角色，可直接复用
4. **MCP SDK**：使用MCP SDK 2.0.0，支持同步客户端
5. **熔断器**：已有McpCircuitBreakerRegistry，支持per-ServerId熔断
6. **缓存模式**：项目使用Caffeine直接Cache<K,V> + StringRedisTemplate，不用CacheManager/@Cacheable
7. **事务控制**：项目使用TransactionTemplate显式控制，不使用@Transactional
8. **ORM规范**：MyBatis-Plus，SQL全部在XML中，不使用注解SQL
9. **单实例部署**：应用本身即MCP Client，无需跨实例通知

## Requirements

### R1: MCP Server连接管理
- **R1.1** ADMIN可创建MCP Server连接（指定URL、名称、描述）
- **R1.2** ADMIN可删除MCP Server连接（级联删除工具配置）
- **R1.3** ADMIN可修改MCP Server连接配置（URL、描述等）
- **R1.4** ADMIN可查询所有MCP Server连接列表
- **R1.5** ADMIN可启用/禁用MCP Server（级联禁用其下工具）
- **R1.6** ADMIN可触发MCP Server重新连接（热重载）

### R2: 工具管理
- **R2.1** 系统启动时自动拉取MCP Server工具列表
- **R2.2** ADMIN可手动刷新MCP Server工具列表
- **R2.3** ADMIN可查看MCP Server提供的所有工具
- **R2.4** ADMIN可启用/禁用单个工具
- **R2.5** 工具配置包括：allowlist、routing intent、risk level、description override

### R3: 安全配置管理（DB 为唯一事实源）
- **R3.1** ADMIN可管理敏感参数regex列表
- **R3.2** ADMIN可管理输出字符上限（default/high-risk）
- **R3.3** ADMIN可管理工具描述字符上限
- **R3.4** ADMIN可按 MCP Server 粒度管理 Bearer Token（加密存 DB，更新后触发该 Server client 重建）
- **R3.5** `McpSecurityProperties`（yaml）降级为启动时 bootstrap 数据源——首次启动导入 DB 后，运行时任何组件不再注入该 Properties，统一从 DB（经 Caffeine 缓存）读取
- **R3.6** `McpSecurityGuard` 改为从 `McpAdminService.getSecurityConfig()` 读取（缓存命中即返回）

### R4: 数据存储
- **R4.1** 配置存储到PostgreSQL数据库
- **R4.2** yaml配置作为初始化数据，ADMIN可覆盖（数据库优先）
- **R4.3** ADMIN 写操作经通用 `@AdminAudit` AOP 记录到 `admin_audit_log` 表（详见 R7）
- **R4.4** 首次启动时从yaml导入配置到数据库（如果数据库为空）
- **R4.5** ORM使用MyBatis-Plus，所有SQL在XML中管理，不使用注解SQL

### R5: API设计
- **R5.1** 仅使用GET、POST方法
- **R5.2** 复用现有ADMIN权限（@PreAuthorize("hasRole('ADMIN')")）
- **R5.3** SSRF防护：复用infrastructure/security的BaseUrlValidator

### R6: 运行时行为
- **R6.1** 热重载：ADMIN操作后立即生效，无需重启
- **R6.2** 重连期间工具调用抛异常："MCP链接断开正在重新连接"（基于 `init_error` 字段，registry 中保留占位 McpServer 而非直接移除）
- **R6.3** Server禁用时级联禁用其下所有工具
- **R6.4** 配置变更时自动更新熔断器状态
- **R6.5** 单级缓存策略：Caffeine 本地缓存 + TTL 兜底（`expireAfterWrite=10min`）+ ADMIN 操作主动 invalidate。**不引入 Redis 层**（单实例 + 工具列表为 KB 级数据，Redis 层属过度设计）
- **R6.6** 缓存失效：ADMIN操作时主动失效（单实例，无需跨实例通知）
- **R6.7** 事务控制：使用TransactionTemplate显式控制，不使用@Transactional
- **R6.8** 软失败语义：DB commit 成功但运行时（client 创建/握手）失败时，UPDATE `init_error` 字段记录失败原因，不回滚 DB；下次启动或重连时重试
- **R6.9** 乐观锁：`McpServerConfig` / `McpToolConfig` 加 `@Version` 字段，并发更新冲突时抛 `OptimisticLockingFailureException`
- **R6.10** 重连限流：per-serverId 30 秒内最多 1 次 reconnect（Caffeine 限流器），超出返回 429

### R7: 通用审计（基础设施层）
- **R7.1** 新建 `infrastructure/audit/` 包，提供通用 `@AdminAudit` 注解 + AOP 切面
- **R7.2** 通用 `admin_audit_log` 表（不绑定 MCP 模块），字段：operator_id/name/role、resource_type、resource_id、action、request_payload(jsonb)、result_status、error_code/message、ip、user_agent、duration_ms、created_at
- **R7.3** AOP 自动捕获：操作者（SecurityContext）、IP/UA（RequestContextHolder）、耗时、成功/失败、错误码
- **R7.4** 异步写入（专用单线程 executor），不阻塞 ADMIN 响应
- **R7.5** 敏感字段自动脱敏（注解 `sensitiveFields` 声明）
- **R7.6** 可被任何模块复用：MCP（server/tool/security 变更）、LLM（model 配置变更）、RAG（pipeline 参数变更）等

## Acceptance Criteria

### AC1: MCP Server CRUD
- [ ] ADMIN可创建MCP Server连接，URL经过SSRF验证（复用BaseUrlValidator）
- [ ] ADMIN可删除MCP Server连接，级联删除工具配置
- [ ] ADMIN可修改MCP Server连接配置
- [ ] ADMIN可查询所有MCP Server连接列表（含健康状态）
- [ ] ADMIN可启用/禁用MCP Server，级联影响工具状态

### AC2: 工具管理
- [ ] 系统启动时自动拉取工具列表并存入数据库
- [ ] ADMIN可手动刷新工具列表，从远端MCP Server拉取
- [ ] ADMIN可查看MCP Server的所有工具（含启用状态）
- [ ] ADMIN可启用/禁用单个工具，立即生效
- [ ] 工具配置支持allowlist、intent、risk、description

### AC3: 安全配置
- [ ] ADMIN可管理敏感参数regex列表
- [ ] ADMIN可管理输出字符上限
- [ ] 配置变更立即生效，无需重启

### AC4: 热重载
- [ ] ADMIN操作后配置立即生效
- [ ] 重连期间工具调用返回错误提示（registry 保留占位 McpServer 标记 init_error，而非直接移除）
- [ ] 配置变更不影响其他MCP Server
- [ ] 重连限流：per-serverId 30s 内重复请求返回 429

### AC5: 权限控制
- [ ] 仅ADMIN角色可访问管理API
- [ ] 非ADMIN访问返回403 Forbidden

### AC6: SSRF防护
- [ ] 创建连接时验证URL格式（复用BaseUrlValidator）
- [ ] 禁止内网IP地址
- [ ] 禁止危险scheme

### AC7: 技术规范
- [ ] 所有SQL在XML文件中管理，不使用注解SQL
- [ ] 事务使用TransactionTemplate显式控制，不使用@Transactional
- [ ] 缓存使用 Caffeine `Cache<K,V>` 单级 + TTL，不使用 CacheManager、不引入 Redis 工具缓存层
- [ ] Entity使用MyBatis-Plus注解（@TableName, @TableId, @Version），不使用JPA注解

### AC8: 通用审计（基础设施层）
- [ ] `infrastructure/audit/` 包提供 `@AdminAudit` 注解 + AOP 切面 + `admin_audit_log` 表
- [ ] MCP 模块所有写操作（create/update/delete/enable/disable/reconnect/refreshTools/updateBearerToken/updateSecurityConfig）标注 `@AdminAudit`
- [ ] 异步写入，不阻塞响应；失败时仍记录 FAILURE 行
- [ ] 敏感字段（bearerToken 等）在 request_payload 中自动脱敏
- [ ] `admin_audit_log` 表设计通用，未来 LLM/RAG 等模块可直接复用

## Out of Scope

1. **前端管理页面**：当前仅提供REST API
2. **配置迁移工具**：手动迁移yaml配置到数据库（仅首次启动自动 bootstrap）
3. **多租户隔离**：MCP配置全局共享
4. **配置回滚**：暂不支持配置版本回滚（审计日志仅记录变更历史，不支持自动 revert）
5. **批量操作**：暂不支持批量启用/禁用（工具的批量 enable/disable 例外，见 R2）
6. **跨实例通知**：单实例部署，无需 Redis Pub/Sub
7. **Micrometer 自定义指标**：本期不做 cache hit/miss、reconnect count 等指标埋点，后续单独 task
8. **DNS rebinding 防护**：BaseUrlValidator 仅在配置时校验，transport 层不做运行时 IP 复检，后续单独 task

## Open Questions

1. **yaml初始化优先级**：yaml配置与数据库配置冲突时，哪个优先？
   - **决策**：DB 为唯一事实源；yaml 仅在 DB 为空时 bootstrap 导入

2. **重连超时时间**：热重连操作的超时时间？
   - **决策**：固定30秒

3. **工具列表缓存**：工具列表是否需要本地缓存？
   - **决策**：单级 Caffeine 缓存 + TTL（`expireAfterWrite=10min`），ADMIN 操作主动 invalidate
   - **不引入 Redis 层**：单实例 + KB 级数据，Redis 层属过度设计（v3 修订）

4. **LLM调用时工具加载**：工具列表从哪里加载？
   - **决策**：Caffeine 缓存 → DB（无 Redis 中间层）
   - **刷新**：ADMIN手动刷新或系统启动时自动拉取远端工具

5. **Bearer Token 存储**：DB 还是 yaml？
   - **决策**：DB（v3 修订，推翻 v2 的"留 yaml"决策）
   - **加密**：复用下沉到 `infrastructure/security` 的 `SecretCipher`（原 `ApiKeyCipher`，加密 key 走 `SecurityCryptoProperties`）
   - **粒度**：per-server（`mcp_server_config.bearer_token_encrypted` 字段），变更触发该 server client 重建

## Notes

- Keep `prd.md` focused on requirements, constraints, and acceptance criteria.
- Lightweight tasks can remain PRD-only.
- For complex tasks, add `design.md` for technical design and `implement.md` for execution planning before `task.py start`.
