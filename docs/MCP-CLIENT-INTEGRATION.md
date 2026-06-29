# MCP Client 接入设计

> 目标：让 `smart-rag` 作为 **MCP client** 接入外部 MCP server，复用现有业务层，不拆多模块，不改变 Web 主应用的部署方式。
>
> 结论：采用 **混合方案 + 领域内核**。starter（官方 MCP Java SDK 的 Boot 装配）承担连接/发现/前缀/过滤/刷新；项目侧把 MCP 建成一个**高度内聚的领域**——以 `McpServer` 门面为内核，统一 tools/resources/prompts 三能力与认证授权，starter 类型**不跨出领域边界**；对外只通过两个出口：`adapter`（→ LLM 工具链）与 `core`（→ 路径 C 业务调用）。内核**只做 starter 不做的**（authz、三能力统一门面、类型封装），不重复 starter 的发现/过滤/前缀/刷新。

> 实锤来源：本地 `javap` 核实 `spring-ai-mcp-1.1.6.jar` / `mcp-core-0.18.2.jar`（依赖树已确认：`spring-ai-starter-mcp-client:1.1.6` → `spring-ai-mcp:1.1.6` → `io.modelcontextprotocol.sdk:mcp:0.18.2`）、`javap` 核实 `ToolCallingManager` / `ToolCallbackResolver`、现有 `ToolAutoConfiguration` / `AgentModeStrategy` 源码。

### 版本核对

- `v1.1.6` release note：确认 MCP auto-config 增加 `@ConditionalOnMissingBean`，并升级 MCP SDK / MCP annotations。
- `v1.1.7` release note：未列出 MCP 相关变更。
- `v1.1.8` release note：仅列出依赖升级，其中 MCP SDK 升到 `0.18.3`。
- 当前官方 MCP overview 页面标识为 `Spring AI 1.1.8`，并明确列出 client/server transports、roots / sampling（client）以及 MCP Annotations。

**版本基准决策**：本项目基准 = **1.1.6**（与 `pom.xml` 的 `spring-ai-bom` 一致）。所有 MCP class 名、配置键、运行时行为以 **1.1.6 源码**为唯一事实来源；1.1.8 overview 页仅作能力清单参考，不作为 API 事实。理由：1.1.6→1.1.8 间 MCP SDK 从 `0.18.2` 升至 `0.18.3`，类名/行为可能漂移；混用版本基准会让 spike 与落地基于错误前提。是否升级到 1.1.8 另议——注意 1.1.8 的 chat memory breaking change（advisors 需显式 conversation ID）会波及现有 advisor 链，升级需单独评估。Phase 1 spike 必须在 1.1.6 源码/依赖上执行。

---

## 1. 背景与结论

单体 Spring Boot 应用，主入口 [`SmartRagApplication`](../src/main/java/com/smart/rag/SmartRagApplication.java)，`pom.xml` 统一管理 `spring-ai-bom = 1.1.6`（`spring-ai.version` property），已引入 `spring-ai-starter-mcp-client`（版本由 BOM 管）。现有"工具层"完全建立在 Spring AI `ToolCallback` 之上：

- [`ToolRegistry`](../src/main/java/com/smart/rag/chat/tool/ToolRegistry.java) 构造期收集 `@Tool` bean → `ToolCallback[]`
- [`AgentToolCallbackFactory`](../src/main/java/com/smart/rag/agent/tool/callback/AgentToolCallbackFactory.java) **per-request、按意图**筛选 `ToolCallback[]`，闭包捕获请求级 `ToolWorkspace`
- [`ToolAutoConfiguration`](../src/main/java/com/smart/rag/config/ToolAutoConfiguration.java) 用 `StaticToolCallbackResolver` 注入全局 `ToolCallAdvisor`（order=2）
- [`AgentModeStrategy`](../src/main/java/com/smart/rag/agent/mode/AgentModeStrategy.java) **per-request 重建** advisor 链：`:158` 构建工具集 → `:167` 新建 resolver → `:171` 自建 `GuardrailEnforcingToolCallAdvisor`（每轮护栏），`:142-144` **显式排除全局 `ToolCallAdvisor`**，`:271` 用 `options.toolCallbacks(...)` per-request 注入

两条链的 `javap` 实锤：`DefaultToolCallingManager` 持有 `toolCallbackResolver`（+ `observationRegistry` / `toolExecutionExceptionProcessor`，非工具供给侧），**不持有 `ToolCallbackProvider`**——工具枚举走 `resolveToolDefinitions(options)`，从 options 取，resolver 无法主动注入新工具。因此全局静态链无法自己变动态，而 Agent 链已 per-request，天然动态。

### 为什么把 MCP 建成内聚领域，而不是"挂到工具链的适配薄壳"

starter 把连接/发现/前缀/过滤/刷新全做了——如果项目侧只做"配置 + 健康脱机"的薄壳，MCP 模块抽掉"接入工具链"出口后**剩不下任何领域内核**，且 resources/prompts/per-user/stdio 一旦长出来就会碎片化。因此确立 `McpServer` 领域门面为内核：有自己的领域语言，三能力统一，authz 在内核层收敛，starter 类型（`McpSyncClient` / `SyncMcpToolCallbackProvider` / `McpSchema.*`）**只活在 `mcp/runtime`+`mcp/config`，不跨出到 `core`/消费者**（§4.3 ArchUnit 强制）。

### 内核与 starter 的职责边界（核实驱动）

本地 `javap` 核实 `SyncMcpToolCallbackProvider`（`org.springframework.ai.mcp`）**自带**：

- `implements ToolCallbackProvider, ApplicationListener<McpToolsChangedEvent>` —— 事件自动刷新缓存（`invalidateCache()` + `Lock`）
- 构造器接受 `McpToolFilter`（`BiPredicate<McpConnectionInfo, McpSchema.Tool>`）+ `McpToolNamePrefixGenerator`

因此**内核不重复这些**：tools 的发现/前缀/过滤/缓存刷新全部复用 provider；`McpServer` 内核的真增量只有三件——**① authz（starter 没有）② 三能力统一门面（starter 把 resources/prompts 留在 `McpSyncClient` 上，无统一门面）③ starter 类型封装（不外泄 `McpSyncClient`/`McpSchema`）**。这让内核更精炼、内聚性更强。

### 为什么不拆多模块 / 不自研协议栈

官方 MCP Java SDK 由 Spring AI 团队维护，`spring-ai-starter-mcp-client` 是其 Boot 装配。自研协议栈=重写框架能力且与现有链脱节。无独立发布/运行/依赖冲突/通用库需求，不拆 Maven 模块——内聚性由**包依赖纪律**（§4.3）保证，而非物理拆分。

---

## 2. 设计目标 / 3. 非目标

**目标**：① 连接外部 MCP server（STDIO / SSE / Streamable-HTTP）；② 远端 tools 经 `adapter` 以 `ToolCallback` 接入 Agent 链；③ resources/prompts 经 `core` 门面服务业务；④ 统一超时/重试/降级，默认 `fail-soft`；⑤ 复用现有 service，不直接依赖 controller/mapper；⑥ 默认开启、可配置关闭（开关语义见 §10）。

**非目标**：不单独部署；不改造成新 Web API 层；不统一本地/远端注册机制（仅在 `ToolCallback` 出口合并）；不引入多模块；**本阶段不覆盖 stdio 子进程 server**；**本阶段不做 per-user MCP 凭据/会话**（§7 BYOK 联动列为后续）；**本阶段不把 roots / sampling / resource 订阅 / annotation-driven client hooks 纳入首批领域模型**，但保留扩展位（`McpSyncClient` 已具备 `addRoot/subscribeResource/setLoggingLevel` 等能力）。

---

## 4. 领域内核：`McpServer`

### 4.1 内核抽象

把"一个远端 MCP server 在本项目内的代理"建模为一等领域对象，三能力对等统一：

```java
// mcp/core — 领域内核，零外部依赖（不 import Spring AI tool / agent / starter MCP 类型）
public interface McpServer {
    ServerId id();                 // 命名空间标识（用于路由）
    McpServerHealth health();      // alive / degraded / down
    McpTools tools();              // tools 能力（发现/前缀/刷新复用 provider，内核只加 authz）
    McpResources resources();      // resources 能力（路径 C）
    McpPrompts prompts();          // prompts 能力（路径 C）
}

public interface McpTools {
    List<McpTool> visibleTo(Subject subj, AgentIntent intent);   // authz + intent 双过滤（见下）；委托 provider 产出
    McpToolResult call(String name, McpArgs args, Subject subj); // 硬 authz + 委托 McpSyncClient.callTool
}
public interface McpResources {
    McpResource read(URI uri, Subject subj);   // 硬 authz + URI 白名单；内部包成 ReadResourceRequest
}
public interface McpPrompts {
    McpPrompt get(String name, McpArgs args, Subject subj);     // 硬 authz
}
```

`McpTool / McpResource / McpPrompt` 是**项目侧净化后的领域模型**（非 starter 的 `McpSchema.*`）。各模型字段（已对齐 1.1.6 `javap` 实锤）：

- **`McpTool`**：`name`（**前缀后**全名，如 `knowledge_search`）、`description`、`inputSchema`（**JSON 字符串**——`McpSchema.Tool.inputSchema()` 返回 `McpSchema$JsonSchema` record，非 String；`runtime` 在装配时从 provider 产出的 `ToolCallback.getToolDefinition().inputSchema()` 取已序列化的 JSON string 直接存入 `McpTool`，避免自己 re-serialize）。adapter 直接喂给 `FunctionToolCallback.builder(...).inputSchema(...)`，**`inputType` 必须是 `Map`**（非 `String`，否则执行时 `readValue` 抛异常，见 §6.1 B1）。
- **`McpArgs`**：持 `Map<String,Object>`；`McpArgs.of(Map)` 直接包（**无 `fromJson`**——adapter 用 `inputType(Map)`，框架已把 LLM 的 JSON args 反序列化成 Map 传给 BiFunction，见 §6.1 B1）；内核组装 `new CallToolRequest(rawName, args.asMap())`（`CallToolRequest(String, Map)` 已 `javap` 核实）。
- **`McpToolResult`**：`text`（文本内容拼接）+ `isError`（取自 `CallToolResult.isError()`，**不可抹平**——`isError=true` 要让 adapter 上报为工具错误，否则误导 LLM）+ 非文本 content 标记（Phase 1 仅落 text，image/resource 留扩展位）。
- **`Subject`**：`userId` + `teamId`（+ 后续 roles），由消费侧从 `ToolWorkspace.getUserId()/getTeamId()` 构造，**非** `workspace.subject()`（该方法不存在）。
- **`McpServerHealth`**：`alive/degraded/down` + 详情；**由三态熔断器状态驱动**（CLOSED=alive / HALF_OPEN=degraded / OPEN=down，见 §11.2），是熔断器的**只读投影**——非独立状态机，熔断器自带完整转换与恢复路径（OPEN→cool-down→HALF_OPEN→探测成功→CLOSED）。Phase 1 不做主动心跳轮询（`McpToolsChangedEvent` 是工具变更非连接健康，不能作 liveness 依据）。

> **`visibleTo` 双过滤语义**：既做 **authz**（未授权工具对调用方不可见，纵深防御——避免把 name/description 泄露给无权主体），又做 **intent 路由**（按 `McpToolPolicy.routing(tool→intent)` 过滤）。`call()` 再做一次硬 authz 兜底。两层都过才暴露。

**实现位置（关键）**：`McpServer` 的**接口与领域模型**在 `mcp/core`（零 starter 依赖）；但**实现类**（持有 `McpSyncClient` / provider 引用的那部分）必须 import starter 类型，因此实现在 `mcp/runtime`（见 §4.2）——`core` 不持有任何 starter 类型引用。文中"`McpServer` 内部委托"指**抽象层面**的委托关系，具体委托代码在 `runtime`。`McpSyncClient`（位于 `mcp-core-0.18.2.jar`，包 `io.modelcontextprotocol.client`；三能力方法 `callTool/listTools/readResource/getPrompt` 等已 `javap` 核实）与 `SyncMcpToolCallbackProvider`（tools 发现/前缀/过滤/刷新）的 starter 类型**不跨出 `runtime`+`config`**。

### 4.2 包结构与对外出口

```text
src/main/java/com/smart/rag/mcp/
├── core/        领域内核：McpServer/McpTools/McpResources/McpPrompts/McpServerRegistry 接口 + 领域模型（Tool/Resource/Prompt/Subject/ServerId/McpArgs/McpToolResult/McpServerHealth）—— 零 starter 依赖
├── runtime/     内核实现：McpServerImpl 等（持有 McpSyncClient + provider 引用）；唯一（除 config 外）可 import io.modelcontextprotocol.* / org.springframework.ai.mcp.* 的包
├── adapter/     对外出口①：McpToolCallbackAdapter（core.Tool → Spring AI ToolCallback）；唯一可 import org.springframework.ai.tool.* 的包
├── policy/      McpAuthorizer（硬 authz，作用于 core 三能力）+ McpToolPolicy（纯规则数据）；纯领域，零 starter 依赖
├── config/      starter 装配边界：AllowlistMcpToolFilter（implements McpToolFilter）+ McpToolNamePrefixGenerator + McpSyncClientCustomizer + @ConfigurationProperties；可 import core/policy/runtime + starter 类型
└── health/      fail-soft（内核门面 try/catch 降级）+ 健康指标
```

> **`AllowlistMcpToolFilter` 归属修正（B3）**：它 `implements McpToolFilter` 必然 import `org.springframework.ai.mcp.McpToolFilter` / `McpConnectionInfo` / `McpSchema.Tool`——全是 starter 类型，**不能**放"纯领域"的 `policy`。它读 `McpToolPolicy`（policy）做规则判定，自身是 starter 装配产物，放 `config`（config 可依赖 policy）。`policy` 因此保持零 starter 依赖。

**两个对外出口，且仅此两个**：

- **出口①（→ LLM 工具链）**：`mcp/adapter` 把 `McpTools` 转 `ToolCallback`，喂给 `AgentToolCallbackFactory`。唯一直接依赖 `org.springframework.ai.tool.*` 的地方。（**本轮不接**——adapter 实现并自测，但不注入 `AgentToolCallbackFactory`，见 §13 本轮范围。）
- **出口②（→ 路径 C 业务）**：业务 service 注入 `McpServer` 调 `resources()/prompts()`，不接触 starter 类型。（Phase 3。）

### 4.3 包依赖纪律（内聚的可执行保证）

内聚性靠依赖方向落地，用 ArchUnit 在测试套件中强制（`archunit-junit5` 1.3.0，test scope，pom 已就绪）：

- `mcp/core`：**禁止** import `org.springframework.ai.tool..`、`org.springframework.ai.mcp..`、`io.modelcontextprotocol..`、`com.smart.rag.agent..`、`com.smart.rag.chat..`、`com.smart.rag.mcp.runtime..`、`com.smart.rag.mcp.adapter..`、`com.smart.rag.mcp.config..`。纯领域（接口 + 模型）。
- `mcp/runtime`：可 import `core` + `io.modelcontextprotocol..` + `org.springframework.ai.mcp..`；**禁止** `org.springframework.ai.tool..`（那是 adapter 的事）、`agent..`、`chat..`。
- `mcp/adapter`：**唯一**允许 import `org.springframework.ai.tool..` 的包；依赖 `core`。
- `mcp/policy`：依赖 `core` **only**（零 starter 类型——故 `AllowlistMcpToolFilter` 不在此，见 §4.2）。
- `mcp/config`：依赖 `core` + `policy` + `runtime` + starter 类型（`org.springframework.ai.mcp..`）；装配根。
- `mcp/health`：依赖 `core`（+ `runtime`）。
- 消费者（`agent`、业务 service）：只依赖 `mcp/core`，**禁止**直注 `McpSyncClient` / `SyncMcpToolCallbackProvider` / `runtime` 实现类。

> 关键可拦截约束：①`core` 零 starter/agent/chat 依赖；②`adapter` 是唯一 `tool..` 导入方；③`runtime`+`config` 是仅有的两个 `org.springframework.ai.mcp..`/`io.modelcontextprotocol..` 导入方（`policy`/`health` 不得漏 starter 类型）；④消费者只碰 `core`。"starter 类型不跨出边界"从口号变成 CI 约束。

---

## 5. 技术选型与官方能力速查

采用 **B 为主 + C 兜底**：

```xml
<dependency>
  <groupId>org.springframework.ai</groupId>
  <artifactId>spring-ai-starter-mcp-client</artifactId>
</dependency>
```

`spring-ai-starter-mcp-client`（core starter）提供 `STDIO`、Servlet-based `Streamable-HTTP`、`Stateless Streamable-HTTP`、`SSE`；`spring-ai-starter-mcp-client-webflux` 提供 WebFlux 版。本项目同步栈，用 core starter、`type: SYNC`。

### starter 已自带、**不要自研**的能力（本地 javap 核实）

| 能力 | 实锤（1.1.6 jar） | 项目侧落点 |
|---|---|---|
| 工具发现 + `ToolCallback` 桥接 | `org.springframework.ai.mcp.SyncMcpToolCallbackProvider implements ToolCallbackProvider` | `runtime` 内部委托，不外泄 |
| **缓存 + 自动刷新** | 同类 `implements ApplicationListener<McpToolsChangedEvent>` + `invalidateCache()` + `Lock` | **零代码**，§6 不自研刷新 |
| **tool 级过滤（allowlist）** | `McpToolFilter extends BiPredicate<McpConnectionInfo, McpSchema.Tool>`，注入 provider 构造器 | `policy` 实现一个 bean |
| 多 server 前缀 | `McpToolNamePrefixGenerator`（默认 `DefaultMcpToolNamePrefixGenerator`——**不加 server 前缀**，仅格式化/去重/截断；自定义 bean 是**必须的**，非可选） | 自定义 `<server>_` 前缀 bean（§9，**必选**；`_` 非 `.`，见 E6） |
| client 定制 | `McpSyncClientCustomizer.customize(name, SyncSpec)` | `config`（timeout/roots/elicitation/logging） |
| 覆盖默认 bean | `defaultMcpToolNamePrefixGenerator()` 有 `@ConditionalOnMissingBean`（可替换）；`mcpToolCallbacks()` **无**此注解（不可直接覆盖 provider bean，需 `@Primary` 或排除自动配置类） | prefix generator 写 bean 即替换；provider 整体替换需额外手段 |
| 会话/连接生命周期 | starter 自动管理（`@Bean(destroyMethod="close")`） | 不自建 |
| `McpSyncClient` 三能力 | `callTool/listTools/listResources/readResource/listPrompts/getPrompt`（mcp-core jar） | `runtime` 委托 |
| annotation 模块（`@McpToolListChanged` 等） | **不在** `spring-ai-mcp`，starter 不传递；需单独引入 | **首批不引**，用 provider 自带的 `ApplicationListener` |

> 说明：上表 class-name 级 API 均已在本地 1.1.6 jar 核实存在；autoconfig 经 `ObjectProvider` 自动拾取 `McpToolFilter`/prefix bean 亦已 `javap`+字节码坐实（§7）。Phase 1 spike 只确认**运行时行为**（启动不可达产出、握手失败降级、`initialize()` 是否被 autoconfig 调用）。

---

## 6. 集成点：内核的两个出口

```text
外部 MCP Server
  ↑ McpSyncClient（starter：连接/操作） + SyncMcpToolCallbackProvider（发现/前缀/过滤/自动刷新）
  │   ── starter 类型只活在 mcp/runtime + mcp/config，不跨出到 core/消费者（§4.3）
  ┌──────── mcp/core（接口+模型） + mcp/runtime（实现） ────┐
  │            McpServer 门面（接口在 core，实现持 client）   │
  │   tools()        resources()   prompts()│  ← authz 在内核层统一（§8）
  └───┬───────────────────┬─────────────┬──┘
      │出口①              │出口②        │出口②
      ▼                   ▼             ▼
  mcp/adapter          业务 service    业务 service
  → ToolCallback[]      readResource    getPrompt
      │
      ▼
  AgentToolCallbackFactory.createToolCallbacks
    = 本地工具集  ++  adapter.toCallbacks(mcpServer.tools(), intent, subj)
      │
      ▼  AgentModeStrategy:167 resolver + :271 options.toolCallbacks  → LLM
```

### 6.1 出口①：追加进 Agent 链（per-request 动态）

Agent 链已 per-request 注入工具（`AgentModeStrategy:271`）。MCP 接入 = 在 `AgentToolCallbackFactory` 产出里追加 adapter 转换的 MCP 工具，**不动全局静态链**：

```java
// mcp/adapter — core → Spring AI 唯一出口
@Component
class McpToolCallbackAdapter {
    ToolCallback[] toCallbacks(McpTools tools, AgentIntent intent, Subject subj) {
        return tools.visibleTo(subj, intent).stream()           // 内核已 authz + intent 双过滤
            .map(t -> FunctionToolCallback.<java.util.Map<String,Object>, String>builder(
                        t.name(),                                // 前缀后全名
                        (args, ctx) -> render(tools.call(t.name(), McpArgs.of(args), subj)))
                    // args 已是 Map：框架先 readValue(toolInput, inputType) 再 apply
                    .description(t.description())
                    .inputSchema(t.inputSchema())               // ★ MCP 真实 schema（JSON 串），不可省
                    .inputType(new org.springframework.core.ParameterizedTypeReference<Map<String,Object>>(){}) // ★ Map，非 String
                    .build())
            .toArray(ToolCallback[]::new);
    }
    private static String render(McpToolResult r) {             // isError 不抹平
        return r.isError() ? "[TOOL_ERROR] " + r.text() : r.text();
    }
}
```

> **`inputType` 必须是 `Map`，不是 `String`（B1 修正，源码证伪）**：`FunctionToolCallback.call()`（1.1.6 源码 `:103`）执行 `I request = JsonParser.fromJson(toolInput, this.toolInputType)`，而 `JsonParser.fromJson` 就是 `OBJECT_MAPPER.readValue(json, type)`。若 `inputType(String.class)`，LLM 按 MCP `inputSchema`（object）传来 `{"query":"x"}` 时，`readValue('{"query":"x"}', String.class)` 遇 `START_OBJECT` → **`MismatchedInputException`** → `ToolExecutionException`，**工具必炸**。文档早先"框架把 JSON args 原样以 String 传入"的说法是错的。正确：`inputType(Map<String,Object>)`——`readValue` 把 JSON object 反序列化成 `Map` 传给 BiFunction，BiFunction 直接 `McpArgs.of(args)` 包成 `McpArgs`（**无需 `fromJson`**），再 `new CallToolRequest(rawName, args.asMap())`。`build()` 的 schema 三元（`:226`）：`inputSchema` 非空就用它、不调 `JsonSchemaGenerator.generateForType`，故 Map 的生成 schema 被覆盖、不参与。`FunctionToolCallback.Builder.inputSchema(String)` + `inputType(Type|ParameterizedTypeReference)` setter 均已 `javap` 核实。
>
> **schema 必须显式传**：`inputSchema` 取自 `McpSchema.Tool.inputSchema()`（`JsonSchema` record），经 provider 产出的 `ToolCallback.getToolDefinition().inputSchema()` 已序列化好，`McpTool` 直接承载（`runtime` 在装配时从 provider callback 取，避免自己 re-serialize `JsonSchema`）。不传则 schema 退化为 `{"type":"string"}`（String）或 Map 的泛化 object，LLM 不知工具真实参数。
>
> **`isError` 不抹平（C5）**：`CallToolResult.isError()` 为 true 时，`McpToolResult.isError()` 置位，adapter `render()` 前缀 `[TOOL_ERROR]` 回流 LLM（Phase 1 文本契约；后续可换 `toolCallResultConverter` 精细化）。直接 `.text()` 会把错误当正常结果，误导 LLM。
>
> adapter 产的 `ToolCallback` 执行时委托回 `McpTools.call()`，任何 LLM 工具调用都必经内核 authz——执行层兜底天然落在内核。`subj` 由消费侧（后续 `AgentToolCallbackFactory`）从 `ToolWorkspace.getUserId()/getTeamId()` 构造后传入（**非** `workspace.subject()`，该方法不存在）。
>
> **✅ B1 已单测坐实（`FunctionToolCallbackB1Test`，6 case 全绿）**：`inputType(String.class)` + JSON object args → 抛 `MismatchedInputException`（根因 `Conversion from JSON to java.lang.String failed`）；`inputType(Map)` + JSON object args → BiFunction 收到正确 Map；`.inputSchema()` 原样透传到 `ToolDefinition`；不传则退化。源码推导经运行时确认。
>
> **📎 附带发现（影响 adapter 实现）**：`ToolCallback.call()` 的返回值经**默认 `ToolCallResultConverter` 做 JSON 序列化**——BiFunction 返回 String `"ok:x"`，`call()` 回流成 `"ok:x"`（带引号）。即 adapter `render(McpToolResult)` 产出的 String 会被框架再 JSON-encode 喂 LLM。Phase 1 用默认 converter 可接受（Spring AI 工具框架会正确处理）；若要对 `[TOOL_ERROR]` / 多行文本精细控制，后续可 `.toolCallResultConverter(...)` 自定义。

### 6.2 工具集获取：主动拉取 + 缓存（不依赖 list_changed）

采用**主动拉取策略**：`tools/list`（`listTools()`）是 MCP 标准协议的**强制**方法，远比可选的 `notifications/tools/list_changed` 可靠；且 MCP 工具集通常准静态。因此：

- **拉取时机：lazy + 缓存**——首次 Agent 请求需要 MCP 工具时，`provider.getToolCallbacks()` 触发 `listTools` 并走内置缓存（`Lock` + 缓存）；**非启动立即拉、非 per-request 频繁拉**，避免对远端造成压力。
- **不依赖 `list_changed`**：该通知是 server **可选**能力（握手时 `capabilities.tools.listChanged` 声明），第三方未必发；client 不以其为刷新依赖。
- **缓存失效**：不主动失效。若 server 碰巧声明 listChanged 并发通知，provider 的 `ApplicationListener<McpToolsChangedEvent>` 自动 `invalidateCache()`（免费 bonus）；不发也不影响。需强制刷新时手动 `invalidateCache()` 或重启。
- **工具集语义**：准静态；变更滞后到下次拉取/重启可接受。
- per-request 追加（§6.1）每次取 provider 当前缓存产出，每请求新建 resolver，绕开 `StaticToolCallbackResolver` 不可变约束。
- 全局静态链无法自己变动态（`DefaultToolCallingManager` 无 provider 字段、枚举走 options）；故 MCP **只进 Agent 链**，全局链继续只管本地工具。

### 6.3 出口②：路径 C 经门面

```java
// 业务 service — 经 core 门面，不直注 McpSyncClient
McpResource doc = mcpServer.resources().read(URI.create("knowledge://x"), subject);
McpPrompt p = mcpServer.prompts().get("summarize", args, subject);
```

`McpServer` 本身即 §8 授权门面。注意适配：`McpSyncClient.readResource` 接受 `Resource`/`ReadResourceRequest`（**非 URI**），`McpResources.read(URI)` 在内核内部把 URI 包成 `ReadResourceRequest` 再委托。`McpServer` 自带 authz + URI 白名单，无需另造 `AuthorizedMcpClient`。

---

## 7. 安全：不可信输入与授权策略

远端 server 返回的元数据与结果都不可信：

- **tool name/description/schema**：复用 starter `McpToolFilter` 做 inclusion/exclusion（见下）；description 截断（防 prompt 爆炸，不防语义注入——语义由 §8 内核 authz + guardrail 兜底）。
- **tool 输出**：indirect prompt injection 主路径；内核对回流做长度控制 + 标记包裹，越权企图由 guardrail 拦截。
- **高风险工具**（写入/删除/外呼/执行）：配置 `risk-level`，默认不进通用意图，调用需授权。
- **路径 C**：`readResource(uri)` / `getPrompt` 同进 LLM 上下文且 URI 是出站请求；门面统一授权 + URI 白名单。

### allowlist：实现 `McpToolFilter`，不自研过滤

starter 的 `McpToolFilter extends BiPredicate<McpConnectionInfo, McpSchema.Tool>`（包 `org.springframework.ai.mcp`，`javap` 核实）。项目实现一个 bean 放 **`mcp/config`**（B3：必然 import starter 类型，不能放 `policy`）：

```java
// mcp/config — starter 装配边界
@Component
class AllowlistMcpToolFilter implements McpToolFilter {   // BiPredicate<McpConnectionInfo, McpSchema.Tool>
    private final McpToolPolicy policy;                   // 来自 mcp/policy
    private final McpToolNamePrefixGenerator prefixGen;   // 复用同一个前缀 bean，保证键一致
    public boolean test(McpConnectionInfo conn, McpSchema.Tool tool) {
        String prefixed = prefixGen.prefixedToolName(conn, tool);   // = yaml 键，如 knowledge_search
        return policy.explicitlyAllowed(prefixed);                  // 显式允许制（按前缀全名）
    }
}
```

> **键命名空间统一（C1 修正）**：`McpToolPolicy` 与 yaml 一律以**前缀全名**为键（`knowledge_search`）。`McpToolFilter.test(conn, tool)` 看到的是**未前缀**的 `tool.name()`，但能拿到 `conn`——用它 + 注入的 `McpToolNamePrefixGenerator` bean **反算前缀键**（`prefixGen.prefixedToolName(conn, tool)`），与 yaml 键 1:1。**filter 与 prefix generator 必须用同一个 bean**，避免前缀逻辑两份漂移。`McpConnectionInfo` 无直接 server 名字段，`prefixGen` 内部自行走 `conn.initializeResult().serverInfo().name()`。

> **三层过滤的字段分工（C2 修正）**——`McpToolPolicy` 一条规则带多字段，但**各层只读自己那部分**：
>
> | 层 | 落点 | 读 policy 哪个字段 | 有 Subject？ |
> |---|---|---|---|
> | 静态 allowlist | `AllowlistMcpToolFilter`（provider 内，全局） | `allowlist`（inclusion） | **否**（签名只有 conn+tool）→ 只能静态判定 |
> | 硬 authz | `McpAuthorizer`（`visibleTo`/`call`/`read`/`get`，内核） | `roles` + `routing` | 是 |
> | 语义层（Phase 2） | `GuardrailEnforcingToolCallAdvisor` | `risk` + `quota` + 敏感参数 | 是 |
>
> 即：`roles`/`risk`/`quota` 是 Subject/上下文相关，**静态 filter 判不了**（它没 Subject），必须由 `McpAuthorizer`/guardrail 兜。`allowlist` 是全局 inclusion，由 filter 判。三层都过才放行；任一层默认拒绝。

> **autoconfig 拾取（已 `javap` 坐实，非 spike）**：`SyncMcpToolCallbackProvider.Builder` 有 `.toolFilter(McpToolFilter)` / `.toolNamePrefixGenerator(...)` setter；starter `McpToolCallbackAutoConfiguration.mcpToolCallbacks(...)` 经 `ObjectProvider<McpToolFilter>` / `ObjectProvider<McpToolNamePrefixGenerator>` 自动拾取 bean 注入 provider——**写 bean 即生效**，无需自定义 provider。若 Step 0 运行时发现未拾取（与 javap 结论不符），才退回自定义 `SyncMcpToolCallbackProvider` bean（用 Builder）。

### 7.1 BYOK / per-user 与 SSRF

starter 配置全局；本项目已有 BYOK（per-user 模型密钥）。**本阶段 MCP 凭据仍为全局**（非目标），后续 per-user 凭据需在 `core` 内按用户建独立 `McpServer`（隔离 `McpSyncClient`）。base-url = 出站 SSRF 风险，所有地址经白名单校验，复用项目 SSRF 先例（commit `2c5733a`）。

---

## 8. 强制认证与授权

"强制"= 落在所有路径必经的瓶颈点。领域内核化后收敛为**内核统一 + Agent 语义**两层：

| 层 | 落点 | 覆盖 |
|---|---|---|
| **内核 authz**（硬授权） | `McpTools.call` / `McpResources.read` / `McpPrompts.get`（`McpServer` 门面内） | tools + resources + prompts 全部，任何调用必经 |
| **Agent 语义层**（软策略） | `GuardrailEnforcingToolCallAdvisor`（`AgentModeStrategy:171`，每轮 check） | 风险分级、配额、敏感参数、越权拦截、二次确认 |

- **执行层兜底天然成立**：出口① 的 `ToolCallback` 委托回 `McpTools.call()`，出口② 直接走门面——没有绕过内核 authz 的路径。
- **认证（出站）与授权（调用方）分离**：出站 bearer/OAuth 由 `McpSyncClientCustomizer`（包 `org.springframework.ai.mcp.customizer`，`customize(String name, McpClient.SyncSpec)`，`javap` 核实）配置（"我是谁"）；调用方授权由 `McpAuthorizer`（"我能做什么"）。server 认证通过 ≠ 用户被授权调该工具。
- **主体来源**：`AgentModeStrategy` 中 `ctx.userId()`（~line 259-260，`AdvisorChainContext` 构造）/ `ctx.request().teamId()`（~line 155，`workspaceFactory.create` 参数），经 `ToolContext` / 门面参数透传为 `Subject`（构造 `new Subject(ws.getUserId(), ws.getTeamId())`，`ToolWorkspace` 无 `subject()` 方法）。
- **Phase 1 authz 覆盖（收窄）**：静态 allowlist（filter）+ 内核硬 authz（`visibleTo` 既 authz 又 intent；`call`/`read`/`get` 再兜底）= **三层中已落两层**。Agent 语义层（guardrail 的 `risk`/`quota`/敏感参数/二次确认）属 **Phase 2**。
- **`roles` 本期无 source，不做强制（修正）**：`Subject = (userId, teamId)`，**无 roles 字段**，项目亦无 role provider；故 yaml 的 `roles: [agent]` 本期**无判定对象**，`McpAuthorizer` 只能落 **allowlist（inclusion）+ intent 路由 + subject 存在性**。`roles`/`risk`/`quota` 强制全部待"role source"就位后再开（接 Agent 链前必须补，否则高风险工具仅靠 allowlist 兜底）。AC3 本期兑现的是"未在 allowlist → 拒"，不是"roles 不符 → 拒"。
- **`visibleTo` 做纵深防御**：未授权工具在 `visibleTo` 即被剔除（不进 `McpTool[]`），LLM 看不到其 name/description；`call()` 再硬判一次。两层 authz 避免"可见但不可调"的信息泄露。

---

## 9. 多 server 与命名空间

`McpServer` 实例与 starter 的 `McpSyncClient` 一一对应。复用 starter `McpToolNamePrefixGenerator` 做稳定前缀。**真实接口签名**（`javap` 核实）：

```java
public interface McpToolNamePrefixGenerator {
    String prefixedToolName(McpConnectionInfo connInfo, McpSchema.Tool tool);  // 非 (String, String)
    static McpToolNamePrefixGenerator noPrefix();
}
```

`McpConnectionInfo` 是 record（`ClientCapabilities/Implementation/InitializeResult`），**无直接 server 名字段**——server 名从 `initializeResult().serverInfo().name()` 提取（路径以 `McpSchema` 源码为准）。自定义前缀：

```java
@Bean
McpToolNamePrefixGenerator mcpToolNamePrefixGenerator() {
    return (connInfo, tool) ->
        connInfo.initializeResult().serverInfo().name() + "_" + tool.name();  // knowledge_search（`_` 对齐 Spring AI 工具名约定，见 §9 E6）
}
```

> **前缀分隔符（E6，Spring AI 字节码核实）**——本约束只与 Spring AI 框架有关，与具体模型 provider 无关（本项目模型按 BYOK 动态配置，文档不绑定 provider）：
> - ✅ 框架默认 `DefaultMcpToolNamePrefixGenerator` **不加 server 前缀**（字节码：只调 `Tool.name()` + `McpToolUtils.format`，无 `serverInfo` 调用）→ 多 server 命名空间**必须**自定义 generator。
> - ✏️ 早先"`McpToolUtils.prefixedToolName()` 底层用 `_` 分隔符"**不准确**：`prefixedToolName(server, local, separator)` 的分隔符是**参数**；`_`/`-` 是 `McpToolUtils.format` 的**清洗替换字符**，不是固定分隔符。
> - 🔧 **框架工具名约定决定分隔符**：`McpToolUtils.format` 合法字符集 = `[a-zA-Z0-9_-]`（+ CJK），**`.` 不在其中**（会被替换成 `_`）。这是 Spring AI 自身对工具名字符的约定，默认 generator 即据此清洗。本项目自定义 generator **采用 `_`**（`serverName + "_" + toolName`）**对齐框架约定**；用 `.` 则偏离约定。
> - ⚠️ **自定义 generator 须清洗组件（框架层实现要点）**：默认 generator 调 `format` 清洗 `tool.name()`；自定义 generator 若直接拼 `serverInfo.name() + "_" + tool.name()`，组件里的非法字符（空格/特殊符号）**不会被框架二次清洗**（自定义输出原样进 Spring AI 工具链）→ 实现时应走 `McpToolUtils.format` 或等价清洗组件。
> - 无论分隔符，filter 与 prefix generator **必须用同一个 bean**（§7 C1），保证 yaml 键 1:1。

前缀后仍重名视为配置错误（`noPrefix()` 多 server 抛 `IllegalStateException`，自定义 generator 应保留此语义）。

### 9.1 per-server `McpServer` 与单一聚合 provider 的拼合（A1 决议）

**张力**：论点是"复用**一个** `SyncMcpToolCallbackProvider`"（白嫖缓存/过滤/前缀/刷新），但 provider 把**所有** server 的工具拍平成一个 `ToolCallback[]`；而 `McpServer` 要 **per-server** 粒度（`id()`/`health()`/`tools()`、§11 per-server fail-hard）。两者怎么拼？

**决议（拼合方式）**——per-server 在**调用面**直接持有自己的 client，tools **发现面**委托聚合 provider 再按前缀过滤：

| 能力 | `McpServer` 怎么实现 | 用谁的 client |
|---|---|---|
| `tools()` 发现 | 委托 `provider.getToolCallbacks()`（聚合，复用缓存/过滤/前缀/刷新）→ 按本 server 前缀 `<name>.` 过滤 → 每个 callback 的 `getToolDefinition()` 组装 `McpTool` | provider（聚合） |
| `tools().call(prefixedName, args, subj)` | authz → **校验 `prefixedName` 前缀 == 自己 `id()`**（防跨 server 误调，不符则拒）→ 剥前缀 → `new CallToolRequest(rawName, args.asMap())` | **本 server 自己的** `McpSyncClient` |
| `resources().read(uri, subj)` | authz + URI 白名单 → `new ReadResourceRequest(uri.toString())` | 本 server 自己的 client |
| `prompts().get(name, args, subj)` | authz → `new GetPromptRequest(...)` | 本 server 自己的 client |

- **`McpServerRegistry`（core 接口 / runtime 实现）**：注入 **`ObjectProvider<List<McpSyncClient>>`** + **`ObjectProvider<SyncMcpToolCallbackProvider>`** + **`ObjectProvider<McpToolNamePrefixGenerator>`**（全可选——无 connections 空载不抛）。**因 `initialized=false`（§10），client 交付时未握手** → registry 建期对每个 client `if (!isInitialized()) try { client.initialize(); } catch { → down }`：成功取 `getCurrentInitializationResult().serverInfo().name()` 作 `ServerId` 建 `McpServer`(alive)；失败（不可达/握手失败）→ 标 `down`、跳过，**不影响其他 client**。`list()/find(ServerId)` 暴露给消费侧。
- **同名 server 检测（修正）**：`ServerId` = `serverInfo.name()`（server 自报、可撞名）。registry 建期若发现**多个 client 的 `serverInfo.name()` 相同** → **显式抛配置错误**（不静默合并——否则前缀过滤会把两家工具并成一个 McpServer）。`McpConnectionInfo` 不暴露配置连接名，故无法用 operator-controlled 标识区分；同名即视为配置冲突。
> **`McpSyncClient.initialize()` 握手（D4）**：`McpSyncClient` 有 `public McpSchema.InitializeResult initialize()` 方法——必须调用后 `getCurrentInitializationResult()` 才非 null（官方用法：`McpClient.sync(...).build().initialize()`）。starter autoconfig 是否自动调用 `initialize()` 是 **Step 0 ①② 必验项**；若 autoconfig 未调用，`McpServerRegistry` 建期需手动初始化并 try/catch 不可达 client 降级。`McpConnectionInfo.initializeResult()` 也可能为 null（bytecode 有 null check），自定义前缀 bean 应做 defensive check。
- **"一一对应"收窄**：§9 的"实例与 `McpSyncClient` 一一对应"= **逻辑一一对应**（每个 client 一个 `McpServer`，调用面绑自己 client）；**物理上 tools 发现共享聚合 provider**（缓存/刷新是 provider 级，非 per-server）。这样既保 per-server fail-hard（某 server down 只影响它自己），又不重复 starter 的发现/缓存/刷新。
- **握手时序（Step 0 ①② 相关）**：client 是否在 bean 创建期就 `initialize()` 决定了 `getCurrentInitializationResult()` 何时可用。若未初始化/不可达，该 `McpServer` 标 `down`、不进可用列表（fail-soft）。Step 0 ①② 坐实后定：是否需在 registry 建期做 try/catch 把不可达 client 降级（而非让其抛穿启动）。
- **前缀过滤的可行性**：`McpToolNamePrefixGenerator` 产 `<serverInfo.name()>_<tool.name()>`，`McpServer.id() = serverInfo.name()`，二者同源 → `tools()` 用 `id() + "_"` 前缀过滤 provider 产出，归属判定可靠。

---

## 10. 配置

复用 starter 原生结构（`spring.ai.mcp.client.*`），项目仅补充 `McpToolPolicy`（tool 级 routing/risk/allowlist 规则数据）。

```yaml
spring:
  ai:
    mcp:
      client:
        enabled: true        # 开启=启动即连 MCP server、发现工具、接入 Agent 链；关闭=完全不初始化 MCP（无连接/无工具/零开销）；默认 true（1.1.6 字节码核实）
        type: SYNC
        request-timeout: 30s              # starter 默认 20s（1.1.6 字节码核实）
        initialized: false   # ★ 必须 false（默认 true）——true 则 starter eager initialize()，单个 server 不可达会阻塞整个启动；false 让 registry 自己 per-client init + try/catch，实现 server 间隔离（§11）
        sse:
          connections:
            knowledge:
              url: https://mcp.example.com
        streamable-http:
          connections:
            ops:
              url: https://ops.example.com
              endpoint: /mcp
mcp:
  policy:
    tools:
      "knowledge_search":    { intent: RETRIEVAL,      risk: low }
      "knowledge_fetch":     { intent: DEEP_RETRIEVAL, risk: low }
      "ops_ticket_create":   { intent: GENERAL_TOOL,   risk: high, roles: [agent] }   # roles 本期无 source，不强制（§8）
    default: deny                         # 显式允许制；键 = 前缀全名（`_` 分隔，§9 E6）
```

> starter 配置键（`spring.ai.mcp.client.*`）以 1.1.6 metadata spike 为准；`McpToolPolicy` 仅承载项目级 routing/risk/allowlist/roles 规则。
> **键 = 前缀全名**（`knowledge_search`，C1）：与 `AllowlistMcpToolFilter` 经 `prefixGen` 反算的键一致。**字段分属不同层**（C2）：`allowlist`（inclusion，静态 filter 读）、`roles`+`routing`（`McpAuthorizer` 读）、`risk`+`quota`（guardrail 读，Phase 2）。`@ConfigurationProperties("mcp.policy")` POJO 用 `Map<String, ToolRule>` + `default: deny`。

---

## 11. 错误处理、异常体系与三态熔断器

MCP 是出站第三方调用，故障是常态。**复用项目既有的三级异常体系与三态熔断器设施**（`infrastructure/exception` + `infrastructure/fallback` + `infrastructure/llm/resilience`），不自研、不引新依赖（pom 无 `spring-retry`，已核实；actuator 已就绪）。三者关系：**异常分级**（判定重试是否计数、是否计熔断）→ **重试**（同 server 瞬态错误，调用级）→ **三态熔断器**（统一 health 状态机 + server 级退避 + OPEN 快速失败）。

> 实锤来源：本地源码核实 `AbstractException`/`ClientException`/`ServiceException`/`RemoteException` + `IErrorCode`/`RemoteErrorCode`、`CircuitBreakerState`/`ModelCircuitBreakerRegistry`/`CircuitBreaker`/`RetryPolicy`/`FallbackEligibility` + `CircuitBreakerProperties`/`RetryConfig`/`ProbeProperties`（签名、默认值、状态转换均经源码确认）。

### 11.1 异常分层：MCP 故障映射到 A/B/C 三级

项目异常体系（`AbstractException` 携带 `IErrorCode`，`GlobalExceptionHandler` 统一兜底）分三级，MCP 故障按"重试 / 换路径是否有意义"映射：

| MCP 故障 | 异常类（分级） | 错误码 | 可重试？ | 计熔断？ |
|---|---|---|---|---|
| 调用方 authz 拒绝（`McpAuthorizer` 判否） | `ClientException`（A 类 1xxxxx） | 复用 `ClientErrorCode` 权限类 | 否 | 否 |
| 工具不存在 / 参数非法 / URI 非白名单 | `ClientException`（A 类） | 复用 `ClientErrorCode` 参数类 | 否 | 否 |
| 配置错误（同名 server / policy 缺失 / prefix 冲突） | `ServiceException`（B 类 2xxxxx） | 复用 `ServiceErrorCode` | 否 | 否 |
| server 不可达 / 握手失败 / 连接重置 | `RemoteException`（C 类 3xxxxx） | `MCP_SERVER_UNREACHABLE`(302001) | 是（瞬态） | **是** |
| 调用超时（`request-timeout`） | `RemoteException`（C 类） | `MCP_TOOL_TIMEOUT`(302002) | 是 | **是** |
| server 限流（429 类比） | `RemoteException`（C 类） | `MCP_RATE_LIMITED`(302003) | 是 | **是** |
| 熔断器 OPEN（快速失败） | `McpCircuitOpenException extends RemoteException` | `MCP_CIRCUIT_BREAKER_OPEN`(302004) | 否（已熔断） | 否（本身即熔断产物） |
| 工具执行返回 `isError=true` | **非异常** — `McpToolResult.isError`（§6.1 C5） | — | — | 否（工具业务错误，非 server 故障） |

**判定原则**（复用 `FallbackEligibility.isEligible` 的同一逻辑）：A/B 类（用户/配置错误）**不重试、不计熔断**——重试或换路径结果相同；C 类（第三方故障）**可重试、计入熔断**。关键区分：`McpToolResult.isError=true` 是**工具业务层**错误（工具被正常调用后返回失败语义），**不是** server 故障，既不重试也不计熔断，由 adapter `render()` 前缀 `[TOOL_ERROR]` 回流 LLM（§6.1）——务必与"server 故障导致的调用异常"区分，否则会把工具的业务错误误记为 server 健康恶化。

> **新增内容（最小）**：`RemoteErrorCode` 增加 `302xxx` 段（MCP，与 LLM `301xxx` 段并列）——`MCP_SERVER_UNREACHABLE / MCP_TOOL_TIMEOUT / MCP_RATE_LIMITED / MCP_CIRCUIT_BREAKER_OPEN`；新增 `McpCircuitOpenException extends RemoteException`（类比 `ModelCircuitOpenException`，OPEN 态抛出、携带 `ServerId`）。A/B 类**不新增** errorcode，复用 `ClientErrorCode`/`ServiceErrorCode` 既有项——MCP 不发明新分级，只往既有三级里装故障。

### 11.2 三态熔断器：统一 health + server 级退避（复用 `CircuitBreakerState`）

复用项目既有的三态熔断器状态机（`CircuitBreakerState = {CLOSED, OPEN, HALF_OPEN}`、`ModelCircuitBreakerRegistry` 的 per-key 模型、`CircuitBreakerProperties` 阈值），**per-`ServerId`** 实例化。状态机核心（计数 + 转换 + 恢复）与"模型"无关——既有实现命名带 `Model` 前缀纯属历史（最先服务 LLM），其 per-String-key 计数语义对 MCP server 同样适用。

**三态与 health 的 1:1 映射**——`McpServerHealth` 不再是独立状态机，而是熔断器状态的**只读投影**：

| 熔断态 | health | 行为 | 转换条件（源码默认值） |
|---|---|---|---|
| `CLOSED` | `alive` | 正常放行；调用经 §11.3 重试后仍失败（C 类）→ `recordFailure` | 连续失败 ≥ `failureThreshold`（默认 **5**）→ `OPEN` |
| `OPEN` | `down` | **快速失败**，不打远端、不重试，直接抛 `McpCircuitOpenException` → 门面降级 | 经过 `openDurationMs`（默认 **30s**）cool-down → `HALF_OPEN` |
| `HALF_OPEN` | `degraded` | 放行**有限探测**（≤ `halfOpenMaxCalls`，默认 **2**）；探测成功 → `recordProbeSuccess` | 探测成功 → `CLOSED`（reset 计数）；探测失败 → 回 `OPEN`（重计 cool-down） |

> **一个机制同时解决三个原缺口**：① **health 状态机与恢复路径**——熔断器自带完整转换（OPEN→cool-down→HALF_OPEN→探测成功→CLOSED），`McpServerHealth` 只读投影即可，消除"被动翻状态无恢复路径"的残缺机（原 D3 决议升级）；② **server 级退避**——`OPEN` 的 `openDurationMs` cool-down 即 server 级退避，`HALF_OPEN` 探测即"受控重试一次"，故 MCP **不需要 spring-retry 做服务级重试**；③ **快速失败**——`OPEN` 直接拒绝，避免对已 down 的 server 持续打流、堆积超时。

> **状态转换的源码约束（复用既有语义）**：`recordSuccess` 在非 CLOSED 状态下为 no-op；**HALF_OPEN → CLOSED 仅由 `recordProbeSuccess()`（内部 `tryRecoverFromHalfOpen`）触发**，不会因 CLOSED 路径的成功计数误转。`HALF_OPEN` 探测槽由 `releaseProbe` 在所有终止信号（含 CANCEL）下释放，避免槽泄漏导致 HALF_OPEN 卡死。MCP 直接继承这套语义，不重新发明。

**落点**：`mcp/health`（或 `mcp/runtime`）建 `McpCircuitBreakerRegistry`（`@Component`，类比 `ModelCircuitBreakerRegistry`），注入 MCP 自己的 `CircuitBreakerProperties`（从新设的 `mcp.resilience.circuit-breaker` 取，**不依赖 `LlmConfig`**），key = `ServerId`。`McpServerImpl` 持有自己的 `CircuitBreaker`（类比 `infrastructure/llm/resilience/CircuitBreaker`），在 `tools().call()` / `resources().read()` / `prompts().get()` 外包 `circuitBreaker.execute(...)`——调用层 authz 兜底（§8）天然落在 `execute` 内部 `action` 里。

> **已通用化（2026-06-29 完成，方案 A + 继承）**：提取三件通用件——① `AbstractCircuitBreakerRegistry`（基类：per-key 状态机容器 + `Clock`，`CircuitBreakerProperties` 由子类构造注入，方法参数通用化为 `key`）；② `CircuitBreakerStateMachine`（从原 `ModelCircuitBreaker` 内部类提升的纯状态机，逻辑零变更）；③ `CircuitOpenException`（通用 OPEN 异常，`IErrorCode` 由装配方注入——LLM 传 `LLM_CIRCUIT_BREAKER_OPEN`、MCP 传 `MCP_CIRCUIT_BREAKER_OPEN`，避免每域复制异常类）。`ModelCircuitBreakerRegistry extends` 基类，保留类名/`@Component`/`@Autowired LlmConfig`/构造签名 → **LLM 侧 `LlmClientFactory`/`LlmCircuitBreakerAdapterRegistry`/`ProbeStreamHandler` 及其测试零改**；adapter `CircuitBreaker` 的 delegate 类型放宽到基类、OPEN 异常切到通用 `CircuitOpenException`，使 LLM/MCP 共用同一 adapter。MCP 侧 `McpCircuitBreakerRegistry extends` 同一基类即可复用（注入 MCP `CircuitBreakerProperties`，key=`ServerId`）。原 `ModelCircuitOpenException` 删除。全量 1110 测试绿。

### 11.3 重试策略：复用 `RetryPolicy`，retryable ≠ 可熔断

同 server 的瞬态故障在 `recordFailure` **之前**先做有限重试，复用 `RetryPolicy.executeWithBackoff`（指数退避：`maxAttempts` 默认 3 / `baseDelayMs` 500 / `multiplier` 2.0 / `maxDelayMs` 5000，由 `RetryConfig`）。**retryable 判定复用 `RetryPolicy.isRetryable`**（与既有 LLM 路径同一套规则）：

- **可重试**：`MCP_TOOL_TIMEOUT` / `MCP_RATE_LIMITED` / `MCP_SERVER_UNREACHABLE`（IO/超时类瞬态）。
- **不可重试**：`McpCircuitOpenException`（已熔断，重试无意义）、`UnsupportedOperationException`、A/B 类（用户/配置错误）、工具不存在。

重试耗尽仍失败 → 包成 `RemoteException(MCP_SERVER_UNREACHABLE, ...)` → 经 `FallbackEligibility.isEligible` 判定（C 类）→ `recordFailure` 计入熔断（§11.2）。**两层正交**（复用 `RetryPolicy` Javadoc 既有约定，非新发明）：`RetryPolicy.isRetryable` = 同 server 调用级重试判定；`FallbackEligibility.isEligible` = 是否计入熔断（C 类才计）。二者各管一段，不可混用。

> **不引 spring-retry**：`RetryPolicy.executeWithBackoff` 已提供指数退避同步重试，MCP 同步栈直接复用；服务级退避由熔断器 OPEN cool-down 承担（§11.2）。pom 无需新增依赖（已核实）。

### 11.4 启动期 fail-soft（`initialized=false` 决议，保留）

- **启动期**：starter **不** eager `initialize()`（§10 配 `initialized: false`），故单个 server 不可达**不在 bean 创建期抛穿、不阻塞启动**（1.1.6 字节码 `mcpSyncClients()` `:161 isInitialized` → `:169 initialize` 核实）。握手由 `McpServerRegistry` per-client 调 `initialize()`（try/catch）：不可达 client → 建 `McpServer` 但其熔断器直接置 `OPEN`（`health=down`）、跳过，**不影响其他 server 或整个应用**。配置缺失（无 policy / 同名 server）仍启动失败（B 类）。
- **调用期**：`McpSyncClient` 三能力调用经 `circuitBreaker.execute(retryPolicy.executeWithBackoff(...))`——OPEN 快速失败抛 `McpCircuitOpenException`、CLOSED/HALF_OPEN 经重试 + 计数；门面 try/catch 把 `McpCircuitOpenException` / `RemoteException` 降级为空工具集 / `McpToolResult.isError` / `health` 翻转，**不击穿 LLM 主流程**。
- **运行期 server 消失**：LLM 可能前一轮已见工具定义、中途调用失败 → 该 server 熔断器翻 `OPEN`，结构化错误（`[TOOL_ERROR]` / `McpCircuitOpenException`）回流 LLM 并标记可重试/换路径（区别于启动期"工具不暴露"）。
- **health 出口**：`mcp/health` 薄 wrapper 聚合所有 server 的熔断器 `stateOf(ServerId)` → `McpServerHealth`，供 actuator `/health`（项目已有 `spring-boot-starter-actuator`）消费；主动心跳轮询仍不做，**不**依赖 `McpToolsChangedEvent`（工具变更非连接 liveness）。
- **registry init 代价**：`initialized=false` 后握手挪到 registry 建期，启动付每个 server 握手延迟（受 `request-timeout` 上界约束）。多 server 启动慢后续可并行 init 或改 first-access lazy；Phase 1 串行 try/catch 即可（隔离目标已达成）。

---

## 12. 测试策略

> 本轮（模块孤立）覆盖 1–4、7–11；5/6/12 标注延后（接对外后做）。

1. 领域内核：`McpServer` 三能力 + `McpServerRegistry` per-server 聚合 + 默认拒绝。
2. adapter：`McpTools` → `ToolCallback[]`，且 `ToolCallback` 执行委托回内核 authz（不可绕过）。
3. allowlist：`AllowlistMcpToolFilter`（`McpToolFilter`）显式允许制 + 前缀键反算（C1）。
4. **inputSchema 透传（B1）**：adapter 产的 `ToolCallback.getToolDefinition().inputSchema()` == MCP 原始 schema（非 `{"type":"string"}` 退化）。
5. _（延后）_ 追加合并：本地 + MCP 子集进同一 per-request options（接 `AgentToolCallbackFactory` 后）。
6. _（延后）_ guardrail 拦截高风险（Phase 2）。
7. 降级：超时/鉴权失败/server 不可达/运行期消失——经三态熔断器 OPEN 快速失败 + 门面 try/catch fail-soft 降级（§11.2/§11.4）。
8. **`isError` 不抹平（C5）**：`CallToolResult.isError=true` → `McpToolResult.isError` → adapter `render()` 前缀 `[TOOL_ERROR]`。
9. **安全用例**：allowlist 绕过、tool 输出 prompt injection、SSRF 内网地址、高风险误暴露、前缀冲突。
10. **MCP 不漏全局链（D1，弱保护——诚实标注）**：断言全局 `ToolCallingManager` 的 resolver 不含 MCP 前缀工具。**注意此测试按构造恒真**（全局 resolver 本就只收 `ToolRegistry`），它**抓不到真实回归**（有人删掉项目自定义 `ToolCallingManager` 覆盖、改回收集 `ToolCallbackProvider` 的默认 resolver——那是未来配置变更，单测无法拦截）。价值在于**文档化不变量**，不是护栏；真正的防护靠 ArchUnit + code review 守住"`ToolAutoConfiguration` 的 `StaticToolCallbackResolver` 不收集 provider"。
11. **依赖纪律（D4，ArchUnit）**：①`core` 不依赖 `tool..`/`mcp..`(spring-ai)/`io.modelcontextprotocol..`/`agent..`/`chat..`/`runtime..`/`adapter..`/`config..`；②`adapter` 是唯一 `tool..` 导入方；③仅 `runtime`+`config` 可 import `org.springframework.ai.mcp..`/`io.modelcontextprotocol..`（`policy`/`health` 不得漏）；④消费者只碰 `core`。
12. **三态熔断器（§11.2）**：per-`ServerId` 状态机转换全覆盖——CLOSED 连续 C 类失败 ≥ `failureThreshold` → OPEN；OPEN 经 `openDurationMs` cool-down → HALF_OPEN；HALF_OPEN 探测成功 → CLOSED（reset）、探测失败 → 回 OPEN。断言 OPEN 态 `execute` 快速失败抛 `McpCircuitOpenException`（不打远端）、`McpServerHealth` 与熔断态 1:1。复用项目 `CircuitBreakerState` 语义，mock `McpSyncClient` 制造连续超时驱动转换。
13. **异常分级 + retryable 正交（§11.1/§11.3）**：① authz 拒绝/工具不存在 → `ClientException`（A），不重试不计熔断；② 配置错误 → `ServiceException`（B），同；③ 超时/不可达 → `RemoteException`（C），可重试、重试耗尽计熔断；④ `McpToolResult.isError=true` **不计熔断**（工具业务错误 ≠ server 故障，防误记健康恶化）；⑤ `McpCircuitOpenException` 不重试。
14. _（延后）_ 刷新：`McpToolsChangedEvent` → provider `invalidateCache`（主动拉取策略下为 bonus，不验闭环）。

测试经 `runtime`/`config` 内部注入 mock（`McpSyncClient` / provider 替身），mock 的 starter 类型不跨出 `runtime`+`config`；`core`/`policy`/`adapter` 测试只用 `core` 领域类型 + mock `McpTools` 等接口。

> **Tier 2 真协议验证目标（无需在本地另起 MCP server）**——用真实 MCP endpoint，全量 `@SpringBootTest` + `spring.ai.mcp.client.streamable-http.connections.<name>.url=<endpoint>` + `initialized=false`，注入 `McpServerRegistry`/provider/filter 断言真协议全链路（handshake/listTools/callTool/autoconfig 拾取/provider 缓存/A1 前缀过滤/多 server）。两个现成目标：
> - **★ 本地 GitNexus（推荐）**：`gitnexus mcp --http -p 3000` 暴露 Streamable-HTTP（`POST /mcp` + legacy SSE），real tools（query/impact/context…索引本项目），`127.0.0.1`、免鉴权。注意 GitNexus **默认是 STDIO**，必须加 `--http` 才是 HTTP（Phase 1 client 不覆盖 STDIO）。
> - **真实 remote MCP**：如 `https://open.bigmodel.cn/api/mcp/{web_reader|web_search_prime|zread}/mcp`（streamable-HTTP）；可能需 bearer 鉴权（`McpSyncClientCustomizer` 配）。
> - ① 不可达验证：指向死端口（`localhost:9` 等），无需 server。
> - （in-process 进程内 servlet server 是 hermetic 兜底方案，非首选。）

---

## 13. 分阶段落地

### Phase 1：领域内核 + 接入 + 强制 authz（主线）

> **本轮范围（2026-06-28）：模块孤立实现**。只建 `mcp/{core,runtime,policy,adapter,config,health}` + 测试，**不接对外**——不动 `AgentToolCallbackFactory`/`AgentModeStrategy`/`ToolAutoConfiguration` 等任何注入点；adapter 实现并自测但不注入工厂。模块编译、bean 装配（无 connections 时优雅空载，Step 0 ③ 已验）、单测/ArchUnit 全绿即本轮完成。
>
> **生产接线 = 最后做**：`AgentToolCallbackFactory`（出口①）/ `AgentModeStrategy` 调 MCP、`GuardrailEnforcingToolCallAdvisor` 接 MCP 策略、真模型 tool_calls 端到端（AC1）——全部留到**最后**的接对外切片。
>
> **测试可全量 `@SpringBootTest`**：加载 `SmartRagApplication` 没问题，**无需 mini-app**。MCP 模块的测试（Tier 1 mock 单测 + Tier 2 真协议集成测试）都可用全量 context；只要不改外部模块的产出代码即可。

- `spring-ai-starter-mcp-client` 已引入（BOM 管 1.1.6）；ArchUnit 1.3.0 test scope 已就绪。
- **Step 0 spike（仅运行时行为；①②⑥ 为开工 Gate，③已验，⑤本轮可补）**：
  - ① 启动期 server 不可达时 provider 产出（空/抛/阻塞）→ **fail-soft 形态已决议**（§11.4：`initialized=false` + registry per-client init + 三态熔断器 OPEN 快速失败 + 门面 try/catch，**弃方案 B 自定义 client bean**）；① 仅剩运行时行为核实（SSE/streamable-HTTP 的 `initialize()` 同步抛，高置信预测）。
  - ② 握手失败/运行期失联降级语义。
  - ③ `enabled` 默认值 + 无 connections 启动行为 —— **已坐实（2026-06-28 手测）**：引依赖后无 server 正常启动。
  - **不验刷新闭环**（主动拉取策略，不依赖可选 `list_changed`）。
  - ⑤ **MCP 不漏全局链（D1，弱保护）**：核实 starter 不把 `SyncMcpToolCallbackProvider` 的 callback 自动塞进项目全局 `ToolCallingManager`（后者用 `StaticToolCallbackResolver(ToolRegistry only)`，理论不漏；测试仅文档化不变量，§12 项 10）。
  - ⑥ **`initialize()` 是否被 autoconfig 调用（D4）——已答**：1.1.6 字节码核实 autoconfig 在 `mcpSyncClients()` 里 `if (isInitialized()) client.initialize()`，`initialized` **默认 true** → 默认 eager init、不可达会阻塞启动。**决议：配置 `initialized=false`**（§10），registry 自己 per-client init + try/catch 实现隔离（§11/§9.1）。无需再 spike ⑥ 本身；剩 ① 只需确认 SSE/streamable-HTTP 的 `initialize()` 同步抛（高置信预测）。
- **已 javap/源码坐实、无需 spike**：A1 server 名路径 `getCurrentInitializationResult().serverInfo().name()`（`McpSyncClient`/`InitializeResult`/`Implementation` 均 `javap` 核实）；A2 autoconfig 自动建 `SyncMcpToolCallbackProvider` bean；A3 autoconfig 经 `ObjectProvider` **自动拾取** `McpToolFilter`/`McpToolNamePrefixGenerator` bean（`Provider.Builder` 有对应 setter + 字节码 `getIfUnique`，写 bean 即注入）；A5 配置键 `enabled/type/request-timeout`；A6 `new ReadResourceRequest(uri.toString())` 直接适配；**A7 `FunctionToolCallback`：`.inputSchema(String)` setter 存在 + `inputType` 必须是 `Map`（源码 `:103` `JsonParser.fromJson`→`readValue`；`inputType(String)` 遇 JSON object 抛 `MismatchedInputException`，已证伪；`build()` `:226` 三元确认 `inputSchema` 覆盖生成 schema）——✅ 已 `FunctionToolCallbackB1Test` 单测坐实（6 case 全绿）**；A8 `new CallToolRequest(name, Map)` 适配 `McpArgs`；A9 默认 `DefaultMcpToolNamePrefixGenerator` 不加 server 前缀（字节码核实，自定义 bean 必选）。
- 本轮交付：`mcp/core`（接口 + 领域模型）、`mcp/runtime`（实现，委托 `McpSyncClient` + provider）、`mcp/policy`（`McpAuthorizer` + `McpToolPolicy`，纯领域）、`mcp/adapter`（`McpToolCallbackAdapter`，带 `inputSchema`）、`mcp/config`（`AllowlistMcpToolFilter` + `McpToolNamePrefixGenerator` + `McpSyncClientCustomizer` + `@ConfigurationProperties`）、`mcp/health`（**三态熔断器 `McpCircuitBreakerRegistry` per-`ServerId`（复用 `CircuitBreakerState` 状态机）+ `RetryPolicy` 重试 + 门面 fail-soft + health 投影**，§11）、`RemoteErrorCode` 302xxx 段 + `McpCircuitOpenException`（§11.1）、ArchUnit 依赖纪律（§4.3）。
- **延后（Phase 1 后续切片）**：`AgentToolCallbackFactory` 追加 MCP 子集、`GuardrailEnforcingToolCallAdvisor` 接 MCP 策略、真实 server 端到端（AC1）。

### Phase 2：多 server + 安全闭环

- 多 server 命名空间验证、`McpToolsChangedEvent` 刷新验证。
- `GuardrailEnforcingToolCallAdvisor` 接入 MCP 策略；安全测试补齐。

### Phase 3：路径 C + per-user

- resources/prompts 经 `McpServer` 门面服务业务（`readResource` URI→`ReadResourceRequest` 适配）。
- 评估 per-user `McpServer`（BYOK 联动）、stdio 本地 server、roots/sampling。

---

## 14. 设计决策摘要

| 决策 | 结论 |
|------|------|
| 拆多模块 | 不拆（内聚靠包依赖纪律保证） |
| 模块形态 | **高度内聚领域**：`McpServer` 门面为内核 |
| 包分层 | `core`(接口+模型,零 starter 依赖) / `runtime`(实现,持 client+provider) / `adapter`(→ToolCallback) / `policy`(authz+规则,纯领域) / `config`(装配+filter+customizer) / `health`(fail-soft) |
| 内核职责边界 | **只做 starter 不做的**：authz + 三能力统一门面 + 类型封装；发现/前缀/过滤/刷新复用 provider |
| 协议栈 | 复用官方 SDK + starter，不自研 |
| 对外出口 | 仅两个：`adapter`（→LLM 工具链）/ `core`（→路径 C 业务） |
| starter 类型可见性 | **不跨出 `runtime`+`config`**；`core`/`policy` 零 starter 依赖（ArchUnit 强制） |
| adapter schema | **`.inputSchema(MCP 真实 schema)` + `inputType(Map<String,Object>)`**；`inputType(String)` 执行时 `readValue` 抛异常（已证伪，B1） |
| per-server 拼合（A1） | 调用面(call/resources/prompts)绑本 server 的 `McpSyncClient`；发现面(tools)委托聚合 provider 按前缀过滤；缓存/刷新 provider 级共享 |
| 刷新 | 复用 provider 自带 `ApplicationListener<McpToolsChangedEvent>`，不自研；不依赖可选 `list_changed` |
| allowlist | 复用 starter `McpToolFilter`（BiPredicate），放 **`config`**（非 policy，B3）；键=前缀全名，filter 经 prefixGen 反算（C1） |
| 动态性 | per-request 追加进 Agent 链，不动全局静态链（本轮不接，延后） |
| 工具前缀 | 复用 `McpToolNamePrefixGenerator`（自定义 `<server>_`，签名 `(McpConnectionInfo, Tool)`；`_` 非 `.`，见 §9 E6） |
| tool 可见性 | tool 级，三层过滤（静态 allowlist / 内核 authz+intent / guardrail）；默认拒绝；字段分属各层（C2） |
| 强制 authz | Phase 1：静态 allowlist + 内核硬 authz（`visibleTo` 双过滤 + `call` 兜底）；Phase 2：+ Agent guardrail 语义层 |
| 认证/授权 | 出站认证（`McpSyncClientCustomizer`）与调用方授权（`McpAuthorizer`）分离 |
| 异常分级 | MCP 故障映射 A/B/C 三级（`ClientException`/`ServiceException`/`RemoteException`，复用项目体系）；C 类可重试可计熔断，A/B 类不重试不计；`McpToolResult.isError` 非异常不计熔断（§11.1） |
| 三态熔断器 | 复用 `CircuitBreakerState`（CLOSED/OPEN/HALF_OPEN）per-`ServerId`；`McpServerHealth` = 熔断器只读投影（alive/degraded/down）；OPEN cool-down 即 server 级退避，无需 spring-retry（§11.2） |
| 重试 | 复用 `RetryPolicy.executeWithBackoff`（调用级瞬态，指数退避）；`isRetryable`（重试）与 `FallbackEligibility`（计熔断）正交（§11.3） |
| 降级 | 默认 `fail-soft`：`initialized=false` + registry per-client init try/catch（启动期隔离）+ 三态熔断器 OPEN 快速失败 + 门面 try/catch（运行期）（§11.4） |
| 本轮范围 | 仅 `mcp/*` 模块孤立实现 + 测试；不接对外注入点 |

---

## 15. 下一步

1. **Step 0 Gate（①②⑥）**：坐实启动不可达产出（fail-soft 形态**已决议**：§11.4 `initialized=false` + registry init + 三态熔断器，弃方案 B）、握手失败降级、`initialize()` 是否被 autoconfig 调用（→定 registry 是否手动 init）。③已验。（`_` 前缀由 Spring AI 框架约定直接支撑，无需 spike；provider 侧工具名规则属 BYOK 部署关注点，不在本文档范围。）
2. **本轮：模块孤立实现** `mcp/{core,runtime,policy,adapter,config,health}` + 单测 + ArchUnit；不接对外注入点。bean 在无 connections 时空载，`./mvnw -q -Dtest='Mcp*Test,Arch*Test' test` 全绿即完成。
3. **后续切片**：`AgentToolCallbackFactory` 追加 MCP 子集（出口①）、`GuardrailEnforcingToolCallAdvisor` 接 MCP 策略、接真实 server 端到端（AC1）。
