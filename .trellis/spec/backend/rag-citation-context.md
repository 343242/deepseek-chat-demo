# RAG 检索引用追溯与上下文工程

> 来源：任务 `06-18-retrieval-citation-traceability`（commit `da323c8`）。agent + chat 双路径统一的检索结果可追溯 + LLM 上下文工程约定。改 RAG 检索返回路径 / system prompt / 响应 DTO 前必读。

## 1. RetrievedDocument 命名契约

- **`docId` 已全量重命名为 `chunkId`**。`vector_store.id` 是 **chunk id**（不是文档 id），真正的文档 id 在 `metadata.documentId` —— 历史上 `docId` 实为 chunk id 的误导命名，已彻底改名。
- 字段：`chunkId / documentId / fileName / page / refNumber / score / content`。
- **统一构造入口**：`RetrievedDocument.from(Document)`（提取 chunkId/documentId/fileName/page_number；fileName 缺失降级 `source`→`documentId`→`"未知"`）。
- 6 个构造点（VectorSearch / HybridSearch / Bm25Search / Rerank / ParentDocLookup tools + ToolWorkspace 重建）一律走 `from(Document)` 或新签名，**禁止裸 `new RetrievedDocument(...)`**。
- `DocDetailTool` 的 LLM-facing 参数已改名 `docIds → chunkIds`（参数名 + tool description 同步）。

## 2. fileName 落库

- 入库时把 `fileName` 写进 `vector_store.metadata`，4 写点：`StandardStrategy` / `FastTrackStrategy.asyncVectorize` / `VectorStoreMapper.insertFastTrackRow` / `EtlPipelineServiceImpl`。
- 空文件名降级 documentId / "未知"。本地开发，**不做存量回填**（重新上传即可）。

## 3. Reference DTO（agent/chat 双路径统一）

- `Reference(int refNumber, String chunkId, String documentId, String fileName, @Nullable Integer page)`。
- `StrategyExecuteResult.references` + `ChatResponse.references`（`@JsonInclude(NON_NULL)`，非 RAG 调用序列化为 null，**不出现字段**）。
- agent 从 `ToolWorkspace.getRetrievedDocs()` 构造；chat 从 `ChatReferenceCollector` 构造。**两路 DTO 形状必须一致**。

## 4. ToolWorkspace 全局稳定编号 `[n]`

- 编号必须扛住：多次 tool call（hybrid→bm25→rerank）不重置；rerank 的 `replaceRetrievedDocs` 不重排（clear 前快照 `chunkId→refNumber`，re-add 复用旧号、新 chunkId 续号）；dedup 跳过的重复 chunk 不"烧号"。
- 覆盖所有来源（child chunk + parent doc）。

## 5. 检索段以 SystemMessage 注入（chat 方案 A）

- chat **不再用** `RetrievalAugmentationAdvisor`；`ChatRetrievalService` 直接跑 query-transform → 检索 → MMR → Rerank → ParentChild，返回 `List<Document>`。
- `<<REF>>[n]` 块由 per-request `RagContextAdvisor`（`BaseAdvisor`）以 **SystemMessage** 注入**对话历史之后、当前问题之前**，**不进 user message**。
- **记忆干净性（role 级保证）**：`MessageChatMemoryAdvisor` 只持久化 user+assistant → SystemMessage 永不入 Redis ChatMemory / DB messages，**无需运行期剥离逻辑**。验证：连问两轮，Redis 历史与 DB messages 均不含 `<<REF>>`。

## 6. 静态/动态 prompt 拆分（KV-cache / 前缀缓存友好）

**核心原则**：静态内容（跨请求字节稳定）放最前；动态内容（每请求变化）放历史**之后**、当前问题之前。**绝不**把动态插在静态基座与历史之间（否则历史每轮重算，缓存收益尽失）。

| 路径 | 消息序 |
|------|--------|
| chat | `[system: 纯 default.xml] → [历史] → [system: 动态(CAG+<<REF>>)] → [user: 问题]` |
| agent | `[system: 静态基座+意图] → [tools] → [tool历史] → [system: 动态(CAG+中间答案+护栏)]` |

- **chat 静态基座 = 纯 default.xml**（CAG 注入已从 `ChatRequestSpecFactory.createSpec` 移出，并入动态尾）。
- **agent 静态 = default.xml 基座 + 意图模板**（`resolveAgentPrompt` 公共出口，覆盖全部 4 意图 DIRECT_ANSWER/RETRIEVAL/DEEP_RETRIEVAL/GENERAL_TOOL；CAG 并入动态尾）；`AgentSystemPromptAdvisor.before()` 拆两个 SystemMessage（静态首位 + 动态末尾）。
- `default.xml` 是 agent + chat **共享基座**（r1–r4 + r5/r6 引用约定：检索段 `<<REF>>[n]` / `[n]`，回答用 `来源#n：文件名`）。
- 可观测：`LlmResponse.TokenUsage.cacheHitTokens`（DeepSeek `prompt_cache_hit_tokens` / 百炼 `prompt_tokens_details.cached_tokens`），用于验证前缀缓存命中。

## 7. chat 流式对等

- chat 流式（SIMPLE/MULTI_TURN）**走 advisor 链**（与阻塞式同源 `createSpec`），补齐三件事：
  - **RAG**：`<<REF>>` 注入 system + references 通过 SSE 末尾 `event: references` 帧下发。
  - **Redis 记忆**：`MessageChatMemoryAdvisor` 流式也 load（读历史）+ save（写新轮）。
  - **DB 落库**：`onStreamComplete` 上提到 `AbstractModeStrategy`，SIMPLE 流式也落库（MULTI_TURN 原已落库）。
- 流式不再"不检索 / 不记 / Simple 不落库"。
