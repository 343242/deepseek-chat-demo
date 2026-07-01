# Implement：MCP Client 底层 SDK 2.0.0 重构

> PRD/AC：[prd.md](./prd.md)。参照 Spring AI 2.0.0 `mcp/common` 源码，适配 SDK 2.0.0 + Spring AI 1.1.6 核心 API。
> Step 0 已完成（javap 核实），结论见下。

## Step 0 — 开工 Gate：API 核实结论（已完成）

### SDK 2.0.0 实锤 API（javap 核实）

| 类/方法 | 2.0.0 实锤 |
|---|---|
| `McpClient.sync(transport)` | → `SyncSpec` → `.build()` → `McpSyncClient` |
| `SyncSpec` | `.requestTimeout(Duration)` `.clientInfo(Implementation)` `.capabilities()` `.build()` |
| `McpSyncClient` | `.listTools()` → `ListToolsResult`；`.callTool(CallToolRequest)` → `CallToolResult`；`.getClientInfo()` `.getClientCapabilities()` `.getCurrentInitializationResult()` `.initialize()` `.close()` |
| `McpSchema.Tool` | `inputSchema()` 返回 **`Map<String,Object>`**（非 JsonSchema）；有 `.builder(name)` |
| `CallToolRequest` | record + `builder().name().arguments(Map).meta(Map).build()` |
| `CallToolResult` | record + `.content()` `.isError()` |
| `HttpClientStreamableHttpTransport$Builder` | `.openConnectionOnStartup(false)` `.httpRequestCustomizer(Sync)` `.asyncHttpRequestCustomizer(Async)` `.endpoint(String)` `.build()` |
| `McpSyncHttpClientRequestCustomizer` | 签名同 0.18.2：`customize(HttpRequest.Builder, String method, URI, String body, McpTransportContext)` |

### Spring AI 1.1.6 vs 2.0.0 适配 diff（已核实）

1. **`JsonHelper`**：1.1.6 **没有** `org.springframework.ai.util.JsonHelper` → 自实现层用 Jackson `ObjectMapper` 替代。
2. **`JsonSchemaUtils`**：1.1.6 **有** `org.springframework.ai.util.json.schema.JsonSchemaUtils.ensureValidInputSchema(String)`（在 `spring-ai-model`）→ 直接用。
3. **`@Nullable`**：2.0.0 用 `org.jspecify.annotations.Nullable` → 改用项目已有的 `org.springframework.lang.Nullable`。
4. **`Tool.inputSchema()`**：2.0.0 返回 `Map<String,Object>` → `createToolDefinition` 里 `objectMapper.writeValueAsString(tool.inputSchema())` 序列化成 String。

### pom 依赖策略（关键修正）

`mcp:2.0.0` 是 **aggregator（pom-only）**，默认拉 **jackson3**（`tools.jackson.*` 包名，与项目 jackson2 `com.fasterxml.jackson.*` **不兼容冲突**）。正确做法：**直接依赖 `mcp-core` + `mcp-json-jackson2`**，跳过 aggregator。

```xml
<!-- 移除 -->
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-mcp-client</artifactId>
</dependency>
<!-- 新增（不经 mcp aggregator，避免 jackson3 冲突） -->
<dependency>
    <groupId>io.modelcontextprotocol.sdk</groupId>
    <artifactId>mcp-core</artifactId>
    <version>2.0.0</version>
</dependency>
<dependency>
    <groupId>io.modelcontextprotocol.sdk</groupId>
    <artifactId>mcp-json-jackson2</artifactId>
    <version>2.0.0</version>
</dependency>
```

### Spring AI 2.0.0 胶水层完整性确认

已读完全部 13 个文件。**必须实现 8 个**，其余可跳过：
- 跳过：`AsyncMcpToolCallback`/`Provider`（项目不用 async）、`aot/`、`customizer/` 子包。

**Gate 通过**——API 假设全部成立，3 处修正已记录。开始执行。

---

## Step 1 — pom 依赖调整

- [ ] 1.1 移除 `spring-ai-starter-mcp-client` dependency（含 `<exclusions>` 如有）。
- [ ] 1.2 新增 `mcp-core:2.0.0` + `mcp-json-jackson2:2.0.0`（直接依赖，**不用 `mcp` aggregator**，避免 jackson3 冲突）。
- [ ] 1.3 保留 `spring-ai-bom:1.1.6` 在 dependencyManagement（管核心 ToolCallback 版本）。
- [ ] **验证**：`./mvnw -q compile`（预期失败——import 还没切，下一步修）。

## Step 2 — 自实现胶水层（mcp/mcpclient 包）

> 参照 Spring AI 2.0.0 `mcp/common` 源码，适配 SDK 2.0.0 + Spring AI 1.1.6。

- [ ] 2.1 `McpToolFilter` — `BiPredicate<McpConnectionInfo, McpSchema.Tool>` 接口（~5 行）。
- [ ] 2.2 `McpConnectionInfo` — `record(ClientCapabilities, Implementation clientInfo, @Nullable InitializeResult)` + `builder()`（参照 2.0.0）。
- [ ] 2.3 `McpToolNamePrefixGenerator` — `prefixedToolName(conn, tool)` 接口 + `noPrefix()` 工厂。
- [ ] 2.4 `DefaultMcpToolNamePrefixGenerator` — 去重 prefix（`ConnectionId` record + `ConcurrentHashMap`，参照 2.0.0）。
- [ ] 2.5 `McpToolUtils` — `format(name)` 正则清洗 + `createToolDefinition(prefixedName, tool)`：
  - `JsonHelper` → 项目注入 `ObjectMapper`（static 单例即可）。
  - `tool.inputSchema()` 返回 `Map` → `objectMapper.writeValueAsString(map)` 序列化 → `JsonSchemaUtils.ensureValidInputSchema(json)`。
- [ ] 2.6 `SyncMcpToolCallback` — `implements ToolCallback`：
  - `getToolDefinition()` → `McpToolUtils.createToolDefinition(prefixedName, tool)`。
  - `call(String input)` → `call(input, null)`。
  - `call(String input, @Nullable ToolContext ctx)`：
    - `objectMapper.readValue(input, Map.class)` → arguments。
    - `CallToolRequest.builder().name(tool.name()).arguments(map).meta(meta).build()`。
    - `mcpClient.callTool(request)` → `response.isError()` ? 抛 `ToolExecutionException` : `objectMapper.writeValueAsString(response.content())`。
- [ ] 2.7 `SyncMcpToolCallbackProvider` — `getToolCallbacks()`：
  - 遍历 `mcpClients`，每个 `listTools().tools()` stream。
  - `toolFilter.test(connectionInfo(client), tool)` 过滤。
  - `prefixGen.prefixedToolName(conn, tool)` 前缀。
  - `SyncMcpToolCallback.builder()...build()` 组装。
  - 去重校验 + 缓存（`volatile` + `ReentrantLock` double-check）。
  - `connectionInfo(client)` 私有方法：从 `McpSyncClient` 提取 `getClientCapabilities()`/`getClientInfo()`/`getCurrentInitializationResult()`。
- [ ] 2.8 `ToolContextToMcpMetaConverter` — 接口 + `defaultConverter()`（过滤 `TOOL_CONTEXT_MCP_EXCHANGE_KEY` + null，参照 2.0.0）。
- [ ] **验证**：`./mvnw -q compile`（mcpclient 包自洽编译）。

## Step 3 — Transport 装配层

- [ ] 3.1 `McpClientTransportConfiguration`：
  - 读 `spring.ai.mcp.client.streamable-http.connections`（自定义 `@ConfigurationProperties`）。
  - 每条 connection → `HttpClientStreamableHttpTransport.builder(url).openConnectionOnStartup(false).httpRequestCustomizer(bearerCustomizer).build()`。
  - `McpClient.sync(transport).requestTimeout().clientInfo().build()` → `McpSyncClient`。
  - per-client `initialize()` + try/catch fail-soft（单 server 不可达不阻塞）。
  - 注册 `List<McpSyncClient>` bean + `SyncMcpToolCallbackProvider` bean。
- [ ] 3.2 Bearer customizer 迁移（既有 `McpSecurityProperties.bearerTokens` → `McpSyncHttpClientRequestCustomizer` bean，签名不变）。
- [ ] 3.3 `McpToolsChangedEvent`（可选，支持 provider 缓存失效；Phase 1 可先省略，主动拉取策略）。
- [ ] **验证**：`./mvnw -q compile`。

## Step 4 — 既有文件 import 切换

- [ ] 4.1 `McpServerImpl` — `org.springframework.ai.mcp.SyncMcpToolCallbackProvider` → `com.smart.rag.mcp.mcpclient.SyncMcpToolCallbackProvider`。
- [ ] 4.2 `McpServerRegistryImpl` — `SyncMcpToolCallbackProvider` + `McpToolUtils` import 切换。
- [ ] 4.3 `AllowlistMcpToolFilter` — `McpConnectionInfo`/`McpToolFilter`/`McpToolNamePrefixGenerator` import 切换。
- [ ] 4.4 `McpClientConfiguration`（原 prefixGen + bearer bean）→ prefixGen bean 保留（用自实现接口）；bearer customizer 迁到 Step 3 装配层或保留此处。
- [ ] **验证**：`./mvnw -q compile` 全绿。

## Step 5 — ArchUnit + 测试适配

- [ ] 5.1 ArchUnit 规则适配（`org.springframework.ai.mcp` 不再被 import → 规则改为断言 SDK import 纪律：仅 `runtime`+`config` 可 import `io.modelcontextprotocol..`）。
- [ ] 5.2 测试文件 import 切换（4 个测试文件）。
- [ ] 5.3 mock 适配（SDK 2.0.0 builder pattern：`CallToolResult` mock 改用 builder 或 record 构造器）。
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
