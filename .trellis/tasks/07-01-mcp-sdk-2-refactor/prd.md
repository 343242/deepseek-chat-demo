# MCP Client 底层 SDK 2.0.0 重构 — 脱离 Spring AI MCP starter，自实现胶水层

> 父任务：[`06-28-mcp-client-phase1`](../06-28-mcp-client-phase1/)。
> 前置切片：`07-01-mcp-security-loop`（Phase 2 安全闭环已落 `4076010`）。
> 根因：MCP Java SDK 0.18.2 issue #773（POST 成功后自动发 GET → 405），Spring AI 1.1.6 starter 锁死 0.18.2 无法升级。

## Goal

去掉 `spring-ai-starter-mcp-client`（锁死 SDK 0.18.2 + autoconfig），直接依赖 MCP Java SDK **2.0.0**（已修复 #773），参照 Spring AI 2.0.0 的 `mcp/common` 自实现等价胶水层。项目领域内核（core/policy/adapter）零改。

## Requirements

### R1 — pom 依赖结构调整
- **移除** `spring-ai-starter-mcp-client`（传递依赖 0.18.2 SDK + autoconfig jar）。
- **新增** `io.modelcontextprotocol.sdk:mcp:2.0.0`（直接依赖，不经 Spring AI BOM）。
- **保留** `spring-ai-bom:1.1.6`（管核心 ToolCallback/FunctionToolCallback 等版本）。

### R2 — 自实现胶水层（参照 Spring AI 2.0.0 `mcp/common`）
在 `mcp/mcpclient` 包新建，对接 MCP SDK 2.0.0 + Spring AI 1.1.6 核心 API：
- `McpToolFilter`（BiPredicate）、`McpConnectionInfo`（record）、`McpToolNamePrefixGenerator`（接口）。
- `DefaultMcpToolNamePrefixGenerator`（去重 prefix）。
- `McpToolUtils`（`format()` 清洗 + `createToolDefinition()`）。
- `SyncMcpToolCallback`（McpSyncClient → Spring AI ToolCallback 桥接，`call()` 内 `callTool(CallToolRequest.builder()...)`）。
- `SyncMcpToolCallbackProvider`（遍历 `listTools()` → 组装 ToolCallback[]，带缓存）。

### R3 — Transport 装配层（替代 starter autoconfig）
`McpClientTransportConfiguration`：读 yaml connections → `HttpClientStreamableHttpTransport.builder()` → `McpSyncClient.create()` → per-client init（fail-soft 隔离）。

### R4 — 既有文件 import 切换
4 个文件的 `org.springframework.ai.mcp.*` import 切到自实现包。

### R5 — Bearer customizer 迁移
既有 `McpSyncHttpClientRequestCustomizer` bean（bearer auth）迁到新装配层，SDK 2.0.0 API 适配。

### R6 — 领域内核零改
- `mcp/core` — 纯领域接口，零 SDK 依赖，不动。
- `mcp/policy` — authz/guard/sanitizer，零 SDK 依赖，不动。
- `mcp/adapter` — 产出 Spring AI ToolCallback（1.1.6 `FunctionToolCallback.builder()`），不碰 MCP SDK。

## Acceptance Criteria

- [ ] **AC1** `./mvnw -q compile`：移除 starter 后零编译错误。
- [ ] **AC2** `./mvnw -q -Dtest='Mcp*Test' test` 全绿（含 ArchUnit 依赖纪律）。
- [ ] **AC3** pom 无 `spring-ai-starter-mcp-client`、有 `mcp:2.0.0`。
- [ ] **AC4** 生产代码零 `org.springframework.ai.mcp.*` import（全部切到自实现包）。
- [ ] **AC5** ArchUnit 6.1/6.2/6.3/6.4 全绿（依赖纪律规则可能需微调包路径）。
- [ ] **AC6** Tavily 远程 MCP 真协议连通：启动 → 工具发现 → 不再 405。
- [ ] **AC7** commit + push。

## Constraints

- Spring Boot 3.5.14 / Spring AI 核心 1.1.6 / Java 21 / MCP SDK 2.0.0。
- 不伪造 API——2.0.0 源码 + javap 实锤为准。
- 改 symbol 前跑 `impact`；提交前确认改动范围。
- ArchUnit 规则改动需在 design.md 记录理由。
