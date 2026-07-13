# Design：MCP client 出口① 接线（Agent 工具链）

> 完整 MCP 设计见 [`docs/MCP-CLIENT-INTEGRATION.md`](../../../../docs/MCP-CLIENT-INTEGRATION.md)（§6.1 出口①、§4.3 依赖纪律）；
> 现实校准见 [父任务 design.md D-1~D-10](../06-28-mcp-client-phase1/design.md)。本文档只记接线切片的边界、契约、决议、兼容性与回滚。

## 任务边界（做 / 不做）

**做（生产代码 3 处）**：
1. `mcp/adapter/McpToolCallbackAdapter`：新增构造器注入 `McpServerRegistry` + 聚合方法 `toCallbacksForAllServers(McpIntent, Subject)`。
2. `agent/tool/callback/AgentToolCallbackFactory`：构造器注入 `McpToolCallbackAdapter`；`createToolCallbacks` 追加 MCP 子集；新增 `toMcpIntent(AgentIntent)` 映射。
3. `test/.../mcp/McpDependencyRulesTest`：放宽规则 6.4 允许 `agent..`→`mcp.adapter..`。

**不做**：`AgentModeStrategy`（调用点零改，见 §数据流）、`ToolAutoConfiguration`、MCP core/runtime/policy/config/health 生产代码、guardrail、resources/prompts。

## 关键契约（已核实，引用父任务实锤）

- `McpToolCallbackAdapter.toCallbacks(McpTools, McpIntent, Subject) → ToolCallback[]`（Phase 1，B1 正确，**不动**）。
- `McpServerRegistry.list() → List<McpServer>`（core）；`McpServer.tools() → McpTools`；`McpTools.visibleTo(Subject, McpIntent) → List<McpTool>`。
- `McpServerImpl.visibleTo` **fail-soft**（`initError/null subj/!isAuthenticated/provider==null → List.of()`；`provider.getToolCallbacks()` 包 try/catch → 空集）—— 聚合方法**无需 try/catch**。
- `McpServerImpl.call` **fail-soft**：circuit OPEN / call 异常 → `McpToolResult.error`（adapter `render` 前缀 `[TOOL_ERROR]`）；仅 authz 拒绝抛 `ClientException`（AC3 propagate，design D-2）。
- `Subject(long userId, Long teamId)` record；`isAuthenticated()` = `userId > 0`。
- `AgentIntent`/`McpIntent` 值集 1:1（DIRECT_ANSWER/RETRIEVAL/DEEP_RETRIEVAL/GENERAL_TOOL）。
- `McpAuthorizer.canSee`：**精确 intent 匹配**（`toolIntent == intent`）—— MCP 工具按 yaml `intent` 精确归属，与本地工具"DEEP 含 RETRIEVAL"子集语义**不同**；接线只透传请求 intent，不改路由。
- 现状：yaml 无 connections（registry 空）+ policy `tools: {}`（空 allowlist）→ 接线后默认 0 MCP 工具。

## ArchUnit 决议（本任务核心设计点）

**张力**：`AgentToolCallbackFactory`（`agent..`）要产 `ToolCallback[]`；McpTool→ToolCallback 转换（含微妙的 B1 知识：`inputType(Map)` + `inputSchema`）唯一住在 `mcp/adapter/McpToolCallbackAdapter`。但 Phase 1 ArchUnit 6.4 `consumers_only_dependOn_core` 禁 `agent..`→`mcp.adapter..`。

**两条路**：

| 方案 | 做法 | 评价 |
|---|---|---|
| **E（采纳）** | 放宽 6.4 允许 `agent..`→`mcp.adapter`；工厂调 `adapter.toCallbacksForAllServers` | **DRY**：adapter 保持唯一 McpTool→ToolCallback 出口，B1 单点持有；与 design §6.1（工厂调 adapter）一致；与 D-8 同理（D-8 已为 runtime 放宽 tool.. 导入） |
| I（否决） | 工厂直注 `McpServerRegistry`(core)，自己拼 ToolCallback | 不动 ArchUnit，但**复制 B1 逻辑**（FunctionToolCallback.builder + inputSchema + inputType(Map) + render）→ 两处拥有"MCP ToolCallback 怎么建"→ drift 风险（adapter 改 B1、工厂漏改）；违背"adapter 唯一 tool.. 出口"设计 |

**采纳 E**。安全性论证：
- `McpToolCallbackAdapter` 公共 API = `mcp.core.*` 类型 + `ToolCallback`（Spring AI tool，agent 本就处处用）；**不泄露 starter 类型**（`McpSyncClient`/`McpSchema`/`SyncMcpToolCallbackProvider`）。
- 真正的不变量（starter 类型只活 runtime+config）由 **6.3 独立保证**，不受 6.4 放宽影响。
- 故 agent→adapter 不破坏"starter 类型不跨边界"。

**6.4 放宽形态**：从禁 `{runtime,adapter,config,health,policy}` 改为禁 `{runtime,config,health,policy}`（解禁 `adapter`）。不针对单个类收窄（ArchUnit 单类匹配脆弱），因 adapter 公共面干净、其他 agent 类依赖它无实际危害。强注释说明出口① 接缝语义。

## 组件改动

### 1. `McpToolCallbackAdapter`（mcp/adapter）
```java
@Component
public class McpToolCallbackAdapter {
    private final McpServerRegistry registry;                       // 新增

    public McpToolCallbackAdapter(McpServerRegistry registry) {     // 新增（Phase 1 无状态→现有）
        this.registry = registry;
    }

    /** 既有，B1 正确，不动。 */
    public ToolCallback[] toCallbacks(McpTools tools, McpIntent intent, Subject subj) { ... }

    /**
     * 新增：聚合所有 MCP server 对 (intent, subj) 可见的工具 → ToolCallback[]（出口① 多 server 拼合）。
     * visibleTo 已 fail-soft（down/熔断/未认证/发现失败→空集），无需 try/catch。
     * registry 空载（无 connections / enabled=false）→ 空数组。
     */
    public ToolCallback[] toCallbacksForAllServers(McpIntent intent, Subject subj) {
        List<McpServer> servers = registry.list();
        if (servers.isEmpty()) return new ToolCallback[0];
        List<ToolCallback> all = new ArrayList<>();
        for (McpServer s : servers) {
            ToolCallback[] part = toCallbacks(s.tools(), intent, subj);
            if (part.length > 0) Collections.addAll(all, part);
        }
        return all.toArray(ToolCallback[]::new);
    }
    // render(...) 既有不动
}
```

### 2. `AgentToolCallbackFactory`（agent/tool/callback）
```java
// 构造器新增参数（最后位）：McpToolCallbackAdapter mcpAdapter
public ToolCallback[] createToolCallbacks(AgentIntent intent, ToolWorkspace workspace) {
    ToolCallback[] local = switch (intent) { ...既有... };          // 不动
    ToolCallback[] mcp = mcpAdapter.toCallbacksForAllServers(
            toMcpIntent(intent),
            new Subject(workspace.getUserId(), workspace.getTeamId()));
    if (mcp.length == 0) {                                          // 默认路径（空 registry/空 allowlist）
        log.debug("Created {} tool callbacks for intent {}", local.length, intent);
        return local;
    }
    ToolCallback[] all = Arrays.copyOf(local, local.length + mcp.length);
    System.arraycopy(mcp, 0, all, local.length, mcp.length);
    log.debug("Created {} tool callbacks for intent {} (local={}, mcp={})",
            all.length, intent, local.length, mcp.length);
    return all;
}

/** AgentIntent→McpIntent 类型桥接（值集 1:1；core 禁 import agent.intent，故映射在消费侧）。 */
static McpIntent toMcpIntent(AgentIntent i) {
    return switch (i) {
        case DIRECT_ANSWER -> McpIntent.DIRECT_ANSWER;
        case RETRIEVAL -> McpIntent.RETRIEVAL;
        case DEEP_RETRIEVAL -> McpIntent.DEEP_RETRIEVAL;
        case GENERAL_TOOL -> McpIntent.GENERAL_TOOL;
    };
}
```

### 3. `McpDependencyRulesTest`（放宽 6.4）
```java
@ArchTest
static final ArchRule consumers_only_dependOn_core = noClasses()
        .that().resideInAnyPackage("..com.smart.rag.agent..", "..com.smart.rag.chat..")
        .should().dependOnClassesThat().resideInAnyPackage(
                "..mcp.runtime..", "..mcp.config..", "..mcp.health..", "..mcp.policy..")
        // mcp.adapter 解禁：出口① 接缝（AgentToolCallbackFactory 调 McpToolCallbackAdapter）；
        // adapter 公共面 = core 类型 + ToolCallback，不泄露 starter 类型（6.3 独立守住）—— 等同 D-8 为 runtime 放宽 tool..
        .because("消费者（agent/chat/业务）只依赖 mcp/core + mcp/adapter（出口① 类型转换接缝），禁止直注 runtime/config/health/policy 实现类（§4.3 6.4，Phase 2 接线放宽）");
```

## 数据流

```text
AgentModeStrategy.buildAdvisorChain（零改）
  └─ :158 agentToolCallbackFactory.createToolCallbacks(intentResult.intent(), workspace)
       ├─ 本地工具集（既有 switch，闭包捕获 workspace）
       └─ mcpAdapter.toCallbacksForAllServers(toMcpIntent(intent), new Subject(ws.userId, ws.teamId))
            └─ 遍历 McpServerRegistry.list()
                 └─ per server: adapter.toCallbacks(server.tools(), intent, subj)
                      └─ McpTools.visibleTo(subj, intent)  [fail-soft：authz+intent+allowlist+前缀过滤 → List<McpTool>]
                           └─ McpTool → FunctionToolCallback（B1：inputSchema + inputType(Map)）
                                └─ BiFunction → tools.call(name, McpArgs.of(args), subj)  [内核硬 authz + 熔断 + fail-soft]
       → local ++ mcp → ToolCallback[]
  → :167 StaticToolCallbackResolver → :271 options.toolCallbacks → LLM
```

**关键**：`AgentModeStrategy` 完全不感知 MCP——接线封装在 `createToolCallbacks` 内，调用签名不变。

## 兼容性

- **默认零行为变更**：空 registry（当前 yaml 无 connections）/ 空 allowlist（`tools: {}`）→ `toCallbacksForAllServers` 返回 `new ToolCallback[0]` → `mcp.length==0` → 返回 `local`（与接线前逐字节一致）。
- **enabled=false**：starter 不建 `McpSyncClient` → registry 空 → 同上。无需 `@ConditionalOnProperty`（Phase 1 已让 registry 始终是 bean、空载不抛）。
- `AgentModeStrategy`/`ToolAutoConfiguration`/全局 `ToolCallAdvisor` 零改；MCP 仍只进 Agent per-request 链，**不漏全局静态链**（D1 不变）。
- 工厂构造器加参：`@Component` 由 Spring DI 注入；无手动 `new AgentToolCallbackFactory`（已 grep 确认）。

## 测试策略

| 层 | 测试 | 覆盖 AC |
|---|---|---|
| adapter 单测（扩 `McpToolCallbackAdapterTest`） | mock `McpServerRegistry`（list 返回若干 mock `McpServer`，其 `tools().visibleTo` 返回可控 `McpTool`）；断言聚合拼接 / 空 registry / server visibleTo 空集跳过 / 未认证空集 | AC1/AC4/AC5 |
| 工厂单测（新建 `AgentToolCallbackFactoryMcpWiringTest`） | mock 9 个工具 + `ToolRegistry`（空 general callbacks）+ mock `McpToolCallbackAdapter`；断言 local++mcp 拼接、DIRECT_ANSWER 路径、adapter 用正确 `McpIntent`+`Subject` 调用（ArgumentCaptor）、mcp=0 时返回 local | AC1/AC2/AC3/AC6 |
| intent 映射单测 | `AgentToolCallbackFactory.toMcpIntent` 4 case | AC2 |
| ArchUnit | 放宽后 6.4 全绿 + 6.1/6.2/6.3 仍绿 | AC7/AC11 |
| Tier 2 真协议（gated） | `@SpringBootTest` + `streamable-http.connections.gitnexus.url=http://127.0.0.1:3000/mcp` + policy allowlist + `@EnabledIfEnvironmentVariable(GITNEXUS_MCP_E2E)`；断言 `gitnexus_*` 工具出现 + callTool | AC10 |

> 工厂 mock 9 工具较繁——可抽一个 `newFactory(adapter)` helper 用 `mock(Mockito.RETURNS_DEFAULTS)` 批量传工具；或用 `@Mock` 字段 + 参数化构造。优先简洁。

## 风险

- **R-1（ArchUnit 放宽是否过松）**：解禁 adapter 允许任意 agent 类依赖它。缓解：adapter 公共面仅 `toCallbacks`/`toCallbacksForAllServers`（参数皆 core 类型），无其他可滥用入口；6.3 独立守 starter 类型不变量。可接受。
- **R-2（B1 drift）**：采纳 E 后 B1 单点持有（adapter），工厂不复制 → 无 drift。若未来有人改工厂自拼，code review + ArchUnit（adapter 是 tool.. 出口）拦截。
- **R-3（authz 拒绝击穿 Agent 循环）**：`McpTools.call` authz 拒绝抛 `ClientException`，经 `ToolCallback.call` → Spring AI 工具框架 `toolExecutionExceptionProcessor` 处理。但 `visibleTo` 已剔除未授权工具，LLM 不会调用不可见工具 → authz 拒绝仅在配置漂移/竞态时触发，罕见。可接受（与 Phase 1 决议一致）。
- **R-4（Tier 2 需 GitNexus 运行）**：e2e 测试 gated，不阻断 CI；AC10 为 manual gate（本地起 `gitnexus mcp --http -p 3000` + 配 policy 后跑）。
- **R-5（多 server 性能）**：N server → N 次 `provider.getToolCallbacks()`（聚合 provider 内部 `Lock`+缓存，N 次廉价缓存读）+ N 次前缀过滤。小 N 可接受；大 N 优化留后续。

## 回滚形状

- 改动集中在 3 文件（adapter/factory/ArchTest）+ 新增测试；无 pom 改动、无 yaml 必改、无 DB 迁移、无对外 API 变更。
- 回滚 = revert 3 文件 + 删测试。运行期即时关闭：`MCP_ENABLED=false` 或不配 connections（registry 空 → 工厂返回 local）。
- 不触碰现有 RAG/agent/chat 业务逻辑。
