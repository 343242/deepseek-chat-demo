# RAG 检索引用追溯：编号+出处+文件名（agent + chat 双路径）

## Goal

让现有 RAG 检索链路（**agent + chat 两条路径，含阻塞式与流式**）的返回结果**可追溯、可验证**：每条命中的检索段都有稳定编号 `[n]` 并绑定（chunkId / documentId / 文件名 / 页码），模型在回答中用 `来源#n：文件名` 引用，前端据此把引用渲染成超链接，用户点击可反查信息来源。

本任务**不做 PageIndex**，纯改造检索返回路径、系统提示与响应 DTO。

## Background

- 现状：检索工具（hybridSearch/vectorSearch/bm25Search/rerank/parentDocLookup）返回的 `RetrievedDocument.docId` 实为 **chunk id**（`vector_store.id`），真正的文档 id 在 `metadata.documentId`；但 `ToolResult.toJson()` 只输出 `docId/score/content/source`，把 metadata 整包丢掉，文件名/页码/documentId 都没返回给 LLM。
- 现状：**chat 路径**用 Spring AI `RetrievalAugmentationAdvisor`（`SimpleModeStrategy.java:35-38` / `MultiTurnModeStrategy.java:57-60` 在 `isRagEnabled()` 时 `chain.add(ragAdvisorFactory.create(...))`），检索段由 Spring AI `DocumentJoiner` 以**默认无编号格式塞进 user message**，模型无法对齐 `[n]`；响应 `ChatResponse` 也无引用映射。
- 现状（记忆）：存在**两套独立存储**——① Redis ChatMemory（`MessageChatMemoryAdvisor` + `RedisChatMemoryRepository`，LLM 上下文窗口记忆，每轮自动进 prompt）；② DB messages（`ChatMessagePublisher` → 总线 → 落库，UI/审计用）。`MessageChatMemoryAdvisor` 只持久化 user+assistant，**不存 system**——这是本任务把检索段注入 system（而非 user）的依据。
- 现状：chat 的 system prompt 来自 classpath XML 模板（优先级 `XML > Redis > PostgreSQL`，`default` 为兜底模板，见 `SystemPromptServiceImpl.getPrompt` → `PromptLoaderServiceImpl`）；`default.xml` 已喂给 chat，但 r2 引用规则因检索段未编号而形同虚设。
- 现状：`default.xml` 的 r2 要求"句末加（来源#编号：文件名）"，但检索段从未编号；且两个 r3 ID 冲突（既有 bug）。
- 现状：agent 路径用 `deepRetrievalPrompt`（配置项），不走 `default.xml`，r2 对 agent 不生效。
- 现状：**流式 chat**（`AbstractModeStrategy.executeStream:50-62` / `MultiTurnModeStrategy.executeStream:72-94`）直接 `chatClient.prompt().user(message).stream().content()`，**完全没走 advisor 链**：既不做 RAG，也**不读/不写 Redis ChatMemory**（模型拿不到历史、新轮不进记忆）；且 Simple 流式**不落库**（仅 MultiTurn 经 `onStreamComplete:102-104` 落库）。本次补齐流式 RAG + Redis 记忆（load+save）+ DB 落库（含 Simple）。

## Requirements

### R1 检索段稳定编号（双路径）
- agent 路径：每个 `RetrievedDocument` 进 `ToolWorkspace` 时获整轮全局唯一稳定编号 `[n]`。
- chat 路径：每个检索 `Document` 在 `ChatReferenceCollector` 收集时获请求内稳定编号 `[n]`。
- 编号必须扛住：多次 tool call（agent：hybrid→bm25→rerank）不重置；rerank 的 `replaceRetrievedDocs` 不重排；dedup 跳过的重复 chunk 不"烧号"。
- 编号统一覆盖所有来源（child chunk、parent doc）。

### R2 返回带完整出处（双路径）
- agent：`ToolResult.toJson()` 每条输出 `[n]`、chunkId、documentId、文件名、页码、score、content（截断）。
- chat：`<<REF>>` 块每条 `<<REF>>[n] fileName(documentId, p.X)\n content…<<END>>`（语义隔离包裹）；**块作为动态 `SystemMessage` 注入对话历史之后、当前问题之前**（不进 user message，见 R9/R10）。
- 文件名方案 B：**入库时把 `fileName` 写进 `vector_store.metadata`**（本地开发，无回填）。
- 页码来自现有 chunk metadata 的 `page_number`。

### R3 可对齐引用
- 模型在回答正文用 `来源#n：文件名` 引用（沿用 r2 格式）。
- 检索段必须以 `[n]+文件名` 列表（agent）/ `<<REF>>` 块（chat）形式喂给 LLM，模型才能知道 n→文件名映射。

### R4 Prompt 统一基座
- `default.xml` 作为**共享基座**（现有 r1–r4 安全/引用/语气/约束；**新增** `<<REF>>`/`来源#n` 引用约定段——当前文件无此约定），chat 和 agent 都遵守。
- chat 已通过 `SystemPromptService` 加载 `default.xml`（兜底模板）→ r2 自动生效。
- agent 最终 system prompt = `default.xml` 基座 + **意图编排模板**叠加（`resolveAgentPrompt` 公共出口，覆盖全部 4 个意图 DIRECT_ANSWER/RETRIEVAL/DEEP_RETRIEVAL/GENERAL_TOOL，不只 DEEP_RETRIEVAL）。
- 不砍掉 agent 的检索编排能力。

### R5 前端可验证映射（双路径）
- agent + chat 响应均含结构化引用映射 `#n → (chunkId, documentId, 文件名, 页码)`，取自各自收集器；两路 DTO 形状一致（统一 `Reference` record + `ChatResponse.references`）。

### R6 命名清理
- `RetrievedDocument.docId` **全量重命名为 `chunkId`**（彻底版，含 LLM-facing 的 `DocDetailTool` 参数 `docIds→chunkIds` + tool description 同步），并梳理所有消费点契约。

### R7 Chat 引用来源（方案 A：拆 Advisor；静态/动态拆分 + 动态尾注入）
- chat 路径不再用 `RetrievalAugmentationAdvisor`，改为 `ChatRetrievalService` 直接跑 **query-transform → 隔离检索 → MMR → Rerank → ParentChild**（复用 `RagAdvisorFactory` 已造的 retriever + postProcessor 组件，只不套 Advisor 壳），返回 `List<Document>`。
- `ChatReferenceCollector` 把 `Document → RetrievedDocument`（提取 metadata）、分配 `[n]`、拼 `<<REF>>` 块、产 references。
- **静态/动态拆分**（见 R10）：`createSpec` 的 system = **纯 default.xml 静态基座**（移除原 CAG 注入）；`<<REF>>` 块 + CAG 段 = 动态尾，由新增的 per-request `RagContextAdvisor` 以 **`SystemMessage` 注入对话历史之后、当前问题之前**。`SimpleModeStrategy`/`MultiTurnModeStrategy` 不再 `add(ragAdvisor)`，其余 advisor（含 `MessageChatMemoryAdvisor`）保留。
- 复用 agent 同款 `RetrievedDocument` record + references DTO，两路一致。

### R8 流式对等 + 记忆 + 落库
- chat 流式（SIMPLE/MULTI_TURN）改为**走 advisor 链**（与阻塞式同源 `createSpec`），补齐三件事：
  - **RAG**：流式也 retrieve → `<<REF>>` 块注入 system；references 通过 SSE 帧下发（内容流末尾 `event: references`，协议见 design §2.10）。
  - **Redis 对话记忆**：`MessageChatMemoryAdvisor` 在流式也 load（读历史）+ save（写新轮）。
  - **DB 落库**：`onStreamComplete` 上提到 `AbstractModeStrategy`，SIMPLE 流式也落库（MULTI_TURN 原已落库）。
- 流式不再"不检索 / 不记 / Simple 不落库"。

### R9 记忆与落库不被 `<<REF>>` 污染（role 级保证）
- Redis ChatMemory（user+assistant）与 DB messages 两套存储写入的内容**都不含 `<<REF>>` 检索原文块**。
- 实现手段：`<<REF>>` 以 `SystemMessage` 角色注入（`MessageChatMemoryAdvisor` Spring AI 1.1.6 只持久化 user+assistant → system 永不入库，与 advisor 顺序无关）；DB 落库继续用 `request.message()`（`ChatServiceImpl:194` / `MultiTurnModeStrategy:102`）。无需任何运行期剥离逻辑。

### R10 KV-cache / 前缀缓存友好
- **静态基座 = 纯 default.xml**，跨所有请求字节相同 → 全局前缀缓存命中（DeepSeek 自动上下文缓存）。
- **对话历史** append-only → 每轮 `静态 + history_so_far` 是上轮前缀 → 逐轮命中。
- **动态尾（CAG 段 + `<<REF>>` 块）必须放在历史之后、当前问题之前**——绝不插在静态基座与历史之间（否则历史每轮重算，缓存收益尽失）。
- 既有 CAG 注入 system prompt 的行为（`createSpec:52` `contextPromptInjector`）需移出静态基座，并入动态尾。

## Constraints

- **不侵入检索语义**：不改 hybrid/vector/bm25 的召回逻辑；chat 方案 A 复用现有 retriever + postProcessor，只换"包裹/编号/注入"层。
- **隔离不变**：沿用现有 userId/teamId 隔离。
- **本地开发**：不考虑存量数据/切片迁移与回填，改完入库代码重新上传即可。
- **删除** `docs/design/page-index-search-tool.md`（放弃 PageIndex）。
- 遵循 GitNexus 规矩：改 symbol 前跑 `impact`，提交前跑 `detect_changes`（索引重建期间以 grep 兜底）。

## Acceptance Criteria

- [ ] `RetrievedDocument` 新增稳定编号字段，`docId → chunkId` 全量重命名（含 DocDetailTool LLM-facing 参数 `docIds→chunkIds` + description 同步），所有消费点（workspace 去重 / RerankTool / DocDetailTool / ParentDocLookupTool / 6 处构造点）契约一致、编译通过。
- [ ] 入库时 `fileName` 写入 `vector_store.metadata` 的**全部写点**（`StandardStrategy` / `FastTrackStrategy.asyncVectorize` / `VectorStoreMapper.insertFastTrackRow`；`EtlPipelineServiceImpl` 视在用情况）；空文件名降级（documentId / "未知"）。
- [ ] agent：`ToolResult.toJson()` 每条输出 `[n]+chunkId+documentId+文件名+页码+score+截断 content`；编号在多次 tool call / rerank replace / dedup 下稳定、不重排、不烧号（单测覆盖）。
- [ ] chat：方案 A 落地——`ChatRetrievalService` 复用现有检索组件；**静态基座（纯 default.xml）+ 动态尾（`<<REF>>[n]` 以 `SystemMessage` 经 `RagContextAdvisor` 注入历史之后）**；user message 保持干净；`SimpleModeStrategy`/`MultiTurnModeStrategy` 不再 `add(ragAdvisor)`；召回行为（数量/排序/MMR/rerank/parent）与改造前一致（回归）。
- [ ] agent system prompt = `default.xml` 基座 + 意图模板叠加，**覆盖全部 4 个意图**（DIRECT_ANSWER/RETRIEVAL/DEEP_RETRIEVAL/GENERAL_TOOL）；r2 对 chat 和 agent 都生效；agent 编排能力未丢失。**Agent prompt 静态/动态拆分**：`AgentSystemPromptAdvisor.before()` 拆成两个 `SystemMessage`（静态基座+意图模板 / 动态中间答案+护栏），静态部分跨 ReAct 轮次字节不变（前缀缓存命中）。
- [ ] agent + chat **阻塞式**响应均含 `#n → (chunkId, documentId, 文件名, 页码)` 结构化映射（`ChatResponse.references` 新字段，`@JsonInclude(NON_NULL)` 不影响非 RAG 调用）。
- [ ] chat **流式** SSE：跑 RAG（`<<REF>>` 注入 system）+ 下发 references 帧；**走 advisor 链**（Redis 记忆 load+save）；**SIMPLE 与 MULTI_TURN 都落库**。
- [ ] **R9 记忆/落库干净**：连问两轮后，Redis ChatMemory 的 user message 与 DB messages 均**不含 `<<REF>>` 块**（`SystemMessage` role 级保证）。
- [ ] **R10 缓存友好**：**chat** 消息序为 `[system:纯default.xml] → [历史] → [system:动态<<REF>>] → [user:问题]`；**agent** 消息序为 `[system:静态基座+意图] → [tools] → [tool历史] → [system:动态(CAG+中间答案+护栏)]`（动态在 tools 与历史**之后**，非之前）；静态基座 + tools 跨请求/轮次字节稳定（DeepSeek `prompt_cache_hit_tokens` 命中）；CAG 注入已移出静态基座（chat `createSpec:52` + agent `resolveAgentPrompt:209`）。
- [ ] **Cache hit 可观测**：`GenericChatClient.parseTokenUsage()` 解析 DeepSeek `prompt_cache_hit_tokens` / 百炼 `prompt_tokens_details.cached_tokens`；`LlmResponse.TokenUsage` 新增 `cacheHitTokens` 字段。
- [ ] 现有 `hybridSearch/vectorSearch/bm25Search` 召回行为不变（回归通过）。
- [ ] `docs/design/page-index-search-tool.md` 已删除；死代码 `AgentChatResponse.java` 已删除。

## Out of Scope

- PageIndex / 章节级 TOC 树 / 结构化文档导航。
- 实体抽取、关系抽取、知识图谱。
- 现有 chunking / ETL 召回策略改造（只新增 fileName 写入）。
- 存量数据回填。
