# Implement — RAG 检索引用追溯（agent + chat 双路径）

> 执行顺序自上而下。每个 Step 内的"前置"必须先做。验证命令在文末。
> **规则**：改任何 symbol 前先跑 `impact`（索引重建期间以 grep 兜底）；提交前跑 `detect_changes`。
> 研究/确认项（§design 6）按依赖挂在各自 Phase 前。
> **v2 要点**：`<<REF>>` 走 system 注入（不碰 user message）；流式补 Redis 记忆 + DB 落库；agent 基座覆盖 4 意图；DTO/Reference 先于 agent/chat 落地。

## Phase 0 — 清理

- [ ] **0.1** 删除 `docs/design/page-index-search-tool.md`（放弃 PageIndex）。
  - 前置：`grep -rn page-index-search-tool` 确认无引用。
- [ ] **0.2** 删除死代码 `AgentChatResponse.java`（design §5 已核实无引用；agent 经 `ChatResponse` 返回）。
  - 前置：`grep -rn AgentChatResponse src` 确认仅剩自身定义（测试若引用则一并改/删）。

## Phase 1 — 共享前置确认（✅ 全部核查闭合）

- [x] **1.1** ✅ `fetchDocHighlights`（`VectorStoreMapper:187`）→ `selectHighlightRows`，XML `VectorStoreMapper.xml:96` 已 `#{id}::uuid` → 无 42883。P2 改名不涉 SQL。
- [x] **1.2** ✅→**动作**：`PromptLoaderService` 无 `getDefaultPrompt()`；`PromptTemplate.toSystemPrompt()=rawXml`（`:26`），`state.defaultTemplate()` 是 default.xml 模板。**P7.1 动作**：加 `getDefaultPrompt()` 返回 `state.defaultTemplate().toSystemPrompt()`（无 default 返回 null）。
- [x] **1.3** ✅ Spring AI 1.1.5 源码（=1.1.6 语义）`MessageChatMemoryAdvisor.adviseStream:126`→`after:137` 流式完成时 `chatMemory.add` 写 Redis。
- [x] **1.4** ✅ `AgentModeStrategy.executeStream:281` 已存在（throw UNSUPPORTED）。接口 `Flux<String>`→`StreamResult` 涉 3 处（接口 default `:49` / Agent 覆盖仍 throw / Abstract 覆盖重写），低风险。
- [x] **1.5** ✅ 架构走 `GenericChatClient`+`ChatModelAdapter`（OpenAI 兼容），支持历史后 system；yml `candidates.list` 空（动态注册），门 G5 运行时确认实际 candidate。
- [x] **1.6** ✅ 源码 `before:105` 只存 `userMessage`、`after:120` 存 assistant，SystemMessage 不入库；`RagContextAdvisor` 中段 SystemMessage 不进 Redis。门 G5 复核。

> 全部回填 `design.md` §6（item 2/5/8/9/10/11 已划 ✅）。运行时复核挂在门 G5/G7（零成本：连问两轮查 Redis + 看 `prompt_cache_hit_tokens`）。

## Phase 2 — 命名 + 数据模型（破坏性改动，整批做，双路径共享）

- [ ] **2.1** `impact({target:"RetrievedDocument"})` + `impact({target:"ToolWorkspace"})` + `impact({target:"DocDetailTool"})` —— 确认 blast radius。
- [ ] **2.2**（前置 1.1）若 `fetchDocHighlights` 缺 `::uuid`，先修（否则 DocDetailTool 一调即 42883）。
- [ ] **2.3** 改 `RetrievedDocument`（design §2.1）：`docId→chunkId`，新增 `documentId/fileName/page/refNumber`；新增 `withRefNumber(int)` + 静态工厂 `from(Document)`（统一提取 chunkId/documentId/fileName/page_number，fileName 缺失降级 documentId/"未知"）。
- [ ] **2.4** 改 6 处 `new RetrievedDocument(...)` 构造点（design §2.1）：`VectorSearchTool:77` / `HybridSearchTool:78` / `Bm25SearchTool:88` / `RerankTool:96` / `ParentDocLookupTool:116` / `ToolWorkspace:304`（truncateIfNeeded 重建）—— 用 `from(Document)` 或新签名。
- [ ] **2.5** 改 `ToolWorkspace`（design §2.2）：`seenDocIds`（`:56`）改用 `chunkId()`；新增 `refCounter`；`addRetrievedDocs:109` / `addRetrievedDocsDeduplicated:151` 分配 refNumber（不烧号）。
- [ ] **2.6** 改 `replaceRetrievedDocs:126`：clear 前快照 `chunkId→refNumber`、re-add 复用旧号、新 chunkId 续号（rerank + parentDocLookup 共用）。
- [ ] **2.7** 改 `truncateIfNeeded:300`（重建 `:304`）：保留 refNumber（`withRefNumber`）。
- [ ] **2.8** 改 `DocDetailTool.execute(String docIds,…)`（`:47`，`@param` `:42`）→ **彻底改名 `chunkIds`**（参数名 + tool description）。
- [ ] **2.9** 改 `RerankTool:81` `new Document(rd.docId(),…)` → `rd.chunkId()`。
- [ ] **2.10** 改 `LlmResponse.TokenUsage`（design §2.12）：新增 `@Nullable Integer cacheHitTokens` 第 4 字段；改 `GenericChatClient.parseTokenUsage()`（`:251-257`）补充读取 `prompt_cache_hit_tokens`（DeepSeek）和 `prompt_tokens_details.cached_tokens`（百炼/Qwen）。
- [ ] **门 G1**：`./mvnw -q compile` 通过；`grep -rnE '\.docId\(\)|new RetrievedDocument\(' src/main` 无残留（白名单：`RetrievedDocument.java` 自身定义）。

## Phase 3 — 文件名落库（全部写点）

- [ ] **3.1** `impact({target:"VectorStoreMapper"})` + `impact({target:"StandardStrategy"})` + `impact({target:"FastTrackStrategy"})`。
- [ ] **3.2** 4 处写点补 `fileName`（design §2.5）：
  - `StandardStrategy.java:124-130`（put `:125/:126/:128`）：metadata put `fileName`（来源 `RagDocument.fileName`，即 `RagDocument:16`）。
  - `FastTrackStrategy.java:180-185`（put `:181/:182/:184`）：同上。
  - `VectorStoreMapper.insertFastTrackRow`（`:117-127`）：方法签名加 `String fileName`，写入 metadata map；透传自 `FastTrackStrategy.writeBm25Row:158`（调用点 `:120`）。
  - `EtlPipelineServiceImpl.executeWithUserId:63`（put `:83-88`）：补 `fileName`（按在用，`execute():54` 委托）。
- [ ] **3.3** 空值降级：fileName 缺失写 documentId。
- [ ] **门 G2**：本地上传测试文档，`select metadata->>'fileName', metadata->>'page_number' from vector_store limit 5;` 确认 4 类写点都落库。

## Phase 4 — `Reference` + DTO（agent/chat 共用，先于两路径）

- [ ] **4.1** `impact({target:"StrategyExecuteResult"})` + `impact({target:"ChatResponse"})`。
- [ ] **4.2** 新增 `Reference(int refNumber, String chunkId, String documentId, String fileName, @Nullable Integer page)`（design §2.4，公共包）。
- [ ] **4.3** `StrategyExecuteResult`（`:12-18`）加 `@Nullable List<Reference> references`；`standard()`（`:21`）+ `agent()`（`:26-29`）两工厂都加 references 参数/重载（standard 默认 null）。
- [ ] **4.4** `ChatResponse`（`:17-22`，`@JsonInclude(NON_NULL)` `:16`）加 `@Nullable List<Reference> references`；3-arg（`:25`）/4-arg（`:29`）构造器 references 默认 null。
- [ ] **4.5** `ChatServiceImpl.processResult:182-204`：`new ChatResponse(...)` 两处（`:199` agent / `:202` 非 agent）都透传 `result.references()`。
- [ ] **门 G3**：`./mvnw -q compile` 通过；确认非 RAG 调用 references 序列化为 null（不出现字段）。

## Phase 5 — Agent 路径：编号 + 序列化 + references

- [ ] **5.1** 改 `ToolResult.toJson()`（`ToolResult.java:80`，当前 docId `:96`，design §2.3）：输出 `refNumber/chunkId/documentId/fileName/page/score/content/source`（LinkedHashMap 保序，500 字符截断 `:100-103` 保留）。
- [ ] **5.2** `AgentModeStrategy.execute`（`:233-258`，design §2.4/§1）：从 `workspace.getRetrievedDocs()` 构造 `List<Reference>`，传进 `StrategyExecuteResult.agent(springResponse, content, agentMetadata, references)`（`:258`）。
  - 注意：`AgentModeStrategy implements ChatModeStrategy`（`:80`，不继承 AbstractModeStrategy）→ references 在此构造，与 chat 路径互不干扰。
- [ ] **门 G4**：本地起服务，跑一轮 agent 检索，确认返回 JSON 含编号 + 出处 + `ChatResponse.references` 映射。

## Phase 6 — Chat 阻塞式：方案 A + 静态/动态拆分（v3 缓存友好）

> 前置 1.5（provider 中段 system）、1.6（memory advisor 忽略 system + advisor order）。

- [ ] **6.1** `RagAdvisorFactory`（design §2.6）：`createIsolatedRetriever:112` / `getPostProcessors:142` 改 public（或新增 public 包装）；**新增 `retrieve(query, userId, teamId): List<Document>`** 复刻 `create():83-104` 内部编排（query-transform → retriever → postProcessor 逐个 process），不套 Advisor 壳。`create()` 保留。
- [ ] **6.2** 新增 `ChatRetrievalService.retrieve(query, userId, teamId)`（薄封装委托 `RagAdvisorFactory.retrieve`）。
- [ ] **6.3** 新增 `ChatReferenceCollector.collect(docs): ChatRefResult(refBlock, references)`（design §2.7，复用 `RetrievedDocument.from`；产 refBlock + references，**不碰 user message**）。
- [ ] **6.4** 新增 `RagContextAdvisor implements BaseAdvisor`（design §2.8b，仿 `ConversationContextAdvisor` 模式）：`before()` 把 `composeDynamic(cagContext, refBlock)`（CAG 段 + `<<REF>>` 块）作为 `SystemMessage` 插在**最后一条 UserMessage 之前**（即历史之后）；`getOrder()` 排在 `MessageChatMemoryAdvisor` load 之后。
- [ ] **6.5** `AbstractModeStrategy` 注入 `ChatRetrievalService` + `ChatReferenceCollector`（构造器加参；Simple `:23` / MultiTurn `:40` 的 `super(...)` 同步）。
- [ ] **6.6** 改 `AbstractModeStrategy.execute`（`:34-47`，final，design §2.9）：`isRagEnabled()` 时 retrieve→collect→得 `refBlock`+`references`；`chain.add(new RagContextAdvisor(refBlock, ctx.cagContext()))`；`createSpec(... chain ...)`（不传 refBlock）；返回 `StrategyExecuteResult.standard(..., references)`。
- [ ] **6.7** 改 `ChatRequestSpecFactory.createSpec`（`:35-58`，design §2.8）：**system = 纯 default.xml**（`resolveSystemPrompt`）；**删除 `:52` 的 `contextPromptInjector.inject`**（CAG 段改由 `RagContextAdvisor` 注入动态尾）；`.user(request.message()):42` **不动**；不新增 refSystemBlock 参。
- [ ] **6.8** 移除 `SimpleModeStrategy.java:35-38`、`MultiTurnModeStrategy.java:57-60` 的 `chain.add(ragAdvisorFactory.create(...))`（其余 advisor 如 `MessageChatMemoryAdvisor:66` 保留）。
- [ ] **门 G5**：本地 SIMPLE/MULTI_TURN 阻塞式 + ragEnabled=true：
  - **消息序**（日志/断点看发给模型的 messages）：`[system: 纯 default.xml] → [历史] → [system: CAG+<<REF>>[n]] → [user: 干净问题]`。
  - 响应含 references；召回数量/排序与改造前一致（复用同组件）。
  - **记忆干净性**（前置 1.6）：连问两轮，查 Redis `chat:memory:{conversationId}` 第二轮历史**不含 `<<REF>>`**（role 级保证验证）。
  - **前缀缓存**（前置 1.5/1.6）：连问两轮，确认静态基座 + 第一轮历史在第二轮是相同前缀（provider cache hit，可看 DeepSeek usage 的 prompt_cache_hit_tokens 增长）。

## Phase 7 — Agent Prompt 基座叠加 + 静态/动态拆分（前置 1.2）

- [ ] **7.1**（前置 1.2）确认 default.xml 基座取数入口（`PromptLoaderService.getDefaultPrompt()` 或等价）。
- [ ] **7.2** 改 `AgentModeStrategy.resolveAgentPrompt`（`:197-210`，design §2.11）：在公共出口 `:209` 对**全部 4 意图**统一 `template = base + "\n\n" + template`（base = default.xml 基座）；**移除 `:209` 的 `contextPromptInjector.inject`**（CAG 并入动态尾，否则静态不稳定）→ `resolveAgentPrompt` 只返回静态（base+意图）。
- [ ] **7.3** 改 `AgentSystemPromptAdvisor.before()`（`:68-114`，design §2.11）：**拆静态/动态两个 SystemMessage**（v5 前缀缓存优化）。静态 = base + 意图（**无 CAG**）放**首位**；动态 = CAG 段 + 中间答案 + 护栏 放**末尾**（history + user 之后、生成点之前）。**动态必须在 tools 与历史之后**（v4 放历史之前是错的：分叉点提前 → tools + 历史每轮全重算）。见 design §2.11 伪代码。
  - 原来的单 SystemMessage 拼接逻辑（`:73-80`）整体替换：静态 SystemMessage 首位 + 原 history/user 不变 + 动态 SystemMessage 末尾。
  - `getOrder()` 不变（仍为 1，在 ToolCallAdvisor 之前）；动态末尾插入靠重建 messages 完成，不依赖 order。
- [ ] **门 G6**：agent 四种意图（DIRECT_ANSWER/RETRIEVAL/DEEP_RETRIEVAL/GENERAL_TOOL）的 system prompt 都带 default.xml 基座（r2 生效），编排指令仍在。消息序为 `[system:静态基座+意图] → [tools] → [tool历史] → [system:动态(CAG+中间答案+护栏)]`（连跑多轮 tool call，确认 `[静态+tools+已有历史]` 跨轮次字节稳定 → DeepSeek `prompt_cache_hit_tokens` 增长）。

## Phase 8 — Chat 流式：R8（Redis 记忆 + DB 落库 + system 注入 + references 帧）

> 前置 1.3（advisor 流式写 Redis）、1.4（接口签名涟漪）。

- [ ] **8.1**（前置 1.3/1.4）确认 advisor 流式持久化 + `AgentModeStrategy` 是否有 `executeStream`。
- [ ] **8.2** 新增 `record StreamResult(Flux<String> content, @Nullable List<Reference> references)`；`ChatModeStrategy.executeStream` 返回类型 `Flux<String>`→`StreamResult`（若 AgentModeStrategy 无 executeStream，接口给默认方法兼容；若有，适配返回 `new StreamResult(flux, null)`）。
- [ ] **8.3** **上提 `onStreamComplete` 到 `AbstractModeStrategy`**（当前只在 `MultiTurnModeStrategy:96-113`）：复用 `publishMessageSave(conversationId, request.message(), content, ...)`（design §0 落库用干净原文）+ `savePartialResponse`（ON_ERROR/CANCEL，`MultiTurn:109`）。
- [ ] **8.4** **重写 `AbstractModeStrategy.executeStream`（`:50-62`，design §2.10）**：`buildAdvisorChain`（含 MessageChatMemoryAdvisor）→ `isRagEnabled()` retrieve→collect（同 §6.3）得 `refBlock`+`references` → `chain.add(new RagContextAdvisor(refBlock, ctx.cagContext()))` → `createSpec(... chain ...).stream().content()`（createSpec 只设静态基座）→ `doOnNext` 收集内容 → `doFinally` → `onStreamComplete` 落库 → 返回 `StreamResult(content, references)`。
  - 走 advisor 链 → `MessageChatMemoryAdvisor` 读 Redis 历史 / 写新轮（§1.3 已验）；`RagContextAdvisor` 注入动态尾（历史后）。
- [ ] **8.5** **删除 `MultiTurnModeStrategy.executeStream` 覆盖（`:72-94`）**：collect+persist+createSpec+RagContextAdvisor 已统一在抽象层。
- [ ] **8.6** 改 `ChatServiceImpl.chatStream`（`:116-138`）：从 `modeStrategy.executeStream(...)` 拿 `StreamResult`，内容流 + references 传给 `SseStreamBridge`。
- [ ] **8.7** 扩展 `SseStreamBridge`（`:14`，design §2.10）：新增 `bridge(StreamResult)` 或 `bridge(Flux content, List<Reference> references)`，在内容流 `complete()`（`:30`）后追加 `event: references\ndata: <json>` 帧（当前 `sendChunk:40-46` 只发 data）。
- [ ] **门 G7**：本地 SIMPLE/MULTI_TURN SSE 流式 + ragEnabled=true：
  - **消息序**：`[system: 纯 default.xml] → [历史] → [system: <<REF>>[n]] → [user: 干净问题]`；末尾收到 references 帧。
  - **Redis 记忆**：连问两轮，第二轮流式模型能看到第一轮历史（advisor load）；查 Redis 新轮已写入（advisor save）且**不含 `<<REF>>`**。
  - **DB 落库**：SIMPLE 流式（之前不落库）与 MULTI_TURN 流式都落库（`chat_message` 有记录）。
  - **前缀缓存**：静态基座 + 第一轮历史在第二轮命中（DeepSeek `prompt_cache_hit_tokens` 增长）。

## Phase 9 — Prompt 基座内容（chat 侧）

- [ ] **9.1** `default.xml`（design §2.11）**新增**检索引用约定段："检索参考信息以 `<<REF>>[n]` 块提供（chat）/ `[n]` 编号（agent），引用时句末加（来源#n：文件名）"。（注：当前文件**无**此约定，是新增不是微调。）
## Phase 10 — 测试

- [ ] **10.1** 编号稳定性单测：多次 add / replace 不重排 / dedup 不烧号（`ToolWorkspace`）。
- [ ] **10.2** `replaceRetrievedDocs` 保全专项：rerank vs parentDocLookup 两场景，旧 refNumber 不变、新 chunkId 续号。
- [ ] **10.3** `RetrievedDocument.from(Document)` 新字段正确提取；空 fileName 降级 documentId/"未知"。
- [ ] **10.4** `ToolResult.toJson()` 字段名/顺序（LinkedHashMap）/500 截断。
- [ ] **10.5** `ChatReferenceCollector`：`<<REF>>[n]` 块格式、references 完整性、空 doc 降级。
- [ ] **10.6** **记忆干净性**：阻塞式 + 流式各一轮，断言写进 Redis ChatMemory 的 user message 不含 `<<REF>>`（`RagContextAdvisor` 用 SystemMessage，role 级保证）。
- [ ] **10.7** **流式记忆**：流式第二轮能读到第一轮历史；流式新轮写入 Redis。
- [ ] **10.7b** **消息序 + 前缀缓存（chat）**：断言发给模型的消息序为 `[system:纯default.xml] → [历史] → [system:动态<<REF>>] → [user:问题]`；连问两轮，静态基座 + 首轮历史字节相同（前缀缓存命中前提）；`RagContextAdvisor` 排在 memory advisor load 之后。
- [ ] **10.7c** **Agent 静态/动态拆分 + tool token（v5）**：断言 agent 多轮 tool call 的消息序为 `[system:静态基座+意图] → [tools] → [tool历史] → [system:动态(CAG+中间答案+护栏)]`；静态基座 + tools + 已有历史跨 ReAct 轮次字节不变（前缀命中），动态在末尾每轮增长；**动态不得在 tools 或历史之前**（否则前缀断裂）。
- [ ] **10.8** Prompt 组合回归：agent 四意图都带 default.xml 基座（未丢编排指令）；chat default.xml r2 生效。
- [ ] **10.9** references 映射完整性：agent + chat 阻塞式每个 refNumber 都有 chunkId/documentId/fileName。
- [ ] **10.10** 回归：`hybridSearch/vectorSearch/bm25Search` 召回数量/排序不变；chat 召回数量/排序与改造前一致（方案 A 复用同组件）。
- [ ] **10.11** 流式 references 帧：SSE 末尾收到完整 references JSON。
- [ ] **10.12** **TokenUsage cache hit 解析**：`GenericChatClient.parseTokenUsage()` 正确读取 DeepSeek `prompt_cache_hit_tokens` / 百炼 `prompt_tokens_details.cached_tokens`（构造 mock JSON 验证）；`TokenUsage` 新字段在序列化/反序列化下正确。
- [ ] **门 G9**：`./mvnw test` 全绿。

## Phase 11 — 收尾

- [ ] **11.1** `detect_changes({scope:"compare", base_ref:"main"})` —— 确认改动范围符合预期。
- [ ] **11.2** 更新 `prd.md` 验收勾选；按 Trellis 走 3.3 spec update + 3.4 commit。

## 验证命令

```bash
./mvnw -q compile
./mvnw test
# 改 symbol 前：impact({target:"<symbol>"})（索引重建期用 grep 兜底）
# 提交前：detect_changes({scope:"compare", base_ref:"main"})
# 命名残留（应无命中）
grep -rnE '\.docId\(\)|new RetrievedDocument\(' src/main
# AgentChatResponse 死代码确认（仅剩自身定义/测试）
grep -rn AgentChatResponse src
# fileName 落库
# psql: select metadata->>'fileName', metadata->>'page_number' from vector_store limit 5;
# 记忆干净性（连问两轮后）
# redis-cli: ZRANGE chat:memory:<conversationId> 0 -1   # user message 不含 <<REF>>
# chat/agent RAG 注入确认（日志/断点看 system prompt 含 <<REF>>[n]，user message 干净）
```

## 回滚点

- G1 后：编译/契约断裂 → revert Phase 2 整批（record 签名变更不可部分回滚）。
- G4/G5 后：agent/chat 阻塞行为异常 → 单独 revert Phase 5 或 6。
- G7 后：流式异常 → revert Phase 8（阻塞式不受影响；回滚需恢复 MultiTurn.executeStream 覆盖 + onStreamComplete 下沉）。
- 全程无 DDL 迁移，回滚 = revert 提交。chat 移除 Advisor + 流式改 createSpec 的回滚需同时恢复 Simple/MultiTurn 的 `add(ragAdvisor)`、AbstractModeStrategy 注入分支、executeStream 原貌。
