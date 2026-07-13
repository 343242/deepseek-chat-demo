# Implement：MCP Client 接入 Phase 1（本轮：模块孤立）

> 有序 checklist。本轮**只建 `mcp/*` 模块 + 测试，不接对外注入点**（不动 `AgentToolCallbackFactory`/`AgentModeStrategy`/`ToolAutoConfiguration`）。每步附验证命令与回滚点。Gate：Step 0 ①② 过再写 runtime。
>
> 设计事实来源：`docs/MCP-CLIENT-INTEGRATION.md`（已对齐 1.1.6 `javap` 实锤 + 本轮 A1/B1/B3/C/D 决议）。包分层：`core`(接口+模型,零 starter 依赖) / `runtime`(实现,持 client+provider) / `adapter`(→ToolCallback) / `policy`(authz+规则,纯领域) / `config`(装配+filter+customizer) / `health`(fail-soft)。

## Step 0 — 运行时验证（前置 Gate：①②）

> ③已验（PRD:41）；⑤本轮可并入。**不验刷新闭环**（主动拉取策略，不依赖可选 `list_changed`）。

- [ ] 0.1 启动期 server 不可达：配坏 URL，观察 provider 产出（空/抛/阻塞）→ 记 PRD ①；**决定 fail-soft wrapper 走方案 A（门面 try/catch）还是 B（自定义 client bean）**（见 MCP 文档 §11）
- [ ] 0.2 握手失败/运行期失联：不可达/非 MCP 响应，观察降级 → 记 PRD ②
- [ ] 0.3 `enabled` 默认值 + 无 connections 启动行为 → **已坐实**（PRD ③）
- [ ] 0.5 MCP 不漏全局链（D1，弱保护）：核实全局 `ToolCallingManager`（`ToolAutoConfiguration:39`，`StaticToolCallbackResolver(ToolRegistry only)`）不含 MCP 前缀工具；写断言（仅文档化不变量）
- [ ] 0.6 `initialize()` autoconfig 行为——**已答（1.1.6 字节码）**：默认 `initialized=true` → eager init → 不可达阻塞启动。**决议 `initialized=false`**（§10），registry 自己 init。剩仅需确认 SSE/streamable-HTTP `initialize()` 同步抛
- [ ] **Gate**：若 0.1/0.2 与设计假设冲突，回 `design.md` 风险段调整方案后再继续

**验证**：手测 + 日志；结论写入 `prd.md` "Step 0 验证结论"。

## Step 1 — mcp/core 领域内核（接口 + 模型，零 starter 依赖）

- [ ] 1.1 领域模型（`record`/`value`）：`ServerId`、`Subject(long userId, Long teamId)`（**无 roles 字段**，§8）、`McpTool(name, description, inputSchema)`、`McpArgs(Map<String,Object>)+of(Map)`（**无 `fromJson`**——adapter `inputType(Map)`，框架已解析，§6.1 B1）、`McpToolResult(text, isError)`、`McpResource`、`McpPrompt`、`McpServerHealth(alive/degraded/down + detail)`
- [ ] 1.2 门面接口：`McpServer(id/health/tools/resources/prompts)`、`McpTools(visibleTo/call)`、`McpResources(read)`、`McpPrompts(get)`、`McpServerRegistry(list/find)`
- [ ] 1.3 `visibleTo` 双过滤语义（authz + intent）、`call/read/get` 硬 authz（接口契约，实现在 runtime）

> **`core` 禁止** import `org.springframework.ai.tool..`/`org.springframework.ai.mcp..`/`io.modelcontextprotocol..`/`agent..`/`chat..`/`runtime..`/`adapter..`/`config..`（Step 6 ArchUnit 强制）。

**验证**：`./mvnw -q -Dtest='com.smart.rag.mcp.core.*' test`；compile 即证零 starter 依赖（Step 6 ArchUnit 兜底）。
**回滚点**：core 纯新增，可整包删除。

## Step 2 — mcp/runtime 内核实现（委托 McpSyncClient + provider；A1 拼合）

- [ ] 2.1 `McpServerImpl`（持一个 `McpSyncClient` + `ServerId` + 共享 `SyncMcpToolCallbackProvider` + `prefixGen`）：
  - `tools()`：`provider.getToolCallbacks()` → 按 `id() + "_"` 前缀过滤 → `cb.getToolDefinition()` 组装 `McpTool`（**inputSchema 取自 `def.inputSchema()`**，不 re-serialize `JsonSchema`）
  - `tools().call(prefixedName, args, subj)`：`McpAuthorizer` → **校验 `prefixedName` 以 `id() + "_"` 开头**（防跨 server 误调，不符拒）→ 剥前缀 → `new CallToolRequest(rawName, args.asMap())` → `client.callTool(...)` → `McpToolResult(text, CallToolResult.isError())`
  - `resources().read(uri, subj)`：authz + URI 白名单 → `new ReadResourceRequest(uri.toString())`
  - `prompts().get(name, args, subj)`：authz → `new GetPromptRequest(...)`
- [ ] 2.2 `McpServerRegistry`：注入 **`ObjectProvider<List<McpSyncClient>>`** + **`ObjectProvider<SyncMcpToolCallbackProvider>`** + **`ObjectProvider<McpToolNamePrefixGenerator>`**（全可选，无 connections 空载）；**因 `initialized=false`，client 交付未握手** → 建期对每个 client `if (!isInitialized()) try initialize() catch → down`：成功取 `serverInfo.name()` 建 `McpServer`(alive)，失败→down 跳过（**不影响其他 client**）；**多个 client 同 `serverInfo.name()` → 显式抛配置错误**（不静默合并）
- [ ] 2.3 health 被动翻转：`tools()/call()` 成功→alive、超时→degraded、不可达→down

**验证**：单测注入 mock `McpSyncClient` + mock provider，覆盖 per-server 过滤/前缀剥离/`isError` 透传/不可达降级。
**回滚点**：runtime 纯新增，删包即可。

## Step 3 — mcp/policy（authz + 规则，纯领域，零 starter 依赖）

- [ ] 3.1 `McpToolPolicy`（`@ConfigurationProperties("mcp.policy")`：`Map<String,ToolRule>` + `default: deny`；`ToolRule(intent/risk/roles/quota)`）+ 查询方法：`explicitlyAllowed(prefixed)`、`routing(prefixed)`、`roles(prefixed)`
- [ ] 3.2 `McpAuthorizer`（`requireAuthorized(subj, prefixedName)`；作用于 `visibleTo`/`call`/`read`/`get`；未授权抛 `UnauthorizedException`）。**Phase 1 只落 `routing` + subject 存在性**（`roles` 无 source，不强制——`Subject` 无 roles 字段、无 role provider，§8）；`roles`/`risk`/`quota` 判定留接口位、返回"未实现/放行"，接 Agent 链前补 role source。

> **字段分属（C2）**：`policy` 只暴露规则查询；`allowlist` 的静态判定由 `AllowlistMcpToolFilter`（Step 5，config）做，不在 policy。policy 不 import starter 类型。

**验证**：单测默认拒绝、显式允许、routing 拦截、subject 缺失拒绝（roles 拦截标 `@Disabled`/待 role source）。

## Step 4 — mcp/adapter（core → ToolCallback；inputType=Map，B1）

- [ ] 4.1 `McpToolCallbackAdapter.toCallbacks(McpTools, intent, subj)`：`FunctionToolCallback.<Map<String,Object>,String>builder(name, (args,ctx)->render(call))` + **`.inputSchema(t.inputSchema())`** + **`.inputType(new ParameterizedTypeReference<Map<String,Object>>(){})`**（**不是 `String.class`**——源码 `:103` `JsonParser.fromJson`→`readValue`，`String` 遇 JSON object 抛 `MismatchedInputException`）+ `.description(...)`；BiFunction 里 `args` 已是 Map → `McpArgs.of(args)`（**无 `fromJson`**）；`render` 按 `isError` 前缀 `[TOOL_ERROR]`（C5）
- [ ] 4.2 测试：`ToolCallback.getToolDefinition().inputSchema()` == MCP 原始 schema（非 `{"type":"string"}` 退化）；**用 JSON object args 执行 ToolCallback 不抛异常、回流 `McpTools.call`**（验证 inputType=Map 生效）；authz 不可绕过

> 本轮 adapter 实现 + 自测，**不注入 `AgentToolCallbackFactory`**（延后切片）。

**验证**：`./mvnw -q -Dtest='McpToolCallbackAdapter*' test`。

## Step 5 — mcp/config（装配边界）+ mcp/health（fail-soft）

- [ ] 5.1 `AllowlistMcpToolFilter implements McpToolFilter`（**放 config，非 policy**，B3）：`test(conn,tool)` 经注入的 `prefixGen.prefixedToolName(conn,tool)` 反算前缀键 → `policy.explicitlyAllowed(prefixed)`（C1）；读 `McpToolPolicy`（policy bean）
- [ ] 5.2 `McpToolNamePrefixGenerator` bean（`<serverInfo.name()>_<tool.name()>`，`_` 对齐 Spring AI `McpToolUtils.format` 合法集 `[a-zA-Z0-9_-]`；**组件走 `McpToolUtils.format` 清洗**防非法字符，E6）
- [ ] 5.3 `McpSyncClientCustomizer`（1.1.6 非generic 接口，`customize(String, McpClient.SyncSpec)`；注意 requestTimeout 已由 `request-timeout` 属性设、customizer 补 sampling/elicitation/logging/capabilities）+ **配置 `spring.ai.mcp.client.initialized=false`**（默认 true 会 eager init 阻塞启动，§11）
- [ ] 5.4 `mcp/health`：门面 try/catch fail-soft wrapper（方案 A 默认）+ 健康指标；不可用 server 包成空集/`down`

**验证**：Step 0 ② 的降级在此 wrapper 验证；AllowlistMcpToolFilter 前缀键反算单测。

## Step 6 — ArchUnit 依赖纪律（D4）

- [ ] 6.1 `core` 不依赖 `tool..`/`org.springframework.ai.mcp..`/`io.modelcontextprotocol..`/`agent..`/`chat..`/`runtime..`/`adapter..`/`config..`
- [ ] 6.2 `adapter` 是唯一 import `org.springframework.ai.tool..` 的包
- [ ] 6.3 仅 `runtime`+`config` 可 import `org.springframework.ai.mcp..`/`io.modelcontextprotocol..`（`policy`/`health` 不得漏）
- [ ] 6.4 消费者（`agent`/业务 service）只依赖 `mcp.core`（本轮无消费者，规则就位待后续）

**验证**：`./mvnw -q -Dtest='Arch*Test' test`。

## Step 7 — 全量验证

- [ ] 7.1 `./mvnw test` 全绿（含新增 MCP 测试 + ArchUnit）
- [ ] 7.2 对照 PRD AC1–AC8（本轮版）逐项确认；AC1 端到端标延后
- [ ] 7.3 更新 `docs/MCP-CLIENT-INTEGRATION.md`（如有 Step 0 结论导致的偏差）
- [ ] 7.4 `detect_changes({scope:"compare", base_ref:"main"})` 确认改动**仅限 `com.smart.rag.mcp.*` + 测试**（无对外注入点改动）

## 实现顺序

Step 0（Gate ①②）→ 1（core）→ 3（policy，纯领域）→ 2（runtime）→ 4（adapter）→ 6（早落 ArchUnit 防 core/runtime 被污染）→ 5（config+health）→ 7。

## 延后切片（不在本轮）

- `AgentToolCallbackFactory.createToolCallbacks` 追加 MCP 子集（出口①，`new Subject(ws.getUserId(), ws.getTeamId())` 透传；按 `routing(tool→intent)` 过滤进对应意图分支）
- `GuardrailEnforcingToolCallAdvisor` 接 MCP 策略（risk/quota，Phase 2）
- 真实/容器化 server 端到端（AC1）

> 任何一步若发现 MCP 文档假设与 1.1.6 实际行为不符，先回 `design.md` 风险段记录，再调整；勿在代码里硬绕。
