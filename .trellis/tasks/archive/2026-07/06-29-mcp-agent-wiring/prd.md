# MCP client 出口① 接线 — Agent 工具链接入

> 父任务：[`06-28-mcp-client-phase1`](../06-28-mcp-client-phase1/)（Phase 1 模块孤立实现已完成）。
> 完整 MCP 设计见 [`docs/MCP-CLIENT-INTEGRATION.md`](../../../../docs/MCP-CLIENT-INTEGRATION.md)，权威现实校准见
> [父任务 design.md D-1~D-10](../06-28-mcp-client-phase1/design.md)。本任务仅记录接线切片的边界、AC、约束。

## Goal

把 Phase 1 孤立的 `mcp/*` 模块接入 Agent 工具链（**出口①**）：`AgentToolCallbackFactory.createToolCallbacks`
在原有本地工具集基础上，per-request 追加"对调用方可见、且匹配本次意图"的 MCP 远端工具。让 MCP 模块从"悬空"
变为对 Agent 真正生效。

- **AgentIntent→McpIntent 映射 + Subject 构造** 落在消费侧（工厂，design D-5：core 禁 import `agent..`，故 adapter 取 `McpIntent`，映射由工厂做）。
- **多 server 聚合** 落在 `McpToolCallbackAdapter`（遍历 `McpServerRegistry.list()`，工厂不感知 server 数量）。

## Non-Goals（明确不做，另起切片）

- **不接 guardrail 语义层**：`GuardrailEnforcingToolCallAdvisor` 接 MCP 策略（`risk`/`quota`/敏感参数/二次确认）= Phase 2「安全闭环」切片，本任务不碰。本任务 MCP authz 仅靠 Phase 1 已落的两层（静态 allowlist + 内核硬 authz）。
- **不动** resources/prompts 业务接入（Phase 3）、per-user `McpServer`、stdio server、`McpToolsChangedEvent` 刷新闭环验证、per-call retry（D-1 defer）。
- **不改 MCP 内核**（core/runtime/policy/config/health 生产代码）——接线只消费 Phase 1 已暴露的 core 接口 + 扩展 adapter。

## Requirements

### R1 — 工厂 per-request 追加 MCP 工具
`AgentToolCallbackFactory.createToolCallbacks(AgentIntent, ToolWorkspace)` 返回值 = 本地工具集 `++` MCP 工具集。
工厂签名**不变**（`AgentModeStrategy:158` 调用点零改）。MCP 工具集 = `McpToolCallbackAdapter.toCallbacksForAllServers(McpIntent, Subject)`。

### R2 — AgentIntent→McpIntent 映射（工厂侧）
两个枚举值集 1:1（DIRECT_ANSWER/RETRIEVAL/DEEP_RETRIEVAL/GENERAL_TOOL），映射是**类型桥接**（core 禁 import agent.intent），
非语义变换。工厂内 package-private static 方法 `toMcpIntent(AgentIntent)`，可单测。

### R3 — Subject 构造（工厂侧）
`new Subject(workspace.getUserId(), workspace.getTeamId())`（design：`ToolWorkspace` 无 `subject()` 方法）。
`userId <= 0`（anonymous）→ `subj.isAuthenticated()=false` → MCP `visibleTo` 返回空集（authz 自然生效）。

### R4 — 多 server 聚合（adapter 侧）
`McpToolCallbackAdapter` 新增 `toCallbacksForAllServers(McpIntent, Subject)`：遍历 `McpServerRegistry.list()`，
逐 server 委托既有 `toCallbacks(server.tools(), intent, subj)`，拼接返回。registry 空载（无 connections / `enabled=false`）→ 空数组。

### R5 — 默认零行为变更（向后兼容）
当前 yaml 无 MCP connections + policy `tools: {}`（空 allowlist）→ registry 空 或 `visibleTo` 空集 →
**工厂输出与接线前逐字节一致**。MCP 工具完全 opt-in（部署配 connection + policy 才生效）。

### R6 — 依赖纪律（ArchUnit 演进）
`AgentToolCallbackFactory` 在 `agent..`，需依赖 `mcp.adapter.McpToolCallbackAdapter`（出口① 接缝）。
Phase 1 ArchUnit 6.4 `consumers_only_dependOn_core` 当前禁 `agent..`→`mcp.adapter..`，**需放宽**允许 `mcp.adapter`
（保留对 `mcp.runtime/config/health/policy` 的禁令）。理由见 design §「ArchUnit 决议」。

## Acceptance Criteria

- [ ] **AC1**（接线生效）：registry 有对 `(intent, subj)` 可见的 MCP 工具时，`createToolCallbacks` 返回数组 = 本地工具 + MCP 工具，数量/名称正确。
- [ ] **AC2**（intent 映射）：`toMcpIntent` 4 个 AgentIntent 各映射到对应 McpIntent；工厂调用 adapter 时传入正确的 McpIntent（Mockito 捕获验证）。
- [ ] **AC3**（Subject）：工厂用 `workspace.getUserId()/getTeamId()` 构造 Subject 并传入 adapter（捕获验证）。
- [ ] **AC4**（多 server 聚合）：`toCallbacksForAllServers` 对 2+ server 拼接所有可见工具；某 server `visibleTo` 空集时跳过、不影响其他 server。
- [ ] **AC5**（fail-soft 不击穿）：registry 空 / server down / 熔断 OPEN / subject 未认证 → MCP 贡献 0 工具、**不抛异常**，本地工具集不受影响。
- [ ] **AC6**（默认零变更）：空 registry（当前默认配置）下 `createToolCallbacks` 输出 == 接线前（本地工具集原样）。
- [ ] **AC7**（ArchUnit）：放宽后的 6.4 全绿；`mcp.adapter` 公共 API 不泄露 starter 类型（`McpSyncClient`/`McpSchema`/`SyncMcpToolCallbackProvider` 仍只活 runtime+config，6.3 不破）。
- [ ] **AC8**（authz 不可绕过）：adapter 产的 `ToolCallback` 执行时委托回 `McpTools.call()`（内核硬 authz 兜底）；`visibleTo` 已剔除未授权工具（LLM 看不到 name/description）。
- [ ] **AC9**（isError 不抹平）：MCP 工具返回 `isError=true` → adapter `render()` 前缀 `[TOOL_ERROR]` 回流 LLM（Phase 1 已实现，接线后链路完整）。
- [ ] **AC10**（Tier 2 真协议，gated/manual）：GitNexus `mcp --http` 作为真 MCP server，`@SpringBootTest` + 真连接，断言 `gitnexus_*` 前缀工具出现且 `callTool` 可执行。需本地起 GitNexus，用 `@EnabledIfEnvironmentVariable` 门控，不阻断 CI。
- [ ] **AC11**（改动范围）：生产代码仅改 `AgentToolCallbackFactory` + `McpToolCallbackAdapter` + `McpDependencyRulesTest`（放宽）；**不动** `AgentModeStrategy`/`ToolAutoConfiguration`/MCP core/runtime/policy/config/health。
- [ ] **AC12**（绿 + 提交）：`./mvnw -q -Dtest='Mcp*Test,Arch*Test,AgentToolCallbackFactory*Test' test` 全绿；新增/改动有单测；commit + push。

## Constraints

- Spring Boot 3.5.14 / Spring AI 1.1.6 / Java 21；遵循项目 best practice。
- **不得伪造 API**：所有 Spring AI / MCP SDK 调用以 1.1.6 源码 + 父任务 design.md `javap` 实锤为唯一事实来源。
- **B1 不可破坏**：adapter 既有 `inputSchema(MCP schema)` + `inputType(Map<String,Object>)`（`FunctionToolCallback.call():103` `readValue`）；本任务不动这部分，新增的聚合方法只复用既有 `toCallbacks`。
- 改任何 symbol 前先跑 GitNexus `impact`；提交前跑 `detect_changes`。
- 接线后 commit + push。
