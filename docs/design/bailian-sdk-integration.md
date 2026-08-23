# 百炼 SDK 接入设计（dashscope-sdk-java 适配到自研 LLM SPI）

> **目标**：将 bailian provider 的三能力客户端从手写 HTTP（GenericChatClient OpenAI 兼容 / BailianEmbeddingClient / BailianRerankClient DashScope 原生裸 HTTP）迁移到**阿里云百炼官方 Java SDK**（`com.alibaba:dashscope-sdk-java`），消除自维护协议代码，获得官方跟进的协议演进（thinking / Qwen3 特性 / 错误码 / 限流语义）。
>
> **定位**：LLM 基础设施层的**客户端实现替换**——SPI 契约（`ChatCapable` / `EmbeddingCapable` / `RerankCapable`）、弹性装饰栈（Resilient\*）、用量计量（UsageRecording\*）、registry/candidate 配置面**全部不动**，只替换 provider 工厂产出的底层客户端。
>
> **核心决策**：
> 1. **SDK 适配到 SPI，不引入 Spring AI Alibaba starter**——starter 的自动装配与本项目"Spring AI 只作类型层、供应商访问全走自研 SPI"的架构决策（spec §5.5 Adapter 模式）直接冲突，且绕过弹性/计量装饰栈。
> 2. **CHAT 纳入 provider 工厂分流**——现状 `ChatCapabilityStrategy` 无工厂感知（一律 GenericChatClient），本次补齐为与 EMBEDDING/RERANKING 相同的 `AbstractProviderFactoryAwareStrategy` 模式。
> 3. **chat 侧协议从"百炼的 OpenAI 兼容层"切到"DashScope 原生协议"**（SDK 封装），工具调用/流式/usage 映射在适配器内完成。
> 4. **response_format 不依赖 SDK 解决**——评估模块 JSON 输出问题按 [GenericChatClient per-candidate params 注入] 方案独立解决（已另行讨论），SDK 迁移不阻塞也不包含该项。
> 5. **分期能回退**——每期能力独立验收，工厂层保留旧客户端类，provider 配置一开关可切回。
>
> **范围**：`infrastructure/llm/client/bailian/*`、`strategy/ChatCapabilityStrategy`（补工厂感知）、`strategy/provider/*`（新增 BailianChatClientFactory）、pom 依赖。**不含**：非 bailian provider、registry/BYOK/计量架构、Spring AI 类型层。
>
> **状态**：设计稿，待评审立项。

---

## 1. 背景与现状（已核验代码事实）

### 1.1 协议现状：chat-completions，非 Responses API

OpenAI 当前有两套对话协议，本项目用的是前者：

| 维度 | Chat Completions API | Responses API | 本项目 |
|---|---|---|---|
| 路径 | `/v1/chat/completions` | `/v1/responses` | `app.llm.providers.<id>.endpoints.chat` 默认 `/v1/chat/completions`（`EndpointConfig.java:11`） |
| 请求形状 | `{model, messages[], stream, tools, response_format…}` | `{model, input, instructions, previous_response_id, built-in tools…}` | `GenericChatClient.buildRequestBody` 构造的正是 completions 形状（messages/stream_options/tools/temperature…），无 `input`/`instructions`/`previous_response_id` |
| 状态管理 | 无状态，历史由客户端回传 | 服务端可托管会话状态 | 无状态（历史走 `ChatRequest.history` 回传） |
| 生态 | 全行业 OpenAI 兼容事实标准（DeepSeek/百炼/vLLM 均兼容） | OpenAI 专属演进方向（内置工具、reasoning items） | 依赖跨厂商兼容性，**只能选 completions** |

结论：`GenericChatClient` 是 chat-completions 协议实现；Responses API 不在（也不应）在本项目通用层引入——它是 OpenAI 专属，跨厂商通用客户端引入会破坏 provider 无关性。若未来某 provider 需要 Responses API 特性，走 provider 工厂分流（同本设计的扩展模式）。

### 1.2 三能力客户端矩阵

| 能力 | bailian provider | 其他 provider | 分流机制 |
|---|---|---|---|
| CHAT | `GenericChatClient`（百炼 OpenAI 兼容端点，dashscope.aliyuncs.com） | 同左 | **无分流**（`ChatCapabilityStrategy` 未继承工厂基类） |
| EMBEDDING | `BailianEmbeddingClient`（DashScope 原生 HTTP：`{model, input:{texts}, parameters:{dimension,text_type,output_type,instruct}}`，MaaS 域名硬编码于工厂） | `GenericEmbeddingClient`（OpenAI 兼容 `/v1/embeddings`） | `BailianEmbeddingClientFactory`（providerId=bailian） |
| RERANKING | `BailianRerankClient`（DashScope 原生 HTTP：`{model, query, documents, top_n}`） | `GenericRerankClient`（Cohere 风格） | `BailianRerankClientFactory` |

手写 HTTP 客户端的维护成本：协议字段变更（如 text_type/instruct 语义）、流式 SSE 解析（ToolCallAccumulator 自维护工具流增量拼接）、错误码映射（HttpClientErrorHandler）、thinking 方言（ThinkingBodyResolver）——全部自行跟进厂商演进。

## 2. SDK 选型对比

| 方案 | 描述 | 结论 |
|---|---|---|
| **A. dashscope-sdk-java 适配 SPI（本设计）** | 官方 SDK（Maven `com.alibaba:dashscope-sdk-java`，当前 2.22.29，发版频繁）包一层实现 `ChatCapable`/`EmbeddingCapable`/`RerankCapable`，经既有 `ProviderClientFactory` 注入 | ✅ 保留全部架构资产（弹性/计量/registry），协议维护交还官方 |
| B. Spring AI Alibaba starter | `spring-ai-alibaba-dashscope`（1.1.x 系基于 Spring AI 1.1.x，与本项目 Spring AI 1.1.6 兼容）直接获得 DashScopeChatModel | ❌ 绕过自研 SPI：ChatModelAdapter/UsageRecording/Resilient 全部旁路或双轨；自动装配需排除；与"Spring AI 只作类型层"决策冲突。仅当整体重构 LLM 层时 reconsider |
| C. 维持自研 HTTP | 不引入 SDK | 短期可行（response_format 走 params 注入独立解决），长期持续承担协议跟进成本 |

## 3. 目标架构

```
app.llm.providers.bailian (YAML, 不变)
        │
LlmClientRegistry (不变)
        │  按 (providerId, capability) 查 CapabilityStrategy
        ▼
AbstractProviderFactoryAwareStrategy（CHAT 补齐加入此模式）
        │  providerId=bailian 命中工厂？
        ├── 是 → BailianChatClientFactory → SdkBailianChatClient（新， implements ChatCapable + ToolCallingCapable）
        │        BailianEmbeddingClientFactory → SdkBailianEmbeddingClient（替换 BailianEmbeddingClient）
        │        BailianRerankClientFactory → SdkBailianRerankClient（替换 BailianRerankClient）
        └── 否 → Generic\*Client（不变）
        │
        ▼ wrapWithResilience（不变：CircuitBreaker + Retry + Probe + Metrics）
        ▼ ChatModelAdapter / BailianSpringAiEmbeddingAdapter（不变：Spring AI 类型桥）
```

关键点：**SDK 客户端只替换最底层的 HTTP 实现**，上层装饰与桥接零改动——这是 ProviderClientFactory 扩展点存在的设计初衷（EMBEDDING/RERANKING 已验证该模式）。

## 4. 详细设计

### 4.1 依赖引入与冲突排查（P0 先行验证）

```xml
<dependency>
    <groupId>com.alibaba</groupId>
    <artifactId>dashscope-sdk-java</artifactId>
    <version>2.22.29</version>
</dependency>
```

已知风险项（P0 用 `mvn dependency:tree` 逐项核对）：
- **OkHttp 版本**：SDK 依赖 okhttp3（3.x/4.x 系），本项目锁定 OkHttp **5.4.0**（okhttp-jvm，pom 已注释：覆盖 Boot BOM 避免 Kotlin stdlib 降级）。okhttp3 4.x 与 5.x 的二进制兼容性（包名相同、Kotlin 元数据差异）需实测；冲突则 exclude SDK 的 okhttp 让其跑在本项目 5.4.0 上，或反之降级评估。
- **reactor-core**：`ChatCapable.chatStream` 返回 `Flux<StreamChunk>`，项目已有 reactor（SDK 也依赖），版本仲裁以 Boot BOM 为准。
- **jackson**：SDK 自带 jackson 版本与 Boot 管理版本的仲裁。
- SDK 传递的其他依赖（如 `org.slf4j` 桥接、commons）常规核对。

### 4.2 CHAT：SdkBailianChatClient

实现 `ChatCapable` + `ToolCallingCapable`，内部委托 SDK：

| SPI 方法 | SDK 映射 |
|---|---|
| `chat(ChatRequest)` | `Generation.call(GenerationParam)`（阻塞，Qwen 文本生成） |
| `chatStream(ChatRequest)` | `Generation.stream(GenerationParam)` → `Flux<ChatResponse>` 逐 chunk 映射 `StreamChunk`（text / toolDelta / finishReason / usage）——**替换现 ToolCallAccumulator 的自维护 SSE 解析** |
| `chatWithTools(request, tools)` | `GenerationParam.toolCalls(...)`（SDK 原生工具调用），工具定义从 `ChatTool.inputSchemaJson()` 转 SDK `ToolParam` |
| `supportsStreaming()` | 仍由 `ModelCandidate.supportsStreaming` 声明（不变） |

**参数映射表**（`ChatRequest` → `GenerationParam.builder()`）：

| ChatRequest 字段 | GenerationParam |
|---|---|
| `input` / `systemPrompt` / `history` | `messages(List<Message>)`，system → `Message.SYSTEM_ROLE` |
| `temperature` / `maxTokens` / `topP` | `temperature` / `maxTokens` / `topP` |
| `thinking`（ThinkingConfig） | `enableThinking(boolean)`（SDK 原生，**替代 ThinkingBodyResolver 的百炼方言分支**——方言 resolver 保留服务 Generic 路径） |
| `extraParams` | 透传 SDK 支持的扩展（如 `responseFormat` 若 SDK 暴露） |

**usage 映射**：SDK `ChatResponse.usage`（input_tokens/output_tokens/total_tokens）→ `LlmResponse.TokenUsage(promptTokens, completionTokens, totalTokens, …)`——UsageRecordingChatModel 计量链路零改动。

**流式 usage 终止块**：SDK 流末 usage 与本项目 `stream_options.include_usage` 语义对齐，在最后一个 chunk 带 usage 收口。

**错误映射**：SDK `ApiException`/`NoApiKeyException` 等 → 既有 `RemoteException(RemoteErrorCode…)` 分类（对齐 `HttpClientErrorHandler` 现有映射：4xx 限流/参数、5xx 远端故障），保证 CircuitBreaker/Retry 语义不变。

### 4.3 CHAT 策略补工厂感知

`ChatCapabilityStrategy` 改为 `extends AbstractProviderFactoryAwareStrategy`（与 Embedding/Rerank 同构）：
- `createGenericClient` 返回现 `GenericChatClient`（非 bailian provider 行为不变）
- `BailianChatClientFactory implements ProviderClientFactory`（providerId=bailian, capability=CHAT）产出 `SdkBailianChatClient`
- **影响面**：`ChatCapabilityStrategy` 是唯一改动的主干类，GitNexus impact 需在实施前跑（预期 LOW：调用方只有 CapabilityStrategyRegistry）

### 4.4 EMBEDDING / RERANKING 迁移

| 现实现 | SDK 替换 | 保真点 |
|---|---|---|
| `BailianEmbeddingClient`（HTTP + parameters{dimension,text_type,output_type,instruct} + MaaS 域名硬编码） | SDK `TextEmbedding.call(TextEmbeddingParam)`：`textType(TEXT_TYPE_DOCUMENT/QUERY)`、`dimension`、`instruct`、`outputFormat`（对齐 output_type=dense） | `EmbeddingCapable` 契约不变；MaaS workspace 域名经 SDK 的 baseURI 覆盖配置注入（**顺带消除工厂里的硬编码**，workspaceId 落 YAML） |
| `BailianRerankClient`（HTTP {model,query,documents,top_n}） | SDK rerank API（`TextReRank`/对应类，P1 实施时按 SDK 版本核实类名） | `RerankCapable` 契约不变 |

`BailianSpringAiEmbeddingAdapter`（Spring AI EmbeddingModel 桥）**不动**——它桥接的是 `EmbeddingCapable`，底层客户端替换对它透明；仅泛化其构造参数类型（`BailianEmbeddingClient` → `EmbeddingCapable`）以解除对具体类的引用。

### 4.5 配置面

```yaml
app:
  llm:
    providers:
      bailian:
        url: https://dashscope.aliyuncs.com        # SDK baseURI 覆盖用
        api-key: ${DASHSCOPE_API_KEY}              # 传 SDK 构造（registry 现有取值路径不变）
        endpoints: {}                               # bailian 三能力全走 SDK 后 endpoints 不再必需（SDK 内置路径）
        # 可选 workspace 模式：embedding MaaS 域名（原硬编码）
        # embedding-base-uri: https://llm-xxxx.maas.aliyuncs.com
```

BYOK 解密路径（ApiKeyCipher）不变——apiKey 在工厂创建客户端时已是明文，与现 Bailian\*Client 构造同位。

### 4.6 测试策略

- **单元**：Mockito 桩 SDK 入口（Generation/TextEmbedding），断言参数映射（messages/thinking/toolCalls/text_type）与响应映射（usage/toolDelta/错误分类）——延续评估模块"罐头响应"测试风格
- **契约回归**：现有 ResilientChatClient/ChatModelAdapter 测试不动（桩在 SPI 层），证明装饰栈无感
- **联调冒烟**（手动/可选 profile）：chat 阻塞+流式、embedding 维度一致性（迁移前后同文本向量余弦=1 或按厂商文档预期）、rerank 排序稳定性

## 5. 分期实施

| 期 | 内容 | 验收 |
|---|---|---|
| **P0 依赖探测** | 引入 SDK、dependency:tree 冲突核对、OkHttp 5.x 共存验证、一个最小 main 冒烟 | 编译通过、无仲裁冲突、单次调用通 |
| **P1 CHAT SDK 化** | `ChatCapabilityStrategy` 工厂感知 + `BailianChatClientFactory` + `SdkBailianChatClient`（含流式/工具/usage/错误映射）+ 单测 | chat/agent/评估全链路切 bailian 回归；开关切回 Generic 验证回退 |
| **P2 EMBEDDING/RERANK** | 两个 Sdk 客户端替换 + adapter 泛化 + MaaS 域名配置化 | 向量化健康检查绿；rerank 链路回归 |
| **P3 清理** | 删 `BailianEmbeddingClient`/`BailianRerankClient` 手写实现与 `ToolCallAccumulator` 中百炼专属分支；spec 更新 | 全量 `mvn test` 绿；detect_changes 影响面在 llm 模块内 |

## 6. 风险与回滚

| 风险 | 缓解 |
|---|---|
| SDK 依赖与 OkHttp 5.4.0/jackson 冲突 | P0 前置探测；不可共存则本设计暂停重估（方案 C 兜底） |
| SDK 流式 chunk 语义与现 StreamChunk 映射偏差（工具增量拼接） | P1 单测覆盖 toolDelta 序列；联调用 agent 工具调用链路实测 |
| 双层重试/超时叠加（SDK 内置 retry vs Resilient RetryPolicy） | SDK 客户端构造时显式关闭其内置重试（或配置为直通），重试语义唯一归 Resilient 层 |
| SDK 版本迭代快（2.x 发版频繁） | 锁定版本 + 升级独立任务；工厂层隔离使升级影响面限于 SdkBailian\* 三类 |
| 回滚 | 每期独立：删对应 Factory 的 @Component（或 provider 配置切非 bailian）即回 Generic/旧客户端路径 |

---

**附：参考资料**
- [dashscope-sdk-java（Maven Central，当前 2.22.29）](https://central.sonatype.com/artifact/com.alibaba/dashscope-sdk-java)
- [dashscope-sdk-java 源码仓库](https://github.com/dashscope/dashscope-sdk-java)
- [阿里云百炼 SDK 安装文档](https://help.aliyun.com/zh/model-studio/install-sdk)
- [Spring AI Alibaba DashScope 接入文档（方案 B 参考，已否决）](https://java2ai.com/integration/chatmodels/dashScope)
- [Spring AI Alibaba 版本说明（Spring AI 1.1.x 对应 1.1.x 系）](https://java2ai.com/docs/versions)
