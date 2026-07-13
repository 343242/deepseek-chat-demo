# MCP Policy DB Migration — risk/intent/descriptionOverride 从 yaml 迁到 DB

## Goal

把 `McpSecurityGuard.risk(name)` + `McpDescriptionSanitizer.descriptionOverride(name)` 从读 yaml `McpToolPolicy` 改为读 DB `mcp_tool_config` 表；删除 `McpToolPolicy` + `McpSecurityProperties`；彻底完成 v4 design 的"DB 唯一事实源"目标。

## User Value

- **彻底清除 yaml 残留**：当前 McpSecurityGuard 仍读 `mcp.policy.tools.<name>.risk`，与 v4 design "DB 唯一事实源" 原则冲突
- **简化配置**：删除 2 个 Properties 类 + 对应 yaml 配置块，配置入口唯一化（DB）

## Confirmed Facts

1. **Phase 5 现状**：`McpSecurityGuard` 注入 `McpToolPolicy`（读 `risk`）；`McpDescriptionSanitizer` 注入 `McpToolPolicy`（读 `descriptionOverride`）
2. **DB 表已就位**：`mcp_tool_config` 表含 `risk` / `intent` / `description_override` 字段（V17 迁移）
3. **`McpAdminService.upsertToolConfig`**：当前 UPSERT 时 `risk` 默认 `low`，ADMIN 经 `POST /api/admin/mcp/tools/{id}/update` 显式设置
4. **`McpToolPolicy`**：被 `McpSecurityGuard` / `McpDescriptionSanitizer` / `McpAuthorizer`（authz 已注释）三处引用
5. **`McpSecurityProperties`**：仅 `McpAdminService.bootstrapFromYaml()` 启动期读 bearer tokens

## Requirements

### R1: McpSecurityGuard 改 DB 驱动
- 移除 `McpToolPolicy` 注入
- 新增 `McpToolConfigAccessor`（独立 Bean，缓存 `Map<prefixedName, McpToolConfig>`，TTL 10min + admin invalidate）
- `risk(name)` → `accessor.get(name).risk()`（DB 缺省 `low`）

### R2: McpDescriptionSanitizer 改 DB 驱动
- 移除 `McpToolPolicy` 注入
- `descriptionOverride(name)` → `accessor.get(name).descriptionOverride()`

### R3: 删除 McpToolPolicy
- 删除 `McpToolPolicy.java` + `McpToolPolicyTest.java`
- 删除 yaml `mcp.policy.*` 配置块

### R4: 删除 McpSecurityProperties
- `McpAdminService.bootstrapFromYaml()` 改为不读 bearer token（ADMIN 经 REST API 配置）
- 删除 `McpSecurityProperties.java` + test
- 删除 yaml `mcp.security.*` 配置块

### R5: McpAuthorizer 占位保留
- `McpAuthorizer` authz 已注释（"临时关闭 mcp.policy authz"）；本期保留占位（authz 重启需 role source，后续 task）

### R6: McpClientTransportProperties 保留
- 保留作 bootstrap（connections URL 清单）；仅文档化"运行时无 Bean 注入"

## Acceptance Criteria

- [ ] `McpSecurityGuard` 不再注入 `McpToolPolicy`
- [ ] `risk(name)` 从 `McpToolConfigAccessor` 读取
- [ ] ADMIN 改 tool `risk` 后立即生效（accessor.invalidate）
- [ ] `McpDescriptionSanitizer` 不再注入 `McpToolPolicy`
- [ ] `descriptionOverride` 从 DB 读取
- [ ] `McpToolPolicy.java` 删除
- [ ] `McpSecurityProperties.java` 删除
- [ ] yaml `mcp.policy.*` / `mcp.security.*` 配置块删除
- [ ] 全量测试通过

## Out of Scope

1. McpClientTransportProperties 删除（保留作 bootstrap）
2. McpAuthorizer authz 重启（需 role source）
3. McpToolConfigAccessor 与 McpAdminService.toolListCache 合并

## Notes

- base branch: `agentic-rag-dev`
- 改动量：~8-12 文件（删 3 + 改 5 + 新增 1-2）

## Notes

- Keep `prd.md` focused on requirements, constraints, and acceptance criteria.
- Lightweight tasks can remain PRD-only.
- For complex tasks, add `design.md` for technical design and `implement.md` for execution planning before `task.py start`.
