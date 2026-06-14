# Agentic RAG 实现参考

> **关联文档**: `docs/AGENTIC-RAG-DESIGN.md`
> **用途**: 设计文档中属于"怎么做"的实现细节，供开发阶段参考。
> **分支**: `agentic-rag-dev`
> **创建日期**: 2026-05-21

---

## 1. IntentClassifier 完整实现

> 设计文档 §2.3 定义了意图分类器的职责和设计决策，以下是实现参考。
>
> **第一版**：只做意图分类，`IntentResult.subQueries` 始终为空列表。查询分解为后续迭代。
> `doClassify()` 方法的 Structured Output 只需返回 `{ "intent": "...", "confidence": 0.95 }`。

```java
@Component
public class IntentClassifier {

    private static final Logger log = LoggerFactory.getLogger(IntentClassifier.class);

    /** 最大重试次数 */
    private static final int MAX_RETRIES = 2;
    /** 单次调用超时 */
    private static final Duration INTENT_TIMEOUT = Duration.ofSeconds(5);

    /** 安全默认值：降级到 DEEP_RETRIEVAL（暴露全量 Tool，宁可多检索不漏检） */
    private static final IntentResult SAFE_FALLBACK = new IntentResult(
        AgentIntent.DEEP_RETRIEVAL, 0.0, Collections.emptyList()
    );

    private final ChatClient intentChatClient;  // 通过 ChatClientRegistry.get(properties.intentModel()) 获取

    /**
     * 分类意图 + 分解查询
     *
     * 容错策略：2 次重试 → 降级 DEEP_RETRIEVAL
     */
    public IntentResult classify(String query) {
        // 1. 空查询保护
        if (query == null || query.isBlank()) {
            return SAFE_FALLBACK;
        }

        // 2. 带重试的 LLM 调用
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            try {
                IntentResult result = doClassify(query);
                return validate(result);
            } catch (JsonProcessingException e) {
                log.warn("Intent classification parse failed (attempt {}): {}",
                    attempt, e.getMessage());
            } catch (ApiException e) {
                log.warn("Intent classification API error (attempt {}): status={}",
                    attempt, e.getStatusCode());
            } catch (Exception e) {
                log.error("Intent classification unexpected error (attempt {})", attempt, e);
            }
        }

        log.warn("Intent classification failed after {} retries, falling back to {}",
            MAX_RETRIES, SAFE_FALLBACK.intent());
        return SAFE_FALLBACK;
    }

    private IntentResult doClassify(String query) {
        // 单次 LLM 调用，通过 Spring AI Structured Output 映射到 IntentResult
        // 第一版输出示例（无查询分解）：
        // {
        //   "intent": "DEEP_RETRIEVAL",
        //   "confidence": 0.95
        // }
        // subQueries 始终为空列表
    }

    private IntentResult validate(IntentResult result) {
        if (result.intent() == null) {
            return SAFE_FALLBACK;
        }
        List<String> queries = result.subQueries() != null
            ? result.subQueries() : Collections.emptyList();
        if (queries.size() > 5) {
            queries = queries.subList(0, 5);
        }
        return new IntentResult(result.intent(), result.confidence(), queries);
    }
}
```

---

## 2. AgentToolCallbackFactory 完整实现

> 设计文档 §2.3 定义了意图→Tool 子集映射和闭包捕获 workspace 的设计决策，以下是完整工厂代码。

> **⚠️ Phase 2 前置验证**：Spring AI 1.1.6 的 `FunctionToolCallback.builder(name, biFunction)`
> 的泛型签名需写 PoC 验证。设计文档假设签名为
> `FunctionToolCallback.<I, O>builder(String name, BiFunction<I, ToolContext, O> fn)`。
> 若实际签名不同（如接受 `Function<I, O>` 而非 `BiFunction`），所有 build 方法需对应调整。

```java
@Component
public class AgentToolCallbackFactory {

    private final HybridSearchTool hybridSearchTool;
    private final VectorSearchTool vectorSearchTool;
    private final RerankTool rerankTool;
    private final QueryRewriteTool queryRewriteTool;
    private final ParentDocLookupTool parentDocLookupTool;
    private final KnowledgeBaseInfoTool knowledgeBaseInfoTool;

    public ToolCallback[] createToolCallbacks(AgentIntent intent, ToolWorkspace workspace) {
        return switch (intent) {
            case RETRIEVAL -> new ToolCallback[]{
                buildHybridSearch(workspace),
                buildRerank(workspace)
            };
            case DEEP_RETRIEVAL -> new ToolCallback[]{
                buildVectorSearch(workspace),
                buildHybridSearch(workspace),
                buildRerank(workspace),
                buildQueryRewrite(workspace),
                buildParentDocLookup(workspace)
            };
            case GENERAL_TOOL -> buildGeneralToolSet();
            case DIRECT_ANSWER -> new ToolCallback[]{};
        };
    }

    private ToolCallback buildHybridSearch(ToolWorkspace workspace) {
        return FunctionToolCallback.<HybridSearchRequest, String>builder(
                "hybridSearch",
                (request, ctx) -> hybridSearchTool.execute(request, workspace)
            )
            .description("混合检索：结合向量语义搜索和 BM25 关键词搜索，通过 RRF 融合排序")
            .inputType(HybridSearchRequest.class)
            .build();
    }

    private ToolCallback buildVectorSearch(ToolWorkspace workspace) {
        return FunctionToolCallback.<VectorSearchRequest, String>builder(
                "vectorSearch",
                (request, ctx) -> vectorSearchTool.execute(request, workspace)
            )
            .description("纯向量语义检索，适用于概念性查询")
            .inputType(VectorSearchRequest.class)
            .build();
    }

    private ToolCallback buildRerank(ToolWorkspace workspace) {
        return FunctionToolCallback.<RerankRequest, String>builder(
                "rerank",
                (request, ctx) -> rerankTool.execute(request, workspace)
            )
            .description("对已检索文档进行语义精排，基于百炼 Rerank API")
            .inputType(RerankRequest.class)
            .build();
    }

    private ToolCallback buildQueryRewrite(ToolWorkspace workspace) {
        return FunctionToolCallback.<QueryRewriteRequest, String>builder(
                "queryRewrite",
                (request, ctx) -> queryRewriteTool.execute(request, workspace)
            )
            .description("改写查询以提升检索效果，支持多角度改写生成多个变体")
            .inputType(QueryRewriteRequest.class)
            .build();
    }

    private ToolCallback buildParentDocLookup(ToolWorkspace workspace) {
        return FunctionToolCallback.<Void, String>builder(
                "parentDocLookup",
                (request, ctx) -> parentDocLookupTool.execute(workspace)
            )
            .description("将检索到的文档片段替换为其所属的完整父文档")
            .inputType(Void.class)
            .build();
    }

    private ToolCallback buildKnowledgeBaseInfo(ToolWorkspace workspace) {
        return FunctionToolCallback.<Void, String>builder(
                "knowledgeBaseInfo",
                (request, ctx) -> knowledgeBaseInfoTool.execute(workspace)
            )
            .description("查询当前知识库的元信息，包括文档数量、分块数量等")
            .inputType(Void.class)
            .toolMetadata(ToolMetadata.builder().returnDirect(true).build())
            .build();
    }
}
```

---

## 3. 各 RAG Tool 实现参考

> 设计文档 §3.4 定义了每个 Tool 的职责和封装组件，以下是具体实现骨架。

### 3.1 VectorSearchTool

```java
@Component
public class VectorSearchTool implements RagTool {

    private final VectorStore vectorStore;
    private final RagRetrievalProperties properties;

    public String execute(VectorSearchRequest request, ToolWorkspace workspace) {
        // 从 workspace 获取 userId/teamId 构建过滤条件
        // 调用 vectorStore.similaritySearch()
        // 追加到 workspace.retrievedDocs
        // 返回 JSON 摘要
    }
}
```

### 3.2 HybridSearchTool

```java
@Component
public class HybridSearchTool implements RagTool {

    private final HybridSearchService hybridSearchService;

    public String execute(HybridSearchRequest request, ToolWorkspace workspace) {
        // 委托 HybridSearchService.execute(query, topK, userId, teamId)
        // 追加到 workspace.retrievedDocs
        // 返回 JSON 摘要
    }
}
```

### 3.3 RerankTool

```java
@Component
public class RerankTool implements RagTool {

    private final BailianRerankPostProcessor rerankProcessor;

    public String execute(RerankRequest request, ToolWorkspace workspace) {
        // 从 workspace.retrievedDocs 获取待排序文档
        // 调用 BailianRerankPostProcessor 核心逻辑
        // workspace.replaceRetrievedDocs(reranked)
        // 返回 JSON 摘要
    }
}
```

### 3.4 QueryRewriteTool

```java
@Component
public class QueryRewriteTool implements RagTool {

    private final ChatClient.Builder chatClientBuilder;

    public String execute(QueryRewriteRequest request, ToolWorkspace workspace) {
        // 调用 LLM 改写查询（复用 RewriteQueryTransformer 的 prompt）
        // workspace.addRewrittenQueries(rewritten)
        // 返回 JSON 摘要
    }
}
```

### 3.5 ParentDocLookupTool

```java
@Component
public class ParentDocLookupTool implements RagTool {

    private final VectorStoreMapper vectorStoreMapper;

    public String execute(ToolWorkspace workspace) {
        // 从 workspace.retrievedDocs 获取子块文档
        // 复用 ParentDocumentPostProcessor 核心逻辑
        // workspace.replaceRetrievedDocs(parentDocs)
        // 返回 JSON 摘要
    }
}
```

### 3.6 KnowledgeBaseInfoTool

```java
@Component
public class KnowledgeBaseInfoTool implements RagTool {

    private final VectorStoreMapper vectorStoreMapper;

    public String execute(ToolWorkspace workspace) {
        // 从 workspace 获取 userId/teamId
        // 查询文档数量、分块数量、最近更新时间
        // 返回 JSON 格式的知识库统计
    }
}
```

### 3.7 Tool 容错模板（以 HybridSearchTool 为例）

> 设计文档 §6.2 定义了分层容错策略，以下是每个 Tool 的容错实现模板。

```java
@Component
public class HybridSearchTool implements RagTool {

    private static final Duration TOOL_TIMEOUT = Duration.ofSeconds(10);

    public String hybridSearch(String query, @Nullable Integer topK) {
        long start = System.currentTimeMillis();
        try {
            // 参数校验
            if (query == null || query.isBlank()) {
                return ToolResult.failure("hybridSearch",
                    "查询文本不能为空", "INVALID_INPUT", 0).toJson();
            }

            ToolWorkspace ws = workspace; // 闭包捕获的 workspace 局部变量
            if (ws == null) {
                return ToolResult.failure("hybridSearch",
                    "Workspace 未初始化", "INTERNAL_ERROR", 0).toJson();
            }

            // 执行检索（带超时保护）
            List<Document> docs = doHybridSearch(query, topK, ws);

            long duration = System.currentTimeMillis() - start;
            List<RetrievedDocument> retrieved = toRetrievedDocs(docs, ws.getCurrentSubQueryIndex());
            ws.addRetrievedDocs(retrieved);

            return ToolResult.success("hybridSearch",
                formatSummary(query, retrieved.size()),
                retrieved, duration).toJson();

        } catch (DataAccessException e) {
            long duration = System.currentTimeMillis() - start;
            log.error("Hybrid search DB error", e);
            return ToolResult.failure("hybridSearch",
                "数据库暂时不可用，请尝试其他检索方式",
                "DB_ERROR", duration).toJson();

        } catch (ApiException e) {
            long duration = System.currentTimeMillis() - start;
            log.error("Hybrid search API error: status={}", e.getStatusCode(), e);
            return ToolResult.failure("hybridSearch",
                "检索服务暂时不可用，可以稍后重试或尝试 bm25Search",
                "API_ERROR", duration).toJson();

        } catch (Exception e) {
            long duration = System.currentTimeMillis() - start;
            log.error("Hybrid search unexpected error", e);
            return ToolResult.failure("hybridSearch",
                "检索发生未知错误",
                "UNKNOWN_ERROR", duration).toJson();
        }
    }
}
```

---

## 4. AgentSystemPromptAdvisor 完整实现

> 设计文档 §4.3 定义了该 Advisor 实现 `BaseAdvisor` 接口、order=1 在 ToolCallAdvisor 之前执行，
> 以下是完整代码。
>
> **中间答案注入机制**：构造时接收 `ToolWorkspace` 引用（与 Tool 闭包共享同一个对象引用），
> `before()` 每轮从 workspace 读取中间答案，追加到 System Prompt 末尾。
> 为什么选构造注入：(a) 最简单直接，零额外机制；(b) advisorParams 需额外序列化；(c) 专用 Tool 依赖 LLM 主动调用不可靠。

```java
import org.jspecify.annotations.NonNull;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.messages.SystemMessage;

import java.util.ArrayList;
import java.util.List;

public class AgentSystemPromptAdvisor implements BaseAdvisor {

    private final AgentIntent intent;
    private final String mergedSystemPrompt;
    private final ToolWorkspace workspace;  // 与 Tool 闭包共享同一个引用

    public AgentSystemPromptAdvisor(AgentIntent intent, String mergedSystemPrompt, ToolWorkspace workspace) {
        this.intent = intent;
        this.mergedSystemPrompt = mergedSystemPrompt;
        this.workspace = workspace;
    }

    @Override
    @NonNull
    public String getName() {
        return "AgentSystemPromptAdvisor";
    }

    @Override
    public int getOrder() {
        return 1; // 在 ToolCallAdvisor(order=2) 之前执行
    }

    @Override
    @NonNull
    public ChatClientRequest before(@NonNull ChatClientRequest request, @NonNull AdvisorChain chain) {
        // 构建最终 System Prompt = 基础 Prompt + 中间答案（如有）
        String finalPrompt = mergedSystemPrompt;
        String intermediateSummary = workspace.getIntermediateAnswersSummary();
        if (intermediateSummary != null && !intermediateSummary.isBlank()) {
            finalPrompt += "\n\n## 已收集的信息\n" + intermediateSummary;
        }

        SystemMessage systemMessage = new SystemMessage(finalPrompt);
        List<org.springframework.ai.chat.messages.Message> instructions =
            new ArrayList<>(request.prompt().getInstructions());
        instructions.add(0, systemMessage);

        return ChatClientRequest.builder()
            .prompt(request.prompt().mutate()
                .instructions(instructions)
                .build())
            .context(request.context())
            .build();
    }

    @Override
    @NonNull
    public ChatClientResponse after(@NonNull ChatClientResponse response, @NonNull AdvisorChain chain) {
        return response;
    }
}
```

---

## 5. ChatAdvisorChainFactory AGENT 分支

> 设计文档 §4.2 描述了 Agent 分支的步骤，以下是完整 `buildAgentChain()` 方法体。

```java
public List<Advisor> buildAgentChain(String conversationId,
                                    ChatRequest request,
                                    ChatModeStrategy modeStrategy) {
    List<Advisor> chain = new ArrayList<>();

    // 1. 上下文注入（与 buildChain 一致）
    if (modeStrategy.isContextEnabled()) {
        chain.add(new ConversationContextAdvisor(conversationId));
    }

    // 2. 全局 Advisor（限流、内容过滤）
    chain.addAll(getGlobalAdvisors());

    // 3. 意图识别（阻塞式 LLM 调用）
    Long userId = SecurityUtils.getCurrentUserId();
    Long teamId = request.teamId();
    IntentResult intent = intentClassifier.classify(request.message());

    // 3. 创建 Workspace（局部变量，不进 ThreadLocal）
    ToolWorkspace workspace = workspaceFactory.create(userId, teamId);
    workspace.setIntent(intent.intent());
    if (intent.hasSubQueries()) {
        workspace.setSubQueries(intent.subQueries());
    }

    // 4. 闭包创建 FunctionToolCallback（捕获 workspace 局部变量）
    ToolCallback[] agentTools = agentToolCallbackFactory
        .createToolCallbacks(intent.intent(), workspace);

    // 5. 创建 ToolCallAdvisor（ToolCallback 通过 resolver 传入）
    ToolCallbackResolver resolver = new StaticToolCallbackResolver(List.of(agentTools));
    ToolCallingManager agentToolManager = DefaultToolCallingManager.builder()
        .toolCallbackResolver(resolver)
        .build();
    chain.add(ToolCallAdvisor.builder()
        .toolCallingManager(agentToolManager)
        .advisorOrder(2)
        .disableMemory()
        .build());

    // 6. 动态 System Prompt（Agent Prompt + CAG 上下文合并）
    String agentPrompt = resolvePrompt(intent.intent());
    String cagContext = buildCagContext(ctx, request);
    String mergedPrompt = agentPrompt;
    if (cagContext != null && !cagContext.isBlank()) {
        mergedPrompt += "\n\n## 当前用户上下文\n" + cagContext;
    }
    chain.add(new AgentSystemPromptAdvisor(intent.intent(), mergedPrompt, workspace));

    // 7. Memory
    chain.add(MessageChatMemoryAdvisor.builder(chatMemory).build());

    // workspace 是局部变量，闭包引用它，请求结束 GC 回收
    return chain;
}
```

---

## 6. AgentGuardrails 完整实现

> 设计文档 §6.2 定义了三指标护栏（迭代总数/token 消耗/连续 Tool），以下是完整代码。

```java
@Component
public class AgentGuardrails {

    private final AgentRagProperties properties;
    private final ProviderRegistry providerRegistry;

    private String lastToolName;
    private int consecutiveCount;

    public int resolveTokenLimit(String compositeModelId) {
        int contextWindow = providerRegistry.getContextWindowSize(compositeModelId);
        double ratio = properties.contextWindowRatio(); // 默认 0.8
        return (int) (contextWindow * ratio);
    }

    public GuardrailCheck check(int iteration, int tokensUsed,
                                 int tokenLimit, String currentTool) {
        // 指标 1：循环迭代总次数
        if (iteration > properties.maxToolIterations()) {
            return GuardrailCheck.stop(
                "ITERATION_LIMIT",
                "已达到最大调用轮次 (%d/%d)，停止检索。"
                    .formatted(iteration, properties.maxToolIterations()));
        }

        // 指标 2：累计 token 消耗
        if (tokensUsed >= tokenLimit) {
            return GuardrailCheck.stop(
                "TOKEN_LIMIT",
                "累计 token 消耗已达 %d（上限 %d，模型上下文窗口 %d × %.0f%%），停止检索。"
                    .formatted(tokensUsed, tokenLimit,
                        tokenLimit / properties.contextWindowRatio(),
                        properties.contextWindowRatio() * 100));
        }

        // 指标 3：同一 Tool 连续调用检测（软干预）
        if (currentTool != null && currentTool.equals(lastToolName)) {
            consecutiveCount++;
        } else {
            lastToolName = currentTool;
            consecutiveCount = 1;
        }
        if (consecutiveCount > properties.maxConsecutiveSameTool()) {
            return GuardrailCheck.warn(
                "CONSECUTIVE_TOOL",
                "注意：工具 [%s] 已连续调用 %d 次。请评估：当前已收集的信息是否足够回答用户问题？"
                    + "如果不够，还缺少哪些部分？尝试切换其他工具是否能获得更好的结果？"
                    + "如果信息已充分，请直接生成最终回答。"
                    .formatted(currentTool, consecutiveCount));
        }

        return GuardrailCheck.ok();
    }

    public int getConsecutiveCount() { return consecutiveCount; }

    public record GuardrailCheck(
        boolean allowed,
        @Nullable String stopReason,
        @Nullable String message,
        boolean shouldWarn
    ) {
        static GuardrailCheck ok() {
            return new GuardrailCheck(true, null, null, false);
        }
        static GuardrailCheck stop(String reason, String message) {
            return new GuardrailCheck(false, reason, message, false);
        }
        static GuardrailCheck warn(String reason, String message) {
            return new GuardrailCheck(true, reason, message, true);
        }
    }
}
```

### 护栏检查在 ReAct 循环中的使用

```java
// 在 ToolCallAdvisor 每轮回调中检查
GuardrailCheck check = guardrails.check(iteration, tokensUsed, tokenLimit, currentTool);

if (check.shouldWarn()) {
    log.info("Agent guardrail warning: reason={}, tool={}, consecutive={}",
        check.stopReason(), currentTool, guardrails.getConsecutiveCount());
    workspace.setPendingWarning(check.message());
    // 继续循环
}

if (!check.allowed()) {
    log.warn("Agent guardrail stop: reason={}, iteration={}, tokens={}/{}, workspace docs={}",
        check.stopReason(), iteration, tokensUsed, tokenLimit,
        workspace.getRetrievedDocs().size());
    return buildGuardrailResponse(workspace, check);
}
```

---

## 7. AgentDegradationStrategy 完整实现

> 设计文档 §6.2 定义了全局降级策略，以下是完整代码。

```java
@Component
public class AgentDegradationStrategy {

    private static final Logger log = LoggerFactory.getLogger(AgentDegradationStrategy.class);

    public boolean isAgentAvailable() {
        // 可扩展为健康检查
        return true;
    }

    public List<Advisor> buildDegradedChain(String conversationId,
                                            ChatRequest request,
                                            ChatModeStrategy modeStrategy) {
        log.warn("Agent mode degraded to MULTI_TURN with RAG Pipeline");
        // 复用 MULTI_TURN 的链路构建逻辑
    }
}
```

---

## 8. 意图识别 Prompt 模板

> 设计文档 §2.3 定义了意图分类的任务，以下是完整的 Prompt 模板。

```
分析用户查询，完成两个任务：

任务 1 — 意图分类：
- DIRECT_ANSWER: 通用知识、闲聊、简单问答，不需要知识库
- RETRIEVAL: 需要知识库检索，单次检索即可满足，问题单一明确
- DEEP_RETRIEVAL: 复杂问题，需要多轮检索、查询改写、语义精排
- GENERAL_TOOL: 需要数学计算、日期查询、代码执行等工具

任务 2 — 查询分解（仅 RETRIEVAL / DEEP_RETRIEVAL 时执行）：
将用户的原始问题拆解为独立、可并行检索的子问题。

分解规则：
- 每个子问题应是一个独立的信息需求，可单独检索
- 对比类问题拆为各方的独立查询 + 对比关系查询
- 多条件问题拆为各条件的独立查询
- 简单单一问题不需要分解，保持原样作为唯一子问题
- 子问题数量控制在 1-5 个
- 子问题应保留原始问题的核心术语和上下文

示例：
  原始：「对比 RAG 和 Fine-tuning 在知识更新场景的优劣」
  分解：[
    "RAG 系统如何实现知识更新",
    "Fine-tuning 模型如何更新知识",
    "RAG 和 Fine-tuning 在知识更新场景的优劣对比"
  ]

  原始：「Spring Boot 的自动装配原理是什么？」
  分解：["Spring Boot 自动装配原理"]（单一问题，不分解）

输出格式（JSON）：
{
  "intent": "DIRECT_ANSWER|RETRIEVAL|DEEP_RETRIEVAL|GENERAL_TOOL",
  "confidence": 0.0-1.0,
  "subQueries": ["子问题1", "子问题2", ...]
}

注意：DIRECT_ANSWER 和 GENERAL_TOOL 类型，subQueries 为空数组 []
```

---

## 9. 检索代价感知 Prompt 片段

> 设计文档 §2.4.4 定义了检索代价感知的引导规则，以下是 System Prompt 中的关键片段。

```
检索代价规则：
1. 每次检索都有成本（延迟 + token 消耗），优先使用已有知识
2. 检查 Workspace.intermediateAnswers — 如果前面的子问题已检索过相关信息，直接引用
3. 只有在确实需要外部知识时才调用检索工具
4. 能用 rerank 精排解决的，不要重新检索
5. 能用自身知识回答的，不要调用任何工具
```

---

## 10. 完整配置类

> 设计文档 §4.4 和 §6.5 各定义了一部分配置，以下是合并后的完整版本。

```yaml
app:
  agent:
    enabled: true
    # 意图识别
    intent-model: deepseek-v4-flash
    intent-temperature: 0.1
    intent-retries: 2
    intent-timeout-ms: 5000
    # ReAct 循环
    max-tool-iterations: 10
    max-consecutive-same-tool: 3
    context-window-ratio: 0.8
    # 容错
    tool-timeout-ms: 10000
    degrade-on-failure: true
    # 动态 System Prompt
    direct-answer-prompt: "..."
    retrieval-prompt: "..."
    deep-retrieval-prompt: "..."
    general-tool-prompt: "..."
```

```java
@ConfigurationProperties(prefix = "app.agent")
public record AgentRagProperties(
    boolean enabled,
    // 意图识别
    String intentModel,
    Double intentTemperature,
    int intentRetries,
    int intentTimeoutMs,
    // 循环护栏
    int maxToolIterations,
    int maxConsecutiveSameTool,
    double contextWindowRatio,
    // 容错
    int toolTimeoutMs,
    boolean degradeOnFailure,
    // System Prompt
    String directAnswerPrompt,
    String retrievalPrompt,
    String deepRetrievalPrompt,
    String generalToolPrompt
) {
    public AgentRagProperties {
        if (maxToolIterations <= 0) maxToolIterations = 10;
        if (maxConsecutiveSameTool <= 0) maxConsecutiveSameTool = 3;
        if (contextWindowRatio <= 0 || contextWindowRatio > 1) contextWindowRatio = 0.8;
        if (intentRetries < 0) intentRetries = 2;
        if (intentTimeoutMs <= 0) intentTimeoutMs = 5000;
        if (toolTimeoutMs <= 0) toolTimeoutMs = 10000;
        if (intentTemperature == null) intentTemperature = 0.1;
    }
}
```

---

## 11. ToolResult 统一返回格式

> 设计文档 §6.2 定义了 Tool 调用容错的统一返回格式，以下是完整实现。

```java
public record ToolResult(
    boolean success,
    String action,
    String summary,
    @Nullable String errorMessage,
    @Nullable String errorCategory,
    @Nullable List<RetrievedDocument> documents,
    long durationMs
) {
    public static ToolResult success(String action, String summary,
                                     List<RetrievedDocument> docs, long durationMs) {
        return new ToolResult(true, action, summary, null, null, docs, durationMs);
    }

    public static ToolResult failure(String action, String errorMessage,
                                     String errorCategory, long durationMs) {
        return new ToolResult(false, action, null, errorMessage,
            errorCategory, null, durationMs);
    }

    public String toJson() {
        // 返回结构化 JSON
    }
}
```

---

## 12. 可观测性记录类

> 设计文档 §6.4 定义了 Agent 执行追踪，以下是完整 record 定义。

```java
public record AgentTrace(
    String traceId,                   // UUIDv7
    long userId,
    String query,
    AgentIntent intent,
    List<String> subQueries,
    List<ToolCallRecord> toolCalls,
    int totalIterations,
    int totalTokensUsed,
    long totalDurationMs,
    String finalStatus,               // COMPLETED / DEGRADED / FAILED
    @Nullable String stopReason
) {}

public record ToolCallRecord(
    int iteration,
    String toolName,
    Map<String, Object> inputParams,
    boolean success,
    @Nullable String errorCategory,
    int resultDocCount,
    long durationMs
) {}
```

---

## 13. AgentDegradationStrategy

> 设计文档 §6.2 第四层定义了全局降级策略，以下是完整实现。

```java
@Component
public class AgentDegradationStrategy {

    private static final Logger log = LoggerFactory.getLogger(AgentDegradationStrategy.class);

    public boolean isAgentAvailable() {
        return true;
    }

    public List<Advisor> buildDegradedChain(String conversationId,
                                            ChatRequest request,
                                            ChatModeStrategy modeStrategy) {
        log.warn("Agent mode degraded to MULTI_TURN with RAG Pipeline");
        // 复用 MULTI_TURN 的链路构建逻辑
    }
}
```

---

## 14. 集成修复的完整代码

> 设计文档 §5.4 P1-P6 定义了 6 个集成冲突点，以下是每个修复的完整代码。

### P1: ChatRequest.mode 正则扩展

```java
// 修改前
@Pattern(regexp = "^(SIMPLE|MULTI_TURN)$",
         message = "对话模式仅支持 SIMPLE 或 MULTI_TURN")
String mode,

// 修改后
@Pattern(regexp = "^(SIMPLE|MULTI_TURN|AGENT)$",
         message = "对话模式仅支持 SIMPLE、MULTI_TURN 或 AGENT")
String mode,
```

### P3: ChatRequestSpecFactory 跳过全量 Tool 绑定

```java
// 修改前
if (advisorChainFactory.hasTools()) {
    spec = spec.tools((Object) advisorChainFactory.getToolCallbacks());
}

// 修改后
if (advisorChainFactory.hasTools() && !modeStrategy.isAgentMode()) {
    spec = spec.tools((Object) advisorChainFactory.getToolCallbacks());
}
```

### P4: ChatRequestSpecFactory 跳过 DB System Prompt

```java
// 修改后 — Agent 分支由 AgentSystemPromptAdvisor 接管
if (modeStrategy.isAgentMode()) {
    // 跳过 DB System Prompt 和 DB ModelParams
    // CAG 上下文传递给 Agent 编排层处理
} else {
    String systemPrompt = resolveSystemPrompt(route);
    systemPrompt = contextPromptInjector.inject(systemPrompt, cagContext);
    if (systemPrompt != null && !systemPrompt.isBlank()) {
        spec = spec.system(systemPrompt);
    }
    ChatOptions options = resolveChatOptions(route);
    if (options != null) {
        spec = spec.options(options);
    }
}
```

### P5: ChatModeStrategy 扩展

```java
public interface ChatModeStrategy {
    ChatMode getMode();
    boolean isMemoryEnabled();
    boolean isContextEnabled();
    boolean isThinkingEnabled();

    /**
     * 是否为 Agent 模式
     * default false 保证 SIMPLE / MULTI_TURN 无需改动
     */
    default boolean isAgentMode() {
        return false;
    }
}
```

### P6: AgentChatResponse 响应包装

```java
// ChatServiceImpl 中 Agent 分支
if (modeStrategy.isAgentMode()) {
    ChatResponse response = spec.chatResponse();
    Map<String, Object> agentMeta = Map.of(
        "agentTrace", workspace.exportTrace(),
        "agentIntent", intent.name(),
        "retrievedDocs", workspace.getRetrievedDocuments(),
        "selfEvaluation", workspace.getSelfEvaluation()
    );
    return new AgentChatResponse(response, agentMeta);
}
```
