# Design：MCP Client 接入 Phase 1

> 完整技术设计见 [`docs/MCP-CLIENT-INTEGRATION.md`](../../../../docs/MCP-CLIENT-INTEGRATION.md)。本文档仅记录任务边界、契约、数据流、兼容性与回滚形状，不重复详细设计。

## 任务边界（本轮：模块孤立 / Phase 1 做 / 不做）

**本轮做（仅 `mcp/*`，不接对外）**：`mcp/core`（接口 + 领域模型）、`mcp/runtime`（实现，持 `McpSyncClient`+provider）、`mcp/adapter`（`McpToolCallbackAdapter`，带 inputSchema）、`mcp/policy`（`McpAuthorizer` + `McpToolPolicy`，纯领域）、`mcp/config`（`AllowlistMcpToolFilter` + `McpToolNamePrefixGenerator` + `McpSyncClientCustomizer` + `@ConfigurationProperties`）、`mcp/health`（门面 fail-soft + 被动 health）；ArchUnit 依赖纪律；Step 0 ①②⑤ 验证。

**本轮不做（延后切片）**：`AgentToolCallbackFactory` 追加（出口①）、`GuardrailEnforcingToolCallAdvisor` 接 MCP 策略（Phase 2）、真实 server 端到端（AC1）。

**Phase 2/3 不做**：resources/prompts 业务接入（Phase 3）、per-user `McpServer`、stdio server。

> adapter 实现并自测，但**不注入工厂**——模块编译 + bean 空载（无 connections）+ 单测/ArchUnit 全绿即本轮完成。

## 关键契约（1.1.6 `javap` 实锤，见 MCP 文档 §5/§9/§13）

- `SyncMcpToolCallbackProvider`（`org.springframework.ai.mcp`）：`ToolCallbackProvider` + `ApplicationListener<McpToolsChangedEvent>`；`getToolCallbacks()`/`invalidateCache()`；`Builder.toolFilter()/toolNamePrefixGenerator()`。
- `McpToolCallbackAutoConfiguration.mcpToolCallbacks(ObjectProvider<McpToolFilter>, ObjectProvider<List<McpSyncClient>>, ObjectProvider<McpToolNamePrefixGenerator>, ...)` —— **写 bean 即自动注入**。
- `McpToolFilter extends BiPredicate<McpConnectionInfo, McpSchema.Tool>`。
- `McpToolNamePrefixGenerator.prefixedToolName(McpConnectionInfo, McpSchema.Tool)` + `noPrefix()`；server 名 = `connInfo.initializeResult().serverInfo().name()`。
- `McpSyncClient`（`io.modelcontextprotocol.client`，mcp-core jar）：`callTool(CallToolRequest)`/`listTools()`/`readResource(ReadResourceRequest)`/`getPrompt(GetPromptRequest)`/`getCurrentInitializationResult()`/`isInitialized()`。
- `McpSchema` 包 = `io.modelcontextprotocol.spec`（**非** `.sdk`）：`Tool{name,description,inputSchema→JsonSchema}`、`CallToolResult{content,isError}`、`ReadResourceRequest(String uri)`、`CallToolRequest(String name, Map args)`。
- `FunctionToolCallback.Builder`：`.description(String)` / **`.inputSchema(String)`** / `.inputType(Type|ParameterizedTypeReference)` / `.build()`（B1：**必须 `.inputSchema(MCP schema)` + `inputType(Map<String,Object>)`**；`inputType(String.class)` 执行时 `readValue` 抛 `MismatchedInputException`——源码 `:103` 证伪）。
- `McpSyncClientCustomizer`（`org.springframework.ai.mcp.customizer`）：`customize(String name, McpClient.SyncSpec)`。

## 数据流（两条出口；A1 拼合）

```text
出口①（LLM，本轮不接）：McpServer.tools() →[委托聚合 provider，按本 server 前缀过滤]→ visibleTo(authz+intent) → adapter(.inputSchema) → ToolCallback[]
           回流：ToolCallback.call → McpTools.call →[authz, 剥前缀]→ 本 server McpSyncClient.callTool
出口②（业务，Phase 3）：service → McpServer.resources()/prompts() →[authz+URI 白名单]→ 本 server McpSyncClient
```

**A1 决议**：调用面（call/resources/prompts）绑本 server 的 `McpSyncClient`；发现面（tools）委托**聚合 provider** 按前缀过滤（缓存/过滤/前缀/刷新 provider 级共享，非 per-server）。`McpServerRegistry` per `McpSyncClient` 建 `McpServerImpl`。authz 落点：`visibleTo`（双过滤）+ `call/read/get`（硬兜底）。

## 与现有代码的兼容性（本轮零接触）

- **本轮不动** `AgentToolCallbackFactory` / `AgentModeStrategy` / `ToolAutoConfiguration` —— 改动仅限 `com.smart.rag.mcp.*` + 测试。
- `ToolAutoConfiguration:39` 全局链用 `StaticToolCallbackResolver(ToolRegistry only)`，**不收集 `ToolCallbackProvider` bean** → MCP 不漏全局（D1，Step 0 ⑤ 写断言兜底，防升级/配置漂移）。
- enabled 默认 true（用户偏好）；无 connections 启动已验正常（Step 0 ③）。

## 依赖卫生（已记于 PRD R6）

MCP SDK 早已经 `spring-ai-advisors-vector-store → client-chat` 传递引入 `mcp-core:0.18.2`；新增 `mcp:0.18.2`。三模块同 0.18.2，dependency:tree 已解析无冲突。ArchUnit 1.3.0 test scope 已就绪（pom:359-365）。

## 回滚形状

- 全部新增代码集中在 `com.smart.rag.mcp.*` + 测试；**无对外注入点改动、无 pom 改动**。
- 回滚 = 删 `mcp` 包 + 测试。`spring.ai.mcp.client.enabled=false` 可即时关闭（默认保持 true）。
- 不触碰现有 RAG/agent/chat 业务逻辑，无数据库迁移，无对外 API 变更。

## 风险

- **R-1**：Step 0 ① 若 starter 在 server 不可达时 bean 创建期抛穿阻塞启动 → fail-soft 退**方案 B**（自定义 `McpSyncClient` bean，初始化包 try/catch）；否则走**方案 A**（门面 try/catch，默认）。② 同理定降级语义。
- **R-2（已解）**：Step 0 ③ 已坐实"无 connections + enabled=true"正常启动，无需占位 server。
- **R-3（已规避）**：主动拉取策略（`listTools` lazy + provider 缓存），不依赖可选 `list_changed`，不验刷新闭环。
- **R-4（B1，已决议+证伪修正）**：adapter 必须 `.inputSchema(MCP schema)` **且 `inputType(Map<String,Object>)`**。源码核实 `FunctionToolCallback.call()` `:103` `JsonParser.fromJson(toolInput, inputType)` → `readValue`；`inputType(String.class)` 遇 JSON object 抛 `MismatchedInputException`（早先"`String` 原样传入"被证伪）。Map 让框架把 JSON object 反序列化成 Map 喂 BiFunction，`McpArgs.of(Map)`（无 `fromJson`）。已固化 §关键契约/implement Step 4。
- **R-5（B3，已决议）**：`AllowlistMcpToolFilter` 放 `config`（非 policy），保 `policy` 零 starter 依赖；ArchUnit 6.3 兜底。
- **R-6（A1，已决议）**：per-server 与聚合 provider 拼合方式见 §数据流；前缀过滤的可靠性依赖 `prefixGen` 与 `McpServer.id()` 同源。
- **R-7（roles 无 source）**：`Subject = (userId, teamId)` 无 roles，无 role provider → yaml `roles`/`risk`/`quota` 本期**无判定对象**。Phase 1 authz 收窄成 allowlist + routing + subject 存在性；roles 判定留接口位、接 Agent 链前必须补 role source，否则高风险工具仅 allowlist 兜底。
- **R-8（握手/initialize，已决议）**：1.1.6 字节码核实 autoconfig `if (isInitialized()) client.initialize()`，`initialized` 默认 **true** → eager init、不可达 server 阻塞启动。**决议 `spring.ai.mcp.client.initialized=false`**，registry 自己 per-client `initialize()` + try/catch（不可达→down 跳过），实现 server 间隔离。Step 0 ⑥ 已答。
- **R-9（前缀分隔符，Spring AI 框架层）**：`McpToolUtils.format` 合法集 `[a-zA-Z0-9_-]` 排除 `.`，默认 generator 据此清洗。本项目自定义 generator 用 `_` 对齐框架约定；`.` 偏离约定（自定义输出不被框架二次清洗）。**实现要点**：自定义 generator 应对 `serverInfo.name()` 组件走 `McpToolUtils.format` 清洗，防非法字符原样流入工具名。与 provider 无关（模型按 BYOK 动态配置）。
- **R-10（同名 server）**：`serverInfo.name()` server 自报可撞名；registry 必须检测同名多 client 并显式抛配置错误，不静默合并。
- **R-11（跨 server 误调）**：`tools().call(prefixedName)` 必须先校验入参前缀 == 自己 `id()`，否则剥错前缀静默 misroute 到别家 client。

## 现实校准（2026-06-29 实现前核实 — 修正设计文档与代码现实的偏差）

> 核实既有基础设施后发现 MCP 文档 §11 若干"实锤"与代码现实不符；以下为落地决议，优先级高于 MCP 文档原文。

- **D-1（RetryPolicy 不可干净复用 → Phase 1 只做熔断器，defer retry）**：`RetryPolicy`（`infrastructure/llm/resilience`）+ `RetryConfig`（`infrastructure/llm/config`）是 **LLM 专属包**，且 `executeWithBackoff` 重试耗尽**硬编码** `RemoteErrorCode.LLM_TRANSIENT_ERROR`（`RetryPolicy:71-73`）、`isRetryable` **硬编码** `LLM_RATE_LIMITED`/`LLM_TRANSIENT_ERROR`（`:121-122`）。commit `0acb3eb` 只通用化了熔断器三件（registry/state machine/CircuitOpenException/CircuitBreakerProperties 已迁 `infrastructure/fallback`），**未迁移/通用化 retry**。→ 复用会误标 MCP 错误码。**决议**：Phase 1 **不做 per-call retry**，只接三态熔断器（已通用化、干净复用）；熔断器 OPEN `openDurationMs` cool-down 已提供 server 级退避（§11.2 明文"无需 spring-retry"），故 MCP 不留空白保护。retry 通用化（让耗尽错误码/isRetryable 可注入）需改 `RetryPolicy`+`LlmClientFactory`+测试，触碰 LLM 代码违反 AC12"改动仅限 mcp.*"，列为后续切片。测试项 §12-13③ 的"重试耗尽"语义本期以"连续失败计熔断"近似。
- **D-2（UnauthorizedException 不存在）**：`ClientException(ClientErrorCode.FORBIDDEN, detail)` 即 authz 拒绝（§11.1 表已写"复用 ClientErrorCode 权限类"）。不新建异常类。AC3/§8 原文 `UnauthorizedException` 等价于 `ClientException(FORBIDDEN)`。
- **D-3（McpCircuitOpenException 不必新建）**：`CircuitOpenException(IErrorCode, candidateId)`（`infrastructure/fallback`）已通用化、错误码由装配方注入。MCP 传 `RemoteErrorCode.MCP_CIRCUIT_BREAKER_OPEN` + `candidateId=ServerId`。覆盖 MCP 文档 §11.1"新增 McpCircuitOpenException"。
- **D-4（熔断器 adapter：mcp 内薄 wrapper，不依赖 llm 包）**：LLM 侧 `infrastructure/llm/resilience/CircuitBreaker` adapter 虽已把 delegate 放宽到 `AbstractCircuitBreakerRegistry`、OPEN 异常换通用 `CircuitOpenException`，但仍住在 `llm.resilience` 包且构造耦合 `LlmMetrics`。为保持 MCP **零 `infrastructure.llm..` 依赖**，Phase 1 在 `mcp/runtime` 内用薄 guard 直接调 `AbstractCircuitBreakerRegistry`（`isCallAllowed/recordSuccess/recordFailure/releaseProbe/stateOf`）+ 复用通用 `FallbackEligibility` 做 A/B/C 计数过滤——底层 registry/state machine/FallbackEligibility 仍是共用的通用件，非"自研熔断器"。
- **D-5（core 不能 import AgentIntent → 自建 McpIntent）**：`McpTools.visibleTo(Subject, AgentIntent)` 与 ArchUnit 6.1"`core` 禁止 import `agent..`"冲突。`AgentIntent` 在 `com.smart.rag.agent.intent`。→ `mcp/core` 定义自己的 `McpIntent` 枚举（routing 用：DIRECT_ANSWER/RETRIEVAL/DEEP_RETRIEVAL/GENERAL_TOOL），`visibleTo(Subject, McpIntent)`、`McpToolPolicy.routing(→McpIntent)`。消费侧（future `AgentToolCallbackFactory`）做 `AgentIntent→McpIntent` 映射。yaml `intent` 值用 McpIntent 名。
- **D-6（启动不可达：initError 标记，不改通用 registry）**：§11.4 想要不可达 client 的熔断器"直接置 OPEN"，但 `AbstractCircuitBreakerRegistry` 无 `forceOpen`。为不改通用 infra（AC12），`McpServerImpl` 持 `@Nullable volatile String initError`：`health()` = `initError!=null ? down(initError) : 投影(熔断态)`；调用成功清 initError（恢复）。CLOSED 态仍允许探测调用（recovery），失败累计经 `recordFailure` 自然翻 OPEN。`forceOpen` 通用化列为可选后续。
- **D-7（health 三态映射）**：`McpServerHealth` 投影规则 `CLOSED→alive / HALF_OPEN→degraded / OPEN→down`（+ initError 覆盖为 down），是熔断器只读投影，非独立状态机——与 §11.2 一致。
- **D-8（ArchUnit 6.2 放宽：runtime 也可 import `org.springframework.ai.tool..`）**：MCP 文档 §4.3/implement 6.2 原文"adapter 是唯一 tool.. 导入方"与 §9.1 发现流程冲突——`McpServerImpl.visibleTo` 必须读聚合 `SyncMcpToolCallbackProvider` 产出的 `ToolCallback.getToolDefinition()`（取 name/description/inputSchema 组装 `McpTool`），故 `mcp/runtime` 也需 import `tool..`。决议：6.2 放宽为"**仅 `mcp.adapter` + `mcp.runtime` 可 import `org.springframework.ai.tool..`；`core`/`policy`/`config`/`health` 不可**"（`McpDependencyRulesTest.tool_imports_confined_to_adapter_and_runtime` 强制）。`core`/`policy` 仍零 `tool..` 依赖，纯领域不变。
- **D-9（McpSyncClientCustomizer 本期不提供）**：starter 默认 customizer 足够（`request-timeout` 经属性、无出站认证/sampling）；no-op customizer bean 是死代码，故不建。Phase 2 bearer auth / Phase 3 per-user 凭据时再补。
- **D-10（CallToolResult 构造器全 deprecated，测试用 `(List<TextContent>, Boolean, Map.of())`）**：0.18.2 `McpSchema.CallToolResult` 的便捷构造器均标 deprecated（推荐 builder）；测试用非 deprecated 的 `(List<Content>, Boolean, Map)` 三参形式，仅测试代码，不影响生产。
