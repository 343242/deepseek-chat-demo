# PRD：MCP Client 接入 Phase 1

## 背景

`smart-rag` 要作为 MCP client 接入外部 MCP server，让 Agent 能调用远端工具、业务能读远端 resources/prompts。详细技术设计见 `docs/MCP-CLIENT-INTEGRATION.md`（已对齐 Spring AI 1.1.6 javap 实锤）。本任务为 Phase 1：领域内核 + 追加接入 + 强制 authz。

## 需求

> **本轮范围（2026-06-28）：模块孤立实现**。只建 `mcp/{core,runtime,policy,adapter,config,health}` + 测试，**不接对外注入点**（不动 `AgentToolCallbackFactory`/`AgentModeStrategy`/`ToolAutoConfiguration`）。adapter 实现并自测但不注入工厂。模块编译 + bean 无 connections 空载 + 单测/ArchUnit 全绿即完成。对外接入作为延后切片。

- **R1 领域内核**：建 `mcp/core`（接口 + 领域模型）+ `mcp/runtime`（实现），以 `McpServer` 门面统一 tools/resources/prompts 三能力 + health + `McpServerRegistry`，带项目侧领域模型（`McpTool`含 `inputSchema`/`McpResource/McpPrompt/Subject/ServerId/McpArgs/McpToolResult`含 `isError`/`McpServerHealth`）。starter 类型不跨出 `runtime`+`config`；`core` 零 starter 依赖。
- **R2 复用 starter，不自研**：tools 发现/前缀/过滤/缓存刷新复用 `SyncMcpToolCallbackProvider`；内核只做 authz + 三能力统一门面 + 类型封装。**A1 拼合**：调用面(call/resources/prompts)绑本 server 的 `McpSyncClient`；发现面(tools)委托聚合 provider 按前缀过滤。
- **R3 adapter（本轮不接链）**：`mcp/adapter` 把 `McpTools` 转 `ToolCallback[]`（**必须 `.inputSchema(MCP schema)` + `inputType(Map<String,Object>)`**——`inputType(String)` 执行时 `readValue` 抛异常，B1），实现 + 自测；**不在 `AgentToolCallbackFactory` 追加**（延后切片）。
- **R4 强制 authz**：`McpAuthorizer` 落在 `visibleTo`(authz+intent 双过滤)/`McpTools.call`/`McpResources.read`/`McpPrompts.get` 内核层（硬授权）。adapter 产的 `ToolCallback` 委托回 `McpTools.call`，无绕过路径。guardrail 语义层（risk/quota）= Phase 2，本轮不做。
- **R5 allowlist**：实现 `McpToolFilter`（`BiPredicate`）bean 放 **`config`**（非 policy，B3），starter autoconfig 经 `ObjectProvider` 自动注入 provider；`McpToolPolicy`（`policy`，纯规则数据）默认拒绝；键=前缀全名，filter 经 `prefixGen` 反算（C1）；字段分属各层（C2）。
- **R6 依赖纪律**：ArchUnit 断言 `core` 零 starter/agent/chat 依赖；`adapter` 唯一 `tool..` 导入方；仅 `runtime`+`config` 可 import `org.springframework.ai.mcp..`/`io.modelcontextprotocol..`。
- **R7 Step 0 运行时验证**：坐实 ①②（启动不可达产出 → 定 fail-soft 方案 A/B、握手失败/失联降级）+ ⑤（MCP 不漏全局链）+ ⑥（`initialize()` 是否被 autoconfig 调）；③已验；采用主动拉取策略，**不验刷新闭环**。`_` 前缀由 Spring AI 框架约定支撑、无需 spike；provider 工具名规则属 BYOK 部署关注点、不在范围。结果回写本文档。

## 约束

- Spring AI 基准 = **1.1.6**（pom 已引 `spring-ai-starter-mcp-client`，BOM 管版本）；1.1.8 页仅作能力参考。
- `enabled` 一律 `true`（用户偏好），仅在配置旁注明开/关语义区别。
- **`spring.ai.mcp.client.initialized` 一律 `false`**（默认 true）：true 会 eager `initialize()`，单个 server 不可达阻塞整个启动；false 让 registry 自己 per-client init + try/catch，实现 server 间隔离（§11，1.1.6 字节码核实）。
- MCP 凭据本期全局（非 per-user）；base-url 走出站 SSRF 白名单（复用 commit `2c5733a` 先例）。
- 不拆 Maven 模块；不覆盖 stdio server；不做 per-user 会话；不引 annotation 模块（用 provider 自带 `ApplicationListener`）。
- **生产接线（`AgentToolCallbackFactory`/`AgentModeStrategy` 调 MCP、guardrail 接 MCP 策略、AC1 真模型端到端）= 最后做**，本轮只在 `com.smart.rag.mcp.*` 内构建 + 测试。
- **测试可全量 `@SpringBootTest`**（加载 `SmartRagApplication`，无需 mini-app）；只要不改外部模块产出代码即可。
- **真协议验证目标**（无需本地另起 server）：★ 本地 `gitnexus mcp --http -p 3000`（Streamable-HTTP `POST /mcp`，real tools，免鉴权；GitNexus 默认 STDIO，须加 `--http`）/ 真实 remote MCP（bigmodel.cn streamable-HTTP，可能需 bearer）/ ① 死端口。详见 MCP 文档 §12。
- 遵循 `.trellis/spec/backend/*`（directory-structure / error-handling / quality-guidelines / logging / llm-spi）。

## 验收标准

> AC1（Agent 端到端）本轮**不验**（延后切片，接链后做）。本轮验 AC2–AC9。

- **AC1（延后）**：配真实/容器化 MCP server，Agent 链（`AgentModeStrategy`）能发现并调用其工具，结果回流 LLM。——本轮不接链，留延后切片。
- **AC2**：`McpServer`/`McpTools`/`McpResources`/`McpPrompts`/`McpServerRegistry` 接口 + `runtime` 实现存在；委托 `McpSyncClient` + authz 生效（单测覆盖）；任何路径不直注 `McpSyncClient`（ArchUnit）。
- **AC3**：未在 `McpToolPolicy` allowlist / intent 路由 / subject 存在性 任一不过的调用被内核 authz 拒绝（抛 `UnauthorizedException`），且无法绕过（`visibleTo` + `call` 双层）。**`roles` 本期无 source 不强制**（§8 R-7）；AC3 兑现范围 = allowlist + routing + subject-present。
- **AC4**：未在 `McpToolPolicy` 显式允许的远端工具默认拒绝（静态 allowlist + `visibleTo`）。
- **AC5**：`McpServer.tools()` 经聚合 provider 按前缀过滤能取本 server 工具子集（mock provider 单测）；不依赖可选 `list_changed`。
- **AC6**：ArchUnit 依赖纪律（§4.3 / implement Step 6 的 ①②③④）全部通过。
- **AC7**：Step 0 ①②⑤⑥ 有记录结论（见下"Step 0 验证结论"）；③已验。
- **AC8**：`./mvnw test` 全绿；新增测试覆盖 MCP 文档 §12 测试策略本轮项（1–4、7–11）。
- **AC9**：`fail-soft`（门面 try/catch）下 server 不可达时，该 server 降级为 `health=down` + 空工具集，不击穿（单测 + Step 0 ②）。
- **AC10（B1）**：adapter 产的 `ToolCallback.getToolDefinition().inputSchema()` == MCP 原始 schema（非 `{"type":"string"}` 退化）。
- **AC11（D1）**：全局 `ToolCallingManager` 不含 MCP 前缀工具（断言兜底）。
- **AC12**：`detect_changes({scope:"compare", base_ref:"main"})` 确认改动**仅限 `com.smart.rag.mcp.*` + 测试**，零对外注入点改动。

## Step 0 验证结论（待 R7 填充）

- [x] ① 启动期 server 不可达时 provider 产出：**fail-soft 方案 A（initialized=false + registry per-client init + try/catch + 三态熔断器）已实现并测试**。`McpServerRegistryImplTest.perClientInit_isolation` 坐实：client initialize 失败 → 建合成 id + `initError`（health=down），**不影响其他 client**；provider 不可达时 `visibleTo` 返回空集（不抛、不阻塞）。未做"真坏 URL 启动"运行时 spike——行为经 mock 测试覆盖，与设计预测一致。
- [x] ② 握手失败/运行期失联降级语义：**同 ①**——init 失败标 down；运行期 call 失败经 `FallbackEligibility` 计熔断 → OPEN 后快速失败；门面 try/catch 降级为 `McpToolResult.error`（call）/ `RemoteException`（read/get）。`McpServerImplTest`（call_circuitOpen_fastFails / call_serverFailure_softened / health_projection）覆盖。
- [x] ③ `enabled` 默认值 + 无 connections 启动行为：**已坐实（2026-06-28 手测）**——pom 引入 `spring-ai-starter-mcp-client` 后，无任何 MCP server 连接，应用正常启动。说明 starter 对"无可用 server"优雅（enabled 默认 true 不阻塞）；间接预示 Step 0 ①（单个 server 不可达）也可能是 fail-soft 返回空，待 ① 针对性确认。
- [x] ⑤ MCP 不漏全局链（D1，弱保护）：**按构造恒真**——`ToolAutoConfiguration` 全局链用 `StaticToolCallbackResolver(ToolRegistry only)`，不收集 `ToolCallbackProvider` bean；MCP 工具只经 `McpServerImpl`（per-request）产出，不进全局链。`McpDependencyRulesTest.consumers_only_dependOn_core` 守护"消费者不碰 mcp 实现包"。§12-10 弱保护断言测试标延后（按构造恒真、价值在文档化非护栏）。
- [x] ⑥ `initialize()` autoconfig 行为：**已答（1.1.6 字节码）**——默认 `initialized=true` → eager init → 不可达阻塞启动。**决议 `spring.ai.mcp.client.initialized=false`**，registry 自己 per-client init + try/catch 实现隔离（§11）。剩仅需确认 SSE/streamable-HTTP `initialize()` 同步抛。

> 工具集获取 = 主动拉取（`listTools`，lazy + 缓存），不依赖 `list_changed`，故不验刷新闭环。
