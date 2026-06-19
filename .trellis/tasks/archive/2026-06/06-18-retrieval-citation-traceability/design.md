# Design — RAG 检索引用追溯（agent + chat 双路径）

> 配套 `prd.md`。本设计基于代码库现状核查（见文末"已核实事实"），所有 `file:line` 均已逐条复核。
> **v2 修订要点**（相对初版）：
> 1. ~~`<<REF>>` 注入点从 user message 改为 system message~~ → **v3 进一步优化**（见下）。
> 2. 流式（R8）补齐 **Redis 对话记忆（load+save）+ DB 落库**：流式改为走 advisor 链（与阻塞式同源），不再裸 `.user(message).stream()`。
> 3. agent prompt 基座叠加覆盖 **全部 4 个意图**（compose 点 = `resolveAgentPrompt` 公共出口），不只 DEEP_RETRIEVAL。
> 4. agent references 接线点明确为 `AgentModeStrategy.execute`；`StrategyExecuteResult` 两个工厂同步加 references。
> 5. 澄清**两套独立存储**：Redis ChatMemory（LLM 上下文窗口）vs DB messages（UI/审计），两者都不被 `<<REF>>` 污染。
>
> **v3 修订要点**（KV-cache / 前缀缓存优化）：
> 6. **静态/动态拆分**：system prompt = 纯 default.xml 静态基座（跨请求字节稳定 → 前缀缓存命中）；CAG 段 + `<<REF>>` 块 = 动态尾。
> 7. **动态尾注入位置 = 历史之后**（不是静态和历史之间）：由新 `RagContextAdvisor` 以 `SystemMessage` 插在对话历史之后、当前问题之前。这样 `[静态基座 + 历史]` 保持稳定/append-only 前缀 → 命中；仅动态尾 miss。若动态插在静态与历史之间，历史每轮重算 → 缓存收益尽失。
> 8. **CAG 注入移出 system**：`createSpec:52` 的 `contextPromptInjector.inject` 删除（CAG 段并入动态尾，否则静态基座不稳定）。
> 9. 记忆安全升级为 **role 级保证**：动态用 `SystemMessage` → `MessageChatMemoryAdvisor`（Spring AI 1.1.6）只存 user+assistant，system 永不入库，与 advisor 顺序无关。
>
> **v4 修订要点**（Agent 前缀缓存 + Cache 可观测）：
> 10. **Agent prompt 静态/动态拆分**：`AgentSystemPromptAdvisor.before()` 拆成两个 `SystemMessage`（静态基座+意图模板 / 动态中间答案+护栏），静态部分跨 ReAct 轮次字节不变 → 前缀缓存命中。
> 11. **TokenUsage cache hit 解析**：`LlmResponse.TokenUsage` 新增 `cacheHitTokens`，`GenericChatClient.parseTokenUsage()` 读取 DeepSeek `prompt_cache_hit_tokens` / 百炼 `prompt_tokens_details.cached_tokens`。
> 12. **§6.12 误报修正**：`RagContextAdvisor` order 依赖是确定性编排，非脆弱点。
> 13. **§0.1 措辞修正**："逐轮命中"精确化为"每轮 cache hit 的是静态 + 前面所有轮，本轮新增轮 miss"。
>
> **v5 修订要点**（Agent 动态位置修正 + 计入 tool token）：
> 14. **Agent 动态必须放历史之后**（修正 v4 #10）：v4 把动态 SystemMessage 放在历史之前 → 分叉点提前 → tool 定义 + tool 历史每轮全重算，拆分失效。v5 改为动态放末尾（tools 与历史之后）。前缀缓存分叉后，分叉点之后**全部** token 重算（v4"只 miss 几百 token"误解）。
> 15. **tool 描述/参数计入缓存前缀**：tool 定义静态、占 token、稳定 → 属命中前缀；禁止把动态内容写进 tool description/parameters。
> 16. **Agent 静态剔除 CAG**：`resolveAgentPrompt:209` 的 `contextPromptInjector.inject` 移除（CAG 并入动态尾），与 chat §2.8 同理。

## 0. 两套存储（务必区分，是本设计的基础）

| 存储 | 承载 | 读写方 | 是否存 `<<REF>>` |
|------|------|--------|------------------|
| **Redis ChatMemory** | LLM 上下文窗口记忆（每轮自动进 prompt） | `MessageChatMemoryAdvisor` ↔ `RedisChatMemoryRepository`（`RedisChatMemoryAutoConfiguration:20-28`，key `chat:memory:{conversationId}`） | **否**（advisor 只存 user+assistant，不存 system；`<<REF>>` 在 system → 不进记忆） |
| **DB messages** | 会话/消息持久化（UI 历史、审计） | `ChatMessagePublisher.publishMessageSave`（`:79`）→ `chat_message_save` 总线 → `ChatMessageSaveConsumer` → `ChatConversationHelper.saveMessagesAndNotify` | **否**（落库用 `request.message()` 干净原文，见 `ChatServiceImpl:194` / `MultiTurnModeStrategy:102`） |

> 结论：只要 `<<REF>>` 走 system 注入、落库继续用 `request.message()`，两套存储都天然干净，无需任何剥离逻辑。

## 0.1 KV-cache / 前缀缓存约束（v3 增补，决定注入位置）

LLM 推理方（本仓主模型 DeepSeek，OpenAI 兼容，自带前缀缓存）按**前缀**缓存 KV 状态：两次请求从第 0 个 token 起连续相同 → 第二次复用 KV，跳过计算。**首次分叉点之后全部 cache miss**。

推论（决定本设计的消息布局）：
- **静态内容必须是最靠前的连续前缀，且跨所有请求字节相同** → 才能被全局缓存。
- **动态内容（每请求不同的 `<<REF>>`、CAG 段）必须放在尽可能靠后**，且**绝不能插在"静态基座"与"对话历史"之间**——否则 `静态+动态` 每轮变，历史（动态之后）永远缓存不住，多轮场景下历史几千 token 每轮重算，损失远大于收益。
- 历史是 **append-only**：每轮新增的 user+assistant 首次出现必然 miss；但 **上一轮之前的所有历史**（`静态 + history_{N-1}`）在第 N 轮是稳定前缀 → 命中。逐轮累积：每轮 cache hit 的是"静态 + 前面所有轮"，本轮新增轮 miss。

据此本设计的 token 序（见 §1 图）：
```
[system: 静态基座(default.xml)]  ← 全局稳定，cache hit（跨所有用户/轮次）
[历史 user/assistant 消息]        ← append-only，静态+已有历史命中、本轮新增轮 miss
[system: 动态(CAG 段 + <<REF>> 块)] ← 每请求不同，cache miss（紧邻当前问题，本就 miss）
[user: 当前问题]                   ← 每请求不同，miss（不可避免）
```
- **动态用 `SystemMessage` 角色**（不是 user message）：① role 级保证 `MessageChatMemoryAdvisor`（Spring AI 1.1.6）不持久化 → Redis 记忆不污染；② 放历史之后 → 不破坏 `静态+历史` 前缀缓存。
- **静态基座 = 纯 default.xml**：当前 `createSpec:52` 的 `contextPromptInjector.inject`（CAG，`ContextPromptInjector:9/29`）把动态 CAG 段塞进 system prompt——**必须移出**（挪到动态尾），否则静态基座不稳定、缓存失效。本任务一并处理（CAG 与 REF 同属动态尾）。

## 1. 总体数据流（双路径）

```
入库 ETL：fileName 写进 vector_store.metadata（全部写点，§2.5）
vector_store(id UUID, content, metadata{documentId,userId,teamId,fileName,page_number,...}, embedding)

┌─ AGENT 路径（阻塞式；agent 流式不在本任务范围）──────────────────────────┐
│ 检索 Tool(hybrid/vector/bm25/parent) → Document(id=chunkId, metadata)      │
│   → RetrievedDocument.from(Document)                                       │
│   → ToolWorkspace.add*()        ← 此处分配全局稳定 refNumber（AtomicInteger）│
│   → ToolResult.toJson()         ← 每条 [n]+chunkId+documentId+fileName+page  │
│   → LLM 读 [n]+fileName → 正文 来源#n：文件名                              │
│   → AgentModeStrategy.execute(:233) 从 workspace.getRetrievedDocs() 构造    │
│       List<Reference> → StrategyExecuteResult.agent(... references ...)     │
│ agent system prompt = default.xml 基座 + 意图模板（4 意图，resolveAgentPrompt）│
└────────────────────────────────────────────────────────────────────────────┘

┌─ CHAT 路径（方案 A：拆 RAG Advisor；静态/动态拆分 + 缓存友好的动态尾注入）──┐
│ AbstractModeStrategy.execute(:34, final)：                                  │
│   ① buildAdvisorChain  ← Simple:32 / MultiTurn:51（不再 add(ragAdvisor)）   │
│   ② if isRagEnabled:                                                        │
│        ChatRetrievalService.retrieve(q,userId,teamId) → List<Document>      │
│          （复用 RagAdvisorFactory retriever+postProcessor，不套 Advisor 壳）  │
│        ChatReferenceCollector.collect(docs) → (refBlock, references)         │
│   ③ 构造 per-request RagContextAdvisor(refBlock, cagContext) 加入 chain      │
│   ④ requestSpecFactory.createSpec(..., chain)：                             │
│        → spec.system(纯 default.xml 静态基座)  ← CAG 不再注入此处           │
│        → .user(request.message())              ← user message 干净          │
│        → .advisors(chain)                       ← 含 RagContextAdvisor       │
│   模型实际看到的 token 序（§0.1）：                                           │
│     [system: 静态基座] → [历史] → [system: CAG段+<<REF>>块] → [user: 问题]    │
│   ⑤ StrategyExecuteResult.standard(... references ...)                      │
│ MessageChatMemoryAdvisor：before() 读 Redis 历史；after() 写新轮             │
│   （RagContextAdvisor 的动态 SystemMessage 是 system 角色 → 不被持久化）      │
│ 缓存：静态基座 + 历史 是稳定/append-only 前缀 → 命中；仅动态尾 miss          │
└────────────────────────────────────────────────────────────────────────────┘

┌─ CHAT 流式（R8，与阻塞式同源，同一套 advisor 机制）────────────────────────┐
│ AbstractModeStrategy.executeStream（重写，镜像 execute）：                   │
│   ① buildAdvisorChain（含 MessageChatMemoryAdvisor + RagContextAdvisor）    │
│   ② if isRagEnabled: retrieve → collect → (refBlock, references)            │
│   ③ createSpec(..., chain).stream().content()  ← 走 advisor 链！             │
│        → MessageChatMemoryAdvisor before() 读 Redis 历史 / after() 写新轮  │
│        → RagContextAdvisor 注入动态 SystemMessage（历史后）                  │
│   ④ doOnNext 收集完整 assistant 内容；doFinally → onStreamComplete 落库     │
│   ⑤ 返回 StreamResult(Flux<String> content, List<Reference> references)     │
│ ChatServiceImpl.chatStream(:116) + SseStreamBridge：内容流末尾追加           │
│   `event: references\ndata: <json>` 帧                                       │
│ MultiTurnModeStrategy.executeStream 覆盖（:72-94）删除 → 折叠进抽象层        │
│ onStreamComplete 上提到 AbstractModeStrategy（让 Simple 流式也落库）         │
└────────────────────────────────────────────────────────────────────────────┘

响应 payload（三路统一）：ChatResponse.references = List<Reference>
  Reference(refNumber, chunkId, documentId, fileName, @Nullable page)
```

## 2. 核心契约变更

### 2.1 `RetrievedDocument`（重命名 + 新增字段，双路径共享）
当前（`agent/workspace/RetrievedDocument.java:15-17`）：
```java
public record RetrievedDocument(
    String docId,        // 实为 chunk id
    String content, double score, String source,
    int subQueryIndex, Map<String, Object> metadata) {}
```
改为：
```java
public record RetrievedDocument(
    String chunkId,        // 重命名：原 docId，语义=vector_store.id（chunk）
    String documentId,     // 新增：metadata.documentId 提升为一等字段
    String fileName,       // 新增：metadata.fileName 提升为一等字段
    Integer page,          // 新增：metadata.page_number 提升为一等字段（可空）
    int refNumber,         // 新增：收集器分配的稳定编号（[n]），0=未分配
    String content, double score, String source,
    int subQueryIndex, Map<String, Object> metadata) {
    public RetrievedDocument withRefNumber(int n) { /* 不可变 record 重建 */ }
    public static RetrievedDocument from(Document d) { /* 统一 metadata 提取 */ }
}
```
- `from(Document)` 统一提取规则：`chunkId=d.getId()`、`documentId=metadata.documentId`、`fileName=metadata.fileName`（缺失降级 documentId / "未知"）、`page=metadata.page_number`；`refNumber=0`（由收集器赋值）。供 agent 6 处构造点 + chat collector 复用。
- `metadata` 仍保留其余键（retrievalSource/totalChunks/parentId/isParent 等），不破坏现有读取。
- **消费点契约梳理（docId→chunkId）**：
  - `ToolWorkspace` 去重 `seenDocIds`（`:56` 字段；`:116/:121/:138/:158/:161` 用到）→ 改用 `chunkId()`。
  - `RerankTool.java:81` `new Document(rd.docId(), rd.content(), metadata)` → `rd.chunkId()`。
  - 6 处 `new RetrievedDocument(...)` 构造点 → 全部用 `from(Document)` 或按新签名：`VectorSearchTool:77` / `HybridSearchTool:78` / `Bm25SearchTool:88` / `RerankTool:96` / `ParentDocLookupTool:116` / `ToolWorkspace:304`（`truncateIfNeeded` 重建）。
  - `DocDetailTool.execute(String docIds, ...)`（`:47`，`@param docIds` 在 `:42`）→ **彻底改名 `chunkIds`**（含 LLM-facing 参数名 + tool description），底层查询不变（确认 `::uuid`，见 §6.5）。
- 改前**必须**对 `RetrievedDocument`、`ToolWorkspace.add*`、`DocDetailTool.execute` 跑 `impact`。

### 2.2 `ToolWorkspace` 全局编号（agent 核心机制）
- 新增 `private final AtomicInteger refCounter = new AtomicInteger(0);`（`size` 在 replace/dedup 下不可靠，故独立计数器）。
- `addRetrievedDocs`（`:109`）/ `addRetrievedDocsDeduplicated`（`:151`）：对真正入列的每条 doc 赋 `refNumber = refCounter.incrementAndGet()`；dedup 跳过的不分配（不烧号）。
- `replaceRetrievedDocs`（`:126`，rerank/parentDocLookup 共用）：**保留原 refNumber**。伪代码：
  ```java
  public void replaceRetrievedDocs(List<RetrievedDocument> docs) {
      Map<String,Integer> oldRefNumbers = new HashMap<>();
      for (RetrievedDocument d : retrievedDocs) oldRefNumbers.put(d.chunkId(), d.refNumber());
      retrievedDocs.clear(); seenDocIds.clear();
      for (RetrievedDocument d : docs) {
          int n = oldRefNumbers.getOrDefault(d.chunkId(), refCounter.incrementAndGet());
          retrievedDocs.add(d.withRefNumber(n));
          seenDocIds.add(d.chunkId());
      }
  }
  ```
- `truncateIfNeeded`（`:300`，重建在 `:304`）：用 `withRefNumber(doc.refNumber())` 保留编号。
- 编号统一覆盖 child chunk 与 parent doc（都进同一 `retrievedDocs` 列表）。

### 2.3 `ToolResult.toJson()`（agent，`ToolResult.java:80`，当前 docId 在 `:96`）
改为输出（`LinkedHashMap` 保序）：
```java
d.put("refNumber", doc.refNumber());           // [n]
d.put("chunkId", doc.chunkId());
d.put("documentId", doc.documentId());
d.put("fileName", doc.fileName());
if (doc.page() != null) d.put("page", doc.page());
d.put("score", doc.score());
d.put("content", contentTruncated);            // 现有 500 字符截断（:100-103）保留
d.put("source", doc.source());
```

### 2.4 `Reference` + DTO（双路径统一）
- 新增 `Reference` record（公共包，agent + chat 共用）：
  ```java
  public record Reference(int refNumber, String chunkId, String documentId,
                          String fileName, @Nullable Integer page) {}
  ```
- `StrategyExecuteResult`（`:12-18`）新增 `@Nullable List<Reference> references` 字段；**两个工厂都改**：
  - `standard(response, content)`（`:21`）→ `standard(response, content, null)`；新增 `standard(response, content, references)`。
  - `agent(response, content, agentMetadata)`（`:26-29`）→ 加 `references` 参数（或重载）。
- `ChatResponse`（`chat/dto/ChatResponse.java:17-22`，`@JsonInclude(NON_NULL)` 在 `:16`）新增 `@Nullable List<Reference> references`；保留现有 3-arg（`:25`）/ 4-arg（`:29`）构造器（references 默认 null）。
- `ChatServiceImpl.processResult`（`:182-204`）：`new ChatResponse(...)` 两处（agent 分支 `:199`、非 agent 分支 `:202`）都透传 `result.references()`。

### 2.5 文件名落库（全部写点）
核查到的**全部 `vector_store.metadata` 写点**（每处已有 `documentId/userId/teamId`，补 `fileName`）：

| 写入点 | 位置 | 现有 metadata | 补 fileName |
|--------|------|---------------|-------------|
| 主入库 standard | `StandardStrategy.java:124-130`（put 在 `:125/:126/:128`） | documentId/userId/teamId | ✅ 来源 `RagDocument.fileName` |
| fast-track 异步向量化 | `FastTrackStrategy.java:180-185`（put 在 `:181/:182/:184`） | documentId/userId/teamId | ✅ 来源 `RagDocument.fileName` |
| fast-track BM25 原文行 | `VectorStoreMapper.insertFastTrackRow`（`:117-127`，default 方法） | documentId/userId/fastTrack | ✅ 需把 fileName 加进方法签名（`insertFastTrackRow(Long documentId, String content, Long userId, Long teamId, String fileName)`），透传自 `FastTrackStrategy.writeBm25Row`（`:158`，调用点 `:120`） |
| 旧入库路径 | `EtlPipelineServiceImpl.executeWithUserId`（`:63`，put 在 `:83-88`） | documentId/userId/teamId | ✅ 若在用（`execute():54` 委托给它） |

- **键名**：`fileName`（与 `documentId/userId` 驼峰一致）。**取值**：`RagDocument.fileName`（`RagDocument.java:16`，getter `:69`）。
- **类型**：`vector_store.metadata` 是 `JSON`（非 jsonb）（`V2__vector_store_bm25.sql:20`），用现有 metadata 序列化路径，无需改类型。
- **空值降级**：fileName 缺失写 documentId；检索输出时仍空 → "未知"。

### 2.6 Chat 检索复用（方案 A：拆 Advisor 壳，组件复用）
- `RagAdvisorFactory`（`rag/config/RagAdvisorFactory.java`）：
  - `createIsolatedRetriever(userId, teamId)`（`:112`，当前 private）→ 改 `public`（或新增 `buildRetriever` 公共方法包一层）。
  - `getPostProcessors()`（`:142`，当前 private cached）→ 改 `public`。
  - **新增 `retrieve(String query, Long userId, @Nullable Long teamId): List<Document>`**：复刻 `create()`（`:83-104`）的内部编排，但不套 `RetrievalAugmentationAdvisor`：
    ```
    1. queryTransformers = queryRewriteEnabled ? [rewriteQueryTransformer] : []   // :86-89
    2. transformedQuery = 逐个 transformer.transform(query)                         // 复刻 Advisor 的 pre-retrieval
    3. docs = createIsolatedRetriever(userId, teamId).retrieve(transformedQuery)     // :91
    4. for pp in getPostProcessors(): docs = pp.process(docs, context)              // :92, MMR→Rerank→Parent
    return docs
    ```
  - `create()` 保留（潜在其它消费方；chat 不再用）。
- 新增 `ChatRetrievalService`（`chat/service`）：薄封装，委托 `RagAdvisorFactory.retrieve(...)`。
- **隔离不变**：沿用 `createIsolatedRetriever` 的 userId/teamId 过滤（`:112-140`）。

### 2.7 `ChatReferenceCollector`（产 refBlock，供 `RagContextAdvisor` 注入动态尾）
```java
record ChatRefResult(String refBlock, List<Reference> references) {}

ChatRefResult collect(List<Document> docs) {
    StringBuilder block = new StringBuilder();
    List<Reference> refs = new ArrayList<>();
    int n = 1;
    for (Document d : docs) {
        RetrievedDocument rd = RetrievedDocument.from(d).withRefNumber(n);
        refs.add(new Reference(n, rd.chunkId(), rd.documentId(), rd.fileName(), rd.page()));
        block.append("<<REF>>[").append(n).append("] ")
             .append(orUnknown(rd.fileName())).append("(").append(rd.documentId())
             .append(rd.page()!=null ? ", p."+rd.page() : "").append(")\n")
             .append(truncate(rd.content())).append("\n<<END>>\n");
        n++;
    }
    String refBlock = block.isEmpty() ? null
        : "## 检索参考信息（引用时用「来源#n：文件名」）\n" + block;
    return new ChatRefResult(refBlock, refs);   // 由 RagContextAdvisor 注入动态 SystemMessage（§2.8b）
}
```
- **不碰 user message**：refBlock 由 `RagContextAdvisor` 以 `SystemMessage` 注入历史之后（§2.8b），既不污染记忆（system 角色）又不破坏前缀缓存（动态在历史后）。

### 2.8 `ChatRequestSpecFactory.createSpec` —— 只设静态基座（v3 改）
当前（`:35-58`）：`.user(request.message())`（`:42`）+ `.advisors(chain)`（`:43-45`，带 `CONVERSATION_ID`）+ system prompt 组装（`:51-55`，含 `contextPromptInjector.inject` CAG 注入）。
改（为前缀缓存，§0.1）：
- **system = 纯 default.xml 静态基座**：`spec.system(resolveSystemPrompt(candidateId))`。
- **移除 `:52` 的 `contextPromptInjector.inject`**（CAG 段改由 `RagContextAdvisor` 注入动态尾，§2.8b）。
- **不新增 `refSystemBlock` 参**（REF 改由 `RagContextAdvisor` 注入）。
- `.user(request.message()):42` **不动** → user message 始终干净。
```java
// createSpec 新 system 组装（删除 CAG inject 与 refSystemBlock）
String systemPrompt = resolveSystemPrompt(candidateId);   // 纯 default.xml，跨请求字节稳定
if (systemPrompt != null && !systemPrompt.isBlank()) {
    spec = spec.system(systemPrompt);
}
```
- createSpec 既能给阻塞式 `.call()`（`AbstractModeStrategy.execute:43`），也能给流式 `.stream()`（§2.10）。CAG 上下文仍作为参传进来，但交给 `RagContextAdvisor`（见 §2.9）。

### 2.8b `RagContextAdvisor` —— 动态尾注入（v3 新增，缓存友好 + 记忆安全的核心）
新增 per-request `BaseAdvisor`（仿现有 `ConversationContextAdvisor`/`AgentSystemPromptAdvisor` 模式，代码库已有 4 个 `BaseAdvisor` 实现）：
```java
class RagContextAdvisor implements BaseAdvisor {
    private final String refBlock;            // 可空（RAG 关闭）
    private final RequestContext cagContext;  // 可空（CAG 关闭）

    public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
        String dynamic = composeDynamic(cagContext, refBlock);   // CAG 段 + <<REF>> 块
        if (dynamic == null || dynamic.isBlank()) return request;
        List<Message> msgs = new ArrayList<>(request.chatRequest().messages());
        int insertAt = msgs.size();                              // 默认末尾
        for (int i = msgs.size() - 1; i >= 0; i--) {
            if (msgs.get(i) instanceof UserMessage) { insertAt = i; break; }  // 当前问题之前
        }
        msgs.add(insertAt, new SystemMessage(dynamic));          // 历史之后、当前问题之前
        return request.mutate().chatRequest(msgs).build();
    }
    // getOrder() 排在 MessageChatMemoryAdvisor 之后（历史已加载再插入动态）
}
```
**两条保证**：
1. **记忆安全（role 级）**：动态以 `SystemMessage` 注入；`MessageChatMemoryAdvisor`（Spring AI 1.1.6）只持久化 USER+ASSISTANT，**system 角色永不入库** → Redis 记忆不污染，与 advisor 顺序无关。
2. **缓存友好（位置级）**：动态 `SystemMessage` 插在历史之后、当前问题之前 → `[静态基座 + 历史]` 仍是稳定/append-only 前缀 → 命中；仅动态尾（紧邻本就 miss 的当前问题）miss。
- **CAG 合并**：CAG 段与 REF 同属动态，合进同一个 `SystemMessage`（`composeDynamic` 内调 `contextPromptInjector` 产 CAG 段，再拼 refBlock）。
- **per-request 实例**：随 `buildAdvisorChain` 之后构造、加入 chain（携带本轮 refBlock/cagContext），与 `ConversationContextAdvisor` 同生命周期。

### 2.9 Chat 阻塞式接线（方案 A，v3）
`AbstractModeStrategy.execute()`（`:34-47`，`final`）改造：
```java
public final StrategyExecuteResult execute(StrategyExecutionContext ctx) {
    AdvisorChainContext chainCtx = ...;                              // :35-37
    List<Advisor> chain = new ArrayList<>(buildAdvisorChain(chainCtx).chain()); // :38

    List<Reference> references = null;
    String refBlock = null;
    if (ctx.request().isRagEnabled()) {
        List<Document> docs = chatRetrievalService.retrieve(
            ctx.request().message(), ctx.userId(), ctx.request().teamId());
        ChatRefResult cr = chatReferenceCollector.collect(docs);
        refBlock = cr.refBlock();
        references = cr.references();
    }
    chain.add(new RagContextAdvisor(refBlock, ctx.cagContext()));   // 动态尾注入（§2.8b）

    ChatResponse springResponse = requestSpecFactory.createSpec(
        ctx.chatClient(), ctx.candidateId(), ctx.request(),
        ctx.conversationId(), chain, ctx.cagContext()              // createSpec 只设静态基座（§2.8）
    ).call().chatResponse();

    String content = extractContent(springResponse);
    return StrategyExecuteResult.standard(springResponse, content, references);
}
```
- `AbstractModeStrategy` 注入 `ChatRetrievalService` + `ChatReferenceCollector`（构造器加参；Simple `:23` / MultiTurn `:40` 的 `super(...)` 同步）。
- **类继承已核实**：仅 `SimpleModeStrategy`（`extends`，`:18`）/ `MultiTurnModeStrategy`（`extends`，`:28`）继承 AbstractModeStrategy；`AgentModeStrategy implements ChatModeStrategy`（`:80`，不继承）→ chat 注入逻辑不会污染 agent 路径。
- **注意**：`buildAdvisorChain` 返回的 chain 需可变（copy）以追加 `RagContextAdvisor`；或 `buildAdvisorChain` 自身接收 refBlock/cagContext 直接 add（更省一次 copy）。实现时二选一。

### 2.10 Chat 流式（R8：Redis 记忆 + DB 落库 + system 注入 + references 帧）
现状缺陷（已核实）：`AbstractModeStrategy.executeStream`（`:50-62`）与 `MultiTurnModeStrategy.executeStream`（`:72-94`）都裸 `.user(message).stream().content()`，**不走 advisor 链**：
- 不读 Redis ChatMemory（模型拿不到历史）❌
- 不写 Redis ChatMemory（新轮不进记忆）❌
- MultiTurn **已落库**（`onStreamComplete:102-104` 用干净 `request.message()`）✅；Simple **不落库**（无 onStreamComplete）❌

改造（`AbstractModeStrategy.executeStream` 重写，镜像 `execute`，同一套 advisor 机制）：
```java
public StreamResult executeStream(StrategyExecutionContext ctx) {
    List<Advisor> chain = new ArrayList<>(buildAdvisorChain(chainCtx).chain()); // 含 MessageChatMemoryAdvisor
    List<Reference> references = null; String refBlock = null;
    if (ctx.request().isRagEnabled()) {
        ChatRefResult cr = chatReferenceCollector.collect(
            chatRetrievalService.retrieve(ctx.request().message(), ctx.userId(), ctx.request().teamId()));
        refBlock = cr.refBlock(); references = cr.references();
    }
    chain.add(new RagContextAdvisor(refBlock, ctx.cagContext()));   // 动态尾注入（§2.8b）

    StringBuilder collected = new StringBuilder();
    Flux<String> content = requestSpecFactory.createSpec(
            ctx.chatClient(), ctx.candidateId(), ctx.request(),
            ctx.conversationId(), chain, ctx.cagContext())          // createSpec 只设静态基座（§2.8）
        .stream().content()                                          // ← 走 advisor 链！
        .doOnNext(t -> appendBounded(collected, t))
        .doFinally(sig -> onStreamComplete(ctx, collected.toString(), sig));  // 落库
    return new StreamResult(content, references);
}
```
- **Redis 记忆**：走 advisor 链 → `MessageChatMemoryAdvisor` before() 读历史、after() 写新轮（Spring AI 标准支持流式，见 §6 待验）。`RagContextAdvisor` 的动态 `SystemMessage` 不被持久化。
- **DB 落库**：`onStreamComplete` **上提到 `AbstractModeStrategy`**（当前只在 `MultiTurnModeStrategy:96-113`），复用其 `publishMessageSave(conversationId, request.message(), content, ...)` 逻辑 → Simple 流式也落库（落库用干净原文）。
- **`MultiTurnModeStrategy.executeStream` 覆盖（`:72-94`）删除**：collect+persist+createSpec+RagContextAdvisor 已在抽象层统一，覆盖折叠掉。
- **`StreamResult` record**：`record StreamResult(Flux<String> content, @Nullable List<Reference> references) {}`；`ChatModeStrategy.executeStream` 接口返回类型由 `Flux<String>` 改为 `StreamResult`（**涟漪**：`AgentModeStrategy implements ChatModeStrategy` 也要适配——返回 `new StreamResult(flux, null)` 或 agent 自己的 references，见 §6）。
- **references SSE 帧**：`ChatServiceImpl.chatStream`（`:116-138`）拿到 `StreamResult.references()`，传给 `SseStreamBridge`；内容流末尾追加 `event: references\ndata: <json>`（`SseStreamBridge` 当前只 `sendChunk`→`event().data(chunk)`（`:40-46`），需扩展 `bridge(StreamResult)` 或 `bridge(Flux, List<Reference>)`，在 `complete()`（`:30`）后追加命名事件帧）。

### 2.11 Prompt 组合（基座 + 叠加）
- **`default.xml`**（`static/prompt/default.xml`）= 共享基座。当前内容：r1（`:9`）/ r2（`:10`）/ r3（重复，`:11/:12`）/ r4（`:13`）+ constraints（`:16-22`）。**当前无 `<<REF>>` 约定**——需新增。
- **chat system prompt（静态基座）**：`createSpec:51` `resolveSystemPrompt(candidateId)` → 优先级 `XML(classpath:static/prompt/*.xml，default 兜底) > Caffeine > Redis > PostgreSQL`（`SystemPromptServiceImpl:26/:55-72`）。v3 起 system = **纯 default.xml**（不再 CAG 注入，§2.8），跨请求字节稳定 → 前缀缓存命中。
- **引用约定（静态）vs 实际 REF 块（动态），务必区分**：
  - **约定规则**（"检索参考信息以 `<<REF>>[n]` 块提供，引用时句末加（来源#n：文件名）"）是稳定文本 → 写进 default.xml 基座（Phase 9.1 新增），随静态前缀一起缓存。
  - **实际 `<<REF>>[n]` 检索块**每请求不同 → 由 `RagContextAdvisor` 以动态 `SystemMessage` 注入历史之后（§2.8b），不进静态基座。
- **agent system prompt（静态部分）**（`AgentModeStrategy.resolveAgentPrompt:197-210`）：4 意图 switch（DIRECT_ANSWER/RETRIEVAL/DEEP_RETRIEVAL/GENERAL_TOOL，`:199-202`，皆来自 `AgentRagProperties`）。**基座叠加在公共出口 `:209`**（覆盖全部意图）。v5 起 `resolveAgentPrompt` 只产**静态** = base + 意图模板，**移除 `:209` 的 `contextPromptInjector.inject`**（CAG 并入动态尾，与 chat §2.8 同理——CAG 是每请求变化的，进静态会破坏前缀缓存）：
  ```java
  String base = promptLoaderService.getDefaultPrompt();   // default.xml 基座（见 §6.2 取数方式待确认）
  if (base != null && !base.isBlank()) template = base + "\n\n" + template;
  return template;   // :209 —— 只返回静态（base+意图），CAG 不进这里
  ```
- **Agent prompt 静态/动态拆分（v4 提出；v5 修正动态位置 + 计入 tool token）**：`AgentSystemPromptAdvisor.before()` 当前把 `mergedSystemPrompt + 中间答案 + 护栏` 拼成**一个** `SystemMessage` 放首位 → 中间答案每轮变化 → 分叉点在最前 → 整条 system 及其后所有 token（**tool 定义 + tool 历史**）每轮全 miss。拆成**静态 SystemMessage（首位）+ 动态 SystemMessage（末尾）**：
  ```java
  // before() 改造
  String staticPart = mergedSystemPrompt;                  // base + 意图（无 CAG），跨轮次稳定
  String intermediate = workspace.getIntermediateAnswersSummaryBounded(BUDGET);
  String dynamicPart = composeAgentDynamic(cagSegment, intermediate, checkGuardrails()); // CAG+中间答案+护栏

  List<Message> newMessages = new ArrayList<>();
  newMessages.add(new SystemMessage(staticPart));          // ① 首位：静态
  for (Message m : originalMessages) {                      // ② history + user（tool 定义由 Spring AI/API 注入，位于 system 之后、messages 之前）
      if (!(m instanceof SystemMessage)) newMessages.add(m);
  }
  if (dynamicPart != null) newMessages.add(new SystemMessage(dynamicPart)); // ③ 末尾：动态
  ```
  **消息序（含 tool 定义——tool 描述/参数占 token 且静态）**：
  `[system: 基座+意图] → [tools(该意图 tool 集)] → [tool 历史…] → [system: CAG+中间答案+护栏]`
  - `[基座+意图] + [tools] + [已有 tool 历史]`：同意图跨请求、同请求跨 ReAct 轮次均稳定/append-only → **命中**。
  - `[动态]`：每轮变 → **miss**。因它在末尾、其后无内容（紧邻生成点），**分叉点之后只有动态本身**几百 token 重算。
  - **关键（v5 修正）**：动态必须在 tools 和历史**之后**。v4 把动态放在历史之前 → 分叉点提前到 tools/历史之前 → **tool 定义（可能上千 token）+ 历史（每轮增长，深度检索可达数千 token）全部每轮重算**，拆分基本失效。前缀缓存一旦在某 token 分叉，**分叉点之后的全部 token 都重算**（不是只算分叉那几行）。
  - **tool 描述/参数是静态 token，必须留在缓存前缀**：不得把每请求/每轮变化的内容写进 tool description/parameters（否则 tool 块变动态 → 前缀断裂）。当前 tool 描述来自 Tool 类静态定义，已静态 ✓；本任务也不要改这块。
  预期收益：深度检索 5-10 轮，`[基座+意图+tools+已有历史]` 每轮命中（DeepSeek 缓存命中 1/10 计费），仅末尾动态中间答案 miss。改动量 ~15 行，Phase 7 同步。
- **既有 bug 顺手修**：`default.xml` 两个 `r3`（`:11/:12`）→ 第二个改 `r5`。

### 2.12 TokenUsage 缓存命中可观测（v4 新增）
当前 `LlmResponse.TokenUsage`（`LlmResponse.java:36`）只有 `promptTokens / completionTokens / totalTokens`。DeepSeek 响应 `usage` 里的 `prompt_cache_hit_tokens` 和百炼的 `prompt_tokens_details.cached_tokens` 被 `parseTokenUsage()`（`GenericChatClient.java:251-257`）丢弃。

改动：
- `TokenUsage` 新增 `@Nullable Integer cacheHitTokens`（第 4 字段，向后兼容——非 RAG 场景为 null 不序列化）。
- `parseTokenUsage()` 补充读取：
  ```java
  // DeepSeek: prompt_cache_hit_tokens
  Integer cacheHit = usage.has("prompt_cache_hit_tokens")
      ? usage.get("prompt_cache_hit_tokens").asInt() : null;
  // 百炼/Qwen/GLM: prompt_tokens_details.cached_tokens
  if (cacheHit == null) {
      JsonNode details = usage.path("prompt_tokens_details");
      if (details.has("cached_tokens")) cacheHit = details.get("cached_tokens").asInt();
  }
  ```
- `ChatModelAdapter`（`adapter/ChatModelAdapter.java`）的 `LlmResponse → ChatResponse` 映射同步：把 `cacheHitTokens` 透传到 Spring AI `Usage` 的 `getUsage()` 扩展字段或 metadata。
- **可观测**：`UsageServiceImpl` 或日志记录 `cache_hit_tokens / prompt_tokens = hit_rate`，用于评估 prefix 稳定性。
- 改动量 ~20 行，在 Phase 2（数据模型）同步完成，测试在 Phase 10.12。

## 3. 取舍与兼容

| 决策 | 取舍 | 理由 |
|------|------|------|
| fileName 落 metadata | 入库写一次，读端零开销；rename 后旧切片 fileName 过时（需重传） | 本地开发无回填负担；metadata 自包含 |
| documentId/fileName/page 提升为字段 | record 字段增多；构造点全改 | LLM 看显式字段比从 metadata 挖更稳；编号必须一等 |
| docId→chunkId 全量重命名（含 LLM-facing） | 多文件契约改动、风险中等 | 消除"docId 实为 chunkId"认知债；本地无存量 prompt 包袱 |
| 编号用独立 AtomicInteger 而非 list size | 多一个字段 | size 在 replace/dedup 下不可靠 |
| **`<<REF>>` 注入 system 而非 user** | 多一个 `RagContextAdvisor` | `MessageChatMemoryAdvisor` 只存 user+assistant → system 角色永不入库 → 记忆零污染（role 级保证，与 advisor 顺序无关）；blocking/streaming 通用；无需剥离 |
| **静态/动态拆分 + 动态尾注入（v3）** | createSpec 不再 CAG 注入；新增 `RagContextAdvisor` 插历史后 | 前缀缓存：`[静态基座 + 历史]` 稳定/append-only → 命中，仅动态尾 miss；若动态插静态与历史之间，历史每轮重算 → 收益尽失 |
| **动态用 `SystemMessage`（非 user）** | 依赖 provider 支持历史后的 system 消息（DeepSeek/OpenAI 兼容，支持） | role 级记忆安全 + 位置级缓存友好，二者兼得 |
| **chat 方案 A 拆 Advisor** | 需自串 retriever→postProcessor 编排（~15 行）+ 复刻 query-transform 顺序 | 格式/编号/references 全可控；不跟 Spring AI advisor context 较劲；100% 复用隔离/MMR/Rerank/Parent |
| **流式走 advisor 链（统一 createSpec）** | 改 executeStream 较多 + 接口返回类型涟漪 | 一次性补齐 Redis 记忆(load+save)；onStreamComplete 上提让 Simple 也落库；与阻塞式同源易维护 |
| **流式 references 走末尾 SSE 帧** | 改 executeStream 返回类型 + SseStreamBridge 扩展 | references 语义属"回答出处"，末尾自然；订阅前已知无时序问题 |

## 4. 回滚形状

- 全部为应用层 + 配置 + 文档改动，**无 DDL 迁移**（metadata 已存在，只是多写一个键）。
- 回滚 = revert 提交即可；vector_store 多写的 `fileName` 键无害（旧代码忽略）。
- `RetrievedDocument` / `StrategyExecuteResult` / `ChatResponse` record 签名变更是**破坏性**改动，回滚需整提交回退，不能部分回滚。
- chat 移除 `RetrievalAugmentationAdvisor` + 流式改走 createSpec 后，回滚需同时恢复 Simple/MultiTurn 的 `add(ragAdvisor)`、AbstractModeStrategy 的检索/注入分支、executeStream 原貌、`onStreamComplete` 下沉。

## 5. 已核实事实（核查时，行号已逐条复核）

- `vector_store`：`id UUID / content TEXT / metadata JSON / embedding VECTOR(1024)`（`V2__vector_store_bm25.sql:19-21`），**无 doc_id 列**，文档归属靠 `metadata.documentId`。
- `vector_store.id` 是 UUID（[[vector-store-uuid-gotcha]]）：`DocDetailTool` 走 `fetchDocHighlights(idList,…)`（`DocDetailTool:75` → `VectorStoreMapper:187` default），底层 SQL 是否 `::uuid` **待实现前确认**（§6.5）。
- 页码：`PdfDocumentParser` 用 `PagePdfDocumentReader`（pagesPerDocument=1），页码由 Spring AI 写入 metadata（标准 `page_number` 键）；`StructureAwareChunkStrategy.chunkPdf`（`:166`）经 `createChunk` 把页 metadata 透传进 chunk。
- **fileName 落库全部写点**：`StandardStrategy:124-130` / `FastTrackStrategy:180-185` / `VectorStoreMapper.insertFastTrackRow:117-127` / `EtlPipelineServiceImpl.executeWithUserId:63`（put `:83-88`，被 `execute():54` 委托）。
- **两套存储**：Redis ChatMemory = `RedisChatMemoryRepository`（`:50`，key `chat:memory:` `:54`），bean 在 `RedisChatMemoryAutoConfiguration:20-28`；DB 落库 = `ChatMessagePublisher.publishMessageSave`（`:79`），调用点仅 `ChatServiceImpl:194`（阻塞式）+ `MultiTurnModeStrategy:102`（流式 MultiTurn）。落库均用 `request.message()` 干净原文。
- **chat 检索链路**：`SimpleModeStrategy.buildAdvisorChain:32`（ragAdvisor add `:35-38`）、`MultiTurnModeStrategy.buildAdvisorChain:51`（ragAdvisor add `:57-60`，`MessageChatMemoryAdvisor` `:66`）；`RagAdvisorFactory.create:83-104` 串 query-transform + 隔离检索（`createIsolatedRetriever:112`）+ postProcessor（`getPostProcessors:142`）。
- **chat system prompt**：`SystemPromptServiceImpl.getPrompt:55`，优先级 XML>Caffeine>Redis>PG（`:26`）；default.xml 经 `PromptLoaderService` 加载，是 chat 兜底。
- **chat 编排**：`ChatController` → `ChatServiceImpl.chat():88` / `chatStream():116` → `prepare():142` → `modeStrategy.execute/execStream`；响应组装 `processResult:182-204`（`new ChatResponse` agent 分支 `:199` / 非 agent `:202`，落库 `:194`）；流式 `SseStreamBridge.bridge:137`。
12. ~~⬜ **`RagContextAdvisor` 排序**（v3）~~ **误报（v4 修正）**：Advisor 链按 `getOrder()` 返回值确定性排序，`RagContextAdvisor` 和 `MessageChatMemoryAdvisor` 各有固定 order 值，顺序不可能随机颠倒。除非有人故意改 order 值，但那是代码变更本身的问题，不是架构脆弱性。此条闭环，无需额外防御。
- **类继承**：仅 Simple（`:18`）/ MultiTurn（`:28`）`extends AbstractModeStrategy`；`AgentModeStrategy implements ChatModeStrategy`（`:80`，不继承，自带 `execute:233`）。
- **agent prompt**：`AgentModeStrategy.resolveAgentPrompt:197-210`（4 意图 switch `:199-202`，公共出口 `:209`）→ `AgentSystemPromptAdvisor`（链加入 `:165`，`before:68` 设 system `:73` + 追加中间答案 `:76`）。
- `ToolWorkspace`：`addRetrievedDocs:109` / `replaceRetrievedDocs:126` / `addRetrievedDocsDeduplicated:151` / `truncateIfNeeded:300`（重建 `:304`）/ `seenDocIds:56`；构造 `ToolWorkspace(long userId, @Nullable Long teamId)`。
- `buildDeepRetrievalToolSet`（`AgentToolCallbackFactory.java:110`，含 9 个 tool 字段 `:42-50`）。
- **`AgentChatResponse` 是死代码**（全仓 main+test 无构建/引用，无 AgentController/AgentChatService）→ agent 经 `processResult:199` 落 `ChatResponse`。R5"两路统一 ChatResponse.references"成立；建议顺手删 `AgentChatResponse.java`。
- Flyway 当前到 V15（本任务**无新迁移**）。
- `SseStreamBridge`（`:14`）：`bridge(Flux):19` → `sendChunk` 只 `event().data(chunk)`（`:46`），`complete():30`；**只透传 data 帧**，references 命名事件帧需扩展。

## 6. 待 Planning/实现确认的研究项

> 已闭环 ✅；仍需确认 ⬜。

1. ✅ ~~agent prompt 是否经 `AgentSystemPromptAdvisor` 二次拼装~~ —— **是**（`:165` 加入链，`before:68` 注入 system）。基座叠加点 = `resolveAgentPrompt:209` 公共出口，覆盖 4 意图。
2. ✅ ~~default.xml 基座取数方式~~ —— `PromptLoaderService` 接口**无** `getDefaultPrompt()`（仅有 `getPrompt(modelId)`，modelId 未命中时 fallback 到 `defaultTemplate`）。`PromptTemplate.toSystemPrompt()` 返回 `rawXml`（`PromptTemplate:26`），`PromptLoaderServiceImpl.state.defaultTemplate()` 是 default.xml 的 `PromptTemplate`。**实现动作（Phase 7.1）**：给 `PromptLoaderService` 加 `getDefaultPrompt()` 返回 `state.defaultTemplate().toSystemPrompt()`（无 default 时返回 null）。
3. ✅ ~~主入库路径写点~~ —— 4 处已实锤（§2.5）；`EtlPipelineServiceImpl` 经 `execute():54` 委托 `executeWithUserId`，按在用处理。
4. ✅ ~~响应序列化点 + DTO~~ —— 统一 `ChatResponse`（`AgentChatResponse` 死代码）；组装 `processResult:199/202`。
5. ✅ ~~`DocDetailTool` UUID~~ —— `fetchDocHighlights`（`VectorStoreMapper:187`）→ `selectHighlightRows`，其 XML `WHERE v.id IN <foreach ...>#{id}::uuid</foreach>`（`VectorStoreMapper.xml:96`）**已显式 `::uuid`**，无 42883 风险。Phase 2 改名 `docIds→chunkIds` 不涉及 SQL，安全。
6. ✅ ~~`SseStreamBridge` 是否只透传 data~~ —— 是（`:40-46`）；references 命名事件帧需扩展 `bridge`。
7. ✅ ~~`<<REF>>` 是否污染记忆~~ —— **不污染**（v3：动态以 `SystemMessage` 注入，`MessageChatMemoryAdvisor` 只存 user+assistant → system 永不入库，role 级保证）。开放项消解。
8. ✅ ~~`MessageChatMemoryAdvisor` 流式持久化~~ —— Spring AI 1.1.5 源码（与 1.1.6 同语义）`MessageChatMemoryAdvisor.adviseStream:126` 在流完成时调 `after:137` → `chatMemory.add` 持久化。流式正确写 Redis。门 G7 仍作运行时确认。
9. ✅ ~~`ChatModeStrategy.executeStream` 接口签名涟漪~~ —— `AgentModeStrategy.executeStream:281` **已存在**（throw `UNSUPPORTED_OPERATION`，agent 流式不支持）。接口 `Flux<String>`→`StreamResult` 影响 3 处：接口 default（`ChatModeStrategy:49`）/ AgentModeStrategy 覆盖（仍 throw，trivial 改返回类型）/ AbstractModeStrategy 覆盖（真正重写，Simple/MultiTurn 继承）。低风险。
10. ✅ ~~provider 是否支持历史后的 system 消息~~ —— 架构走 `GenericChatClient`（OpenAI 兼容 HTTP）+ `ChatModelAdapter`，messages 数组任意位置 system 角色均支持。`application.yml` 的 `app.chat.candidates.list` 为空（动态注册），intent-model 默认 `deepseek-v4-flash`；运行时确认实际 candidate 均 OpenAI 兼容即可（门 G5 覆盖）。若后续接 Anthropic 类（system 顶层单块），加 fallback。
11. ✅ ~~`MessageChatMemoryAdvisor.after()` 忽略 system 消息~~ —— 源码实锤：`before():105` `chatMemory.add(conversationId, userMessage)`（只存 user message），`after():120` 存 assistant；**SystemMessage 不入库**（:91-93 只是把已有 system 消息重定位到首位，不持久化）。故 `RagContextAdvisor` 注入的中段 `SystemMessage` 不进 Redis 记忆。门 G5 运行时复核。
12. ~~⬜ **`RagContextAdvisor` 排序**（v3）~~ **误报（v4 修正）**：Advisor 链按 `getOrder()` 返回值确定性排序，`RagContextAdvisor` 和 `MessageChatMemoryAdvisor` 各有固定 order 值，顺序不可能随机颠倒。除非有人故意改 order 值，但那是代码变更本身的问题，不是架构脆弱性。此条闭环，无需额外防御。
