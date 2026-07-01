# Implement：MCP Client 底层 SDK 2.0.0 重构

> PRD/AC：[prd.md](./prd.md)。参照 Spring AI 2.0.0 `mcp/common` 源码，适配 SDK 2.0.0 + Spring AI 1.1.6 核心 API。

## Step 0 — 开工 Gate：核实 SDK 2.0.0 API

- [ ] 0.1 `mvn dependency:resolve` 拉 `io.modelcontextprotocol.sdk:mcp:2.0.0` 到本地仓库。
- [ ] 0.2 `javap` 核实 2.0.0 关键 API：`McpSyncClient`、`CallToolRequest`、`McpSchema.Tool`、`HttpClientStreamableHttpTransport$Builder`。
- [ ] **Gate**：确认 API 签名与调研报告一致，否则调整设计。

## Step 1 — pom 依赖调整

- [ ] 1.1 移除 `spring-ai-starter-mcp-client` dependency。
- [ ] 1.2 新增 `io.modelcontextprotocol.sdk:mcp:2.0.0`（直接，不经 BOM）。
- [ ] 1.3 保留 `spring-ai-bom:1.1.6` 在 dependencyManagement。
- [ ] **验证**：`./mvnw -q compile`（预期失败——import 还没切，下一步修）。

## Step 2 — 自实现胶水层（mcp/mcpclient 包）

- [ ] 2.1 `McpToolFilter` — `BiPredicate<McpConnectionInfo, McpSchema.Tool>` 接口。
- [ ] 2.2 `McpConnectionInfo` — record(clientCapabilities, clientInfo, initializeResult)。
- [ ] 2.3 `McpToolNamePrefixGenerator` — `prefixedToolName(conn, tool)` 接口 + `noPrefix()` 工厂。
- [ ] 2.4 `DefaultMcpToolNamePrefixGenerator` — 去重 prefix（参照 2.0.0 源码）。
- [ ] 2.5 `McpToolUtils` — `format(name)` 清洗 + `createToolDefinition(tool, prefixedName)`（JsonHelper → ObjectMapper）。
- [ ] 2.6 `SyncMcpToolCallback` — ToolCallback 实现：`getToolDefinition()` + `call(input, ctx)` → `mcpClient.callTool(CallToolRequest.builder()...)`。
- [ ] 2.7 `SyncMcpToolCallbackProvider` — `getToolCallbacks()` 遍历 `listTools()` + filter + prefix + 去重 + 缓存。
- [ ] **验证**：`./mvnw -q compile`（mcpclient 包自洽编译）。

## Step 3 — Transport 装配层

- [ ] 3.1 `McpClientTransportConfiguration` — 读 connections yaml → `HttpClientStreamableHttpTransport.builder()` → `McpSyncClient`。
- [ ] 3.2 per-client init + fail-soft（try/catch 隔离，单个 server 不可达不阻塞）。
- [ ] 3.3 Bearer customizer 迁移（SDK 2.0.0 的 `McpSyncHttpClientRequestCustomizer` 适配）。
- [ ] 3.4 `McpToolsChangedEvent` 事件（支持 provider 缓存失效）。
- [ ] **验证**：`./mvnw -q compile`。

## Step 4 — 既有文件 import 切换

- [ ] 4.1 `McpServerImpl` — `SyncMcpToolCallbackProvider` import 切到 mcpclient。
- [ ] 4.2 `McpServerRegistryImpl` — `SyncMcpToolCallbackProvider` + `McpToolUtils` import 切换。
- [ ] 4.3 `AllowlistMcpToolFilter` — `McpConnectionInfo`/`McpToolFilter`/`McpToolNamePrefixGenerator` import 切换。
- [ ] 4.4 `McpClientConfiguration`（原 prefixGen + bearer customizer bean）—— 评估是否合并进新装配层或保留。
- [ ] **验证**：`./mvnw -q compile` 全绿。

## Step 5 — ArchUnit + 测试适配

- [ ] 5.1 ArchUnit 规则包路径适配（`org.springframework.ai.mcp` → 自实现包；或放宽为 SDK import 纪律）。
- [ ] 5.2 测试文件 import 切换（4 个测试文件）。
- [ ] 5.3 mock 适配（SDK 2.0.0 的 builder pattern：`CallToolResult.builder()` 等）。
- [ ] **验证**：`./mvnw -q -Dtest='Mcp*Test' test` 全绿。

## Step 6 — 真协议验证 + 提交

- [ ] 6.1 启动应用连 Tavily（`export TAVILY_API_KEY=...`），确认不再 405、工具发现成功。
- [ ] 6.2 全量 `./mvnw test`。
- [ ] 6.3 确认改动范围（无意外扩散到 agent/chat/rag）。
- [ ] 6.4 commit `feat(mcp): 脱离 Spring AI MCP starter，直接依赖 MCP SDK 2.0.0 + 自实现胶水层` + push。

## 审查门 / 回滚点

- **Gate A**（Step 2 后）：mcpclient 包自洽编译 → 胶水层就位。
- **Gate B**（Step 4 后）：全量编译绿 → import 切换完成。
- **Gate C**（Step 5 后）：MCP 测试全绿 → 可提交。
- **回滚**：`git revert`；或 pom 回退 + 重新引入 starter。
