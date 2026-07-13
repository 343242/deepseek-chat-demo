# Implement：MCP client 出口① 接线

> 设计依据：[design.md](./design.md)；PRD/AC：[prd.md](./prd.md)。按序执行，每步带验证；改动前跑 `impact`，提交前跑 `detect_changes`。

## 前置（开工 Gate）

- [ ] **P0** 确认在正确分支（`agentic-rag-dev`）；基线干净（除既有未跟踪文件）。
- [ ] **P1** GitNexus `impact({target: "AgentToolCallbackFactory", direction: "upstream"})` + `impact({target: "McpToolCallbackAdapter", direction: "upstream"})`，记录 blast radius（直接调用方 = `AgentModeStrategy:158`；风险应 LOW——签名不变/仅构造器加参）。HIGH/CRITICAL 必须先告知用户。

## Step 1 — 扩展 `McpToolCallbackAdapter`（聚合方法）

- [ ] 1.1 加构造器注入 `McpServerRegistry registry`（字段 + 构造器；Phase 1 此类无状态，现变有状态）。
- [ ] 1.2 新增 `toCallbacksForAllServers(McpIntent intent, Subject subj) → ToolCallback[]`：`registry.list()` 空 → `new ToolCallback[0]`；否则遍历，per server `toCallbacks(server.tools(), intent, subj)`，`part.length>0` 时 `Collections.addAll(all, part)`，最后 `all.toArray(ToolCallback[]::new)`。
- [ ] 1.3 Javadoc：注明 fail-soft 依赖 `visibleTo`（无需 try/catch）、空载语义、出口① 多 server 拼合。
- [ ] 1.4 **不动**既有 `toCallbacks` / `render`（B1 单点）。
- [ ] **验证**：`./mvnw -q -Dtest='McpToolCallbackAdapterTest' test`（既有应仍绿；新方法暂无测试）。

## Step 2 — 放宽 ArchUnit 6.4

- [ ] 2.1 `McpDependencyRulesTest.consumers_only_dependOn_core`：依赖包列表去掉 `"..mcp.adapter.."`（保留 runtime/config/health/policy）。
- [ ] 2.2 更新 `@because` 注释（出口① 接缝；adapter 公共面不泄露 starter 类型；6.3 独立守；等同 D-8）。
- [ ] **验证**：此时规则暂不被违反（工厂还未依赖 adapter），全绿。

## Step 3 — 接线 `AgentToolCallbackFactory`

- [ ] 3.1 构造器末位加 `McpToolCallbackAdapter mcpAdapter` 参数 + 字段赋值。
- [ ] 3.2 `createToolCallbacks`：`local = switch(...)`（既有不动）；`mcp = mcpAdapter.toCallbacksForAllServers(toMcpIntent(intent), new Subject(workspace.getUserId(), workspace.getTeamId()))`；`mcp.length==0` → 返回 `local`（默认路径）；否则 `Arrays.copyOf` + `System.arraycopy` 拼接。
- [ ] 3.3 新增 `static McpIntent toMcpIntent(AgentIntent)`（package-private，4 case switch）+ Javadoc（类型桥接，core 禁 import agent.intent）。
- [ ] 3.4 日志：mcp=0 用原 debug；mcp>0 带 `(local={}, mcp={})`。
- [ ] 3.5 import：`com.smart.rag.mcp.adapter.McpToolCallbackAdapter`、`com.smart.rag.mcp.core.{McpIntent,Subject}`。
- [ ] **验证**：`./mvnw -q compile`（编译过；ArchUnit 在 test 阶段验）。

## Step 4 — adapter 聚合单测（扩 `McpToolCallbackAdapterTest`）

- [ ] 4.1 `toCallbacksForAllServers`：2 个 mock server（各 `tools().visibleTo` 返回 1-2 个 `McpTool`）→ 拼接数量/名称正确。
- [ ] 4.2 registry `list()` 空 → `new ToolCallback[0]`。
- [ ] 4.3 某 server `visibleTo` 返回空集（mock down）→ 跳过，另一 server 工具仍包含。
- [ ] 4.4 `subj` 未认证（`userId<=0`）→ visibleTo 空集（mock 体现）→ 空数组。
- [ ] **验证**：`./mvnw -q -Dtest='McpToolCallbackAdapterTest' test` 全绿。

## Step 5 — 工厂接线单测（新建 `AgentToolCallbackFactoryMcpWiringTest`）

- [ ] 5.1 helper 构造工厂：9 工具 + `ToolRegistry` 用 mock（general callbacks 空）+ mock `McpToolCallbackAdapter`。
- [ ] 5.2 AC1：`createToolCallbacks(RETRIEVAL, ws)` → local（5 retrieval）++ mock adapter 返回 2 → 共 7。
- [ ] 5.3 AC2：`ArgumentCaptor<McpIntent>` 验证 `toCallbacksForAllServers` 收到 `McpIntent.RETRIEVAL`（含 4 intent 各一 case）。
- [ ] 5.4 AC3：`ArgumentCaptor<Subject>` 验证 Subject = `(ws.getUserId(), ws.getTeamId())`。
- [ ] 5.5 AC6：mock adapter 返回 `new ToolCallback[0]` → 结果 == local（数量一致）。
- [ ] 5.6 DIRECT_ANSWER：local=0，adapter 仍以 `McpIntent.DIRECT_ANSWER` 调用。
- [ ] 5.7 `toMcpIntent` 4 case 直测。
- [ ] **验证**：`./mvnw -q -Dtest='AgentToolCallbackFactoryMcpWiringTest' test` 全绿。

## Step 6 — ArchUnit 全绿 + 全量回归

- [ ] 6.1 `./mvnw -q -Dtest='McpDependencyRulesTest' test` —— 放宽后 6.4 + 6.1/6.2/6.3/policy/llm 全绿（验证 starter 类型不泄露）。
- [ ] 6.2 `./mvnw -q -Dtest='Mcp*Test,Arch*Test,AgentToolCallbackFactory*Test' test` —— 接线相关全绿（AC12）。
- [ ] 6.3 `./mvnw -q test` —— **全量回归**（确认工厂构造器变更未破坏既有 agent/chat 测试，AC11/AC12）。

## Step 7 — Tier 2 真协议（gated，AC10；可选执行）

- [ ] 7.1 新建 `McpWiringE2ETest`（`@SpringBootTest` + `@EnabledIfEnvironmentVariable(GITNEXUS_MCP_E2E, "true")`）。
- [ ] 7.2 `@TestPropertySource` / `@DynamicPropertySource` 配 `spring.ai.mcp.client.streamable-http.connections.gitnexus.url=http://127.0.0.1:3000/mcp` + `mcp.policy.tools.<gitnexus_tool>:{intent:...}`。
- [ ] 7.3 注入 `AgentToolCallbackFactory` + 构造 `ToolWorkspace`，断言 `createToolCallbacks` 含 `gitnexus_*` 前缀工具。
- [ ] 7.4 **manual gate**：本地 `gitnexus mcp --http -p 3000`，`GITNEXUS_MCP_E2E=true ./mvnw -q -Dtest='McpWiringE2ETest' test`。不阻断 CI（gated）。

## Step 8 — 提交前自检 + GitNexus detect_changes

- [ ] 8.1 `mcp__gitnexus__detect_changes({scope: "compare", base_ref: "main"})` —— 确认改动仅触及预期符号（adapter/factory/ArchTest + 新测试），无意外扩散。
- [ ] 8.2 人工核对：`AgentModeStrategy`/`ToolAutoConfiguration`/MCP core-runtime-policy-config-health 生产代码**零改**（AC11）。
- [ ] 8.3 改动文件清单：`McpToolCallbackAdapter.java`、`AgentToolCallbackFactory.java`、`McpDependencyRulesTest.java` + 新测。

## Step 9 — Commit & Push

- [ ] 9.1 `git add` 改动文件 + task 三件套（prd/design/implement）。
- [ ] 9.2 commit message：`feat(mcp): 出口① 接线 — AgentToolCallbackFactory per-request 追加 MCP 工具 + ArchUnit 6.4 放宽`（带简要 body：聚合在 adapter、映射在工厂、默认零变更）。
- [ ] 9.3 `git push`。
- [ ] 9.4 更新 memory `[[mcp-client-phase1-progress]]`（Phase 2 出口① 接线完成）。

## 验证命令速查

```bash
# 单点
./mvnw -q -Dtest='McpToolCallbackAdapterTest' test
./mvnw -q -Dtest='AgentToolCallbackFactoryMcpWiringTest' test
./mvnw -q -Dtest='McpDependencyRulesTest' test
# 接线相关合集（AC12）
./mvnw -q -Dtest='Mcp*Test,Arch*Test,AgentToolCallbackFactory*Test' test
# 全量回归
./mvnw -q test
# Tier 2（manual）
GITNEXUS_MCP_E2E=true ./mvnw -q -Dtest='McpWiringE2ETest' test
```

## 审查门 / 回滚点

- **Gate A**（Step 3 后）：编译过 + 工厂默认路径（mcp=0）行为不变 → 可随时停在此步，已是一个安全中间态（接线就绪但无 MCP server 时零影响）。
- **Gate B**（Step 6 后）：全量绿 → 接线完成、可提交。
- **回滚**：`git revert` 单 commit；或运行期 `MCP_ENABLED=false`。
