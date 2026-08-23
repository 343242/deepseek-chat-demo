# 百炼 SDK 接入设计（dashscope-sdk-java 适配到自研 LLM SPI）

> **目标**：将 bailian provider 的客户端从手写 HTTP 迁移到**阿里云百炼官方 Java SDK**（`com.alibaba:dashscope-sdk-java`），消除自维护协议代码，获得官方跟进的协议演进（thinking / Qwen3 特性 / 错误码 / 限流语义）。
>
> **定位**：LLM 基础设施层的**客户端实现替换**——SPI 契约（`ChatCapable` / `EmbeddingCapable` / `RerankCapable`）、弹性装饰栈（Resilient\*）、用量计量（UsageRecording\*）、registry/candidate 配置面**全部不动**，只替换 provider 工厂产出的底层客户端。
>
> **核心决策**：
> 1. **SDK 适配到 SPI，不引入 Spring AI Alibaba starter**——starter 的自动装配与本项目"Spring AI 只作类型层、供应商访问全走自研 SPI"的架构决策（spec §5.5 Adapter 模式）直接冲突，且绕过弹性/计量装饰栈。
> 2. **CHAT 纳入 provider 工厂分流**——现状 `ChatCapabilityStrategy` 无工厂感知（一律 GenericChatClient），本次补齐为与 EMBEDDING/RERANKING 相同的 `AbstractProviderFactoryAwareStrategy` 模式。
> 3. **CHAT 迁移 SDK（DashScope 原生协议）**，SDK facade 按模型族分流（`Generation` 纯文本路由 vs `MultiModalConversation` 多模态路由，见 §4.2.4）——dev 环境 bailian chat 模型（qwen3.7-plus/qwen3.8-max）官方归类**多模态路由**。
> 4. **EMBEDDING 迁移 SDK（DashScope 原生协议，路径与现状一致）**——现手写客户端本就打原生路由，SDK 化零协议切换风险；工厂域名硬编码同步消除。
> 5. **RERANK 不迁移 SDK（官方文档 + 项目内实证双重确证）**——项目模型 `qwen3-rerank` 的官方推荐路径就是现用的 OpenAI 兼容端点 `/compatible-api/v1/reranks`；SDK `TextReRank` 封装的原生路由仅服务 `gte-rerank-v2`/`qwen3-vl-rerank`。dev 配置注释已实证：completions 风格顶层格式打原生路由会 400（`Field required: input.query & input.documents`）。rerank 保留手写客户端（现状即官方推荐姿势），仅消除工厂域名硬编码。
> 6. **response_format 双路径覆盖**——评估模块 JSON 输出修复按 [GenericChatClient per-candidate params 注入] 方案独立解决；但 SDK 化会把 bailian chat 移出 GenericChatClient，params 注入覆盖不到 SDK 路径，故 `BailianChatClient` 必须同步映射 `responseFormat`（SDK 已暴露），两路互补。
> 7. **OkHttp 项目侧回退对齐**——项目从 `okhttp-jvm 5.4.0` 切回 `okhttp 4.12.0` 经典构件，与 SDK 传递的 okhttp/okhttp-sse/logging-interceptor 4.12.0 + okio 3.6.0 **同版对齐**，消除 duplicate-class 与二进制兼容风险（见 §4.1）。
> 8. **SDK baseUrl 覆盖只用 per-facade 构造器**——官方提供构造器传 baseUrl（实例级）与 `Constants.baseHttpApiUrl`（JVM 全局静态）两种方式。本项目多客户端多域名共存，**禁用全局常量方式**（互相污染），一律构造器注入；baseUrl 格式为带 `/api/v1` 后缀的完整前缀（官方 Java 示例：`https://{WorkspaceId}.cn-beijing.maas.aliyuncs.com/api/v1`）。
> 9. **分期能回退**——每期能力独立验收：CHAT 经工厂守卫（§4.3）可回落 GenericChatClient；EMBEDDING **同名全量替换**手写实现（无过渡共存类），回滚 = revert 单期 commit。
>
> **范围**：`infrastructure/llm/client/bailian/*`、`strategy/ChatCapabilityStrategy`（补工厂感知）、`strategy/provider/*`（新增 BailianChatClientFactory）、pom 依赖（SDK 引入 + okhttp 回退）。**不含**：非 bailian provider、registry/BYOK/计量架构、Spring AI 类型层、**rerank 客户端替换（见决策 5）**。
>
> **状态**：**已实施**（2026-08-23，P0–P3 全部完成）。v2 订正代码库与 SDK POM 事实；v3 叠加官方文档核验（rerank 决策反转）；v4 订正双 profile 真实配置（embedding 模型、chat 路由、域名矩阵——依据未跟踪的 `application-dev.yml`，其为默认激活 profile）；v5 定名并取消过渡共存。P0 实测结论见下节与任务 research。

> **P0 实测结论（2026-08-23，详见 `.trellis/tasks/08-23-bailian-sdk-integration/research/p0-probe-results.md`）**：
> ① dev chat 现状**确证为坏**（completions 形状 × multimodal 原生路由 → 400 `Field required: input`），SDK 化即修复；
> ② §4.2.4 **策略 a 否决、采策略 b**——qwen3.7-plus/qwen3.8-max 仅受 multimodal 路由服务（text 路由 400 `url error`，双域名皆拒；SDK Models 常量收录 ≠ 路由可用），qwen3-max 仅受 text 路由服务；workspace 域名双路由均可服务；
> ③ embedding 冒烟：qwen3.7-text-embedding / text-embedding-v4 ✓（1536 维）；`Qwen/Qwen3-Embedding-8B` 404（与手写现状一致，切候选列后续运维任务）；
> ④ SDK HTTP 路径**无内置重试**（仅 WebSocket 有），无双层重试叠加风险；
> ⑤ 流式 delta 形状：content=`[{text=增量}]`、tool_calls 为 OpenAI index 语义分片（尾片 finish=tool_calls 且 toolCalls 空）、usage 逐 chunk 携带累积值；显式 `incrementalOutput(true)` 即透传增量；
> ⑥ 新增传递链发现：`opendataloader-pdf-core:2.5.0` 传递 `okhttp-jvm:5.4.0`，需 exclusion（原设计未预见，P0 已处理）。

> **修订记录（v4 → v5，命名定稿 + 实施粒度）**：
> ① **客户端命名去 Sdk 前缀**：`SdkBailianChatClient` → `BailianChatClient`（与 `BailianChatClientFactory` / `BailianRerankClient` 命名一致，代码库无同名冲突）；
> ② **EMBEDDING 同名全量替换，取消过渡共存**：不再引入 `SdkBailianEmbeddingClient` 过渡类——P2 单 commit 将 `BailianEmbeddingClient` 全量重写为 SDK 实现并删除手写实现；P3 不再含删旧项；决策 9 / 回滚表同步改写（EMBEDDING 回滚 = revert P2 commit）；
> ③ **§4.2.3 明确 accumulator 全新实现**：旧 `ToolCallAccumulator` 为包私有 + OpenAI index 语义，不可复用，新 accumulator 按 DashScope delta 形状新写；
> ④ **§4.3 补基类 Javadoc 同步订正点**：「Chat 不使用此基类」句随 P1 失效删除。

> **修订记录（v3 → v4，双 profile 配置核验）**：
> ① **embedding 模型矩阵订正**：v3 误将 stable 的 `Qwen/Qwen3-Embedding-8B` 当作项目模型。实际双 profile：**dev**（`application-dev.yml`，未跟踪、默认激活——`application.yml` 默认 `SPRING_PROFILES_ACTIVE:dev`）用 `qwen3.7-text-embedding`（默认候选，dimension 1536、`params.batch-size: 20`）+ `text-embedding-v4`（备选，1536，无 batch-size 声明→默认 10）；**stable** 用 `Qwen/Qwen3-Embedding-8B`（1536）。dev 两模型均为官方模型表内一等公民（SDK 参数全支持），v3 的"ModelScope 风格命名冒烟风险"仅剩 stable 一例；
> ② **chat 现状矩阵订正**：dev bailian chat 并非兼容层——`provider.url` 即 workspace 专属域名，`endpoints.chat=/api/v1/services/aigc/multimodal-generation/generation`（**原生多模态路由**），候选 `qwen3.7-plus`/`qwen3.8-max`（官方将 qwen3.7-plus 归类多模态路由模型）。stable 才是共享域名兼容层（qwen3-max/qwen-plus-latest）。§1.2 矩阵按 profile 拆分；
> ③ **新增 dev chat 形状疑点**：`GenericChatClient.buildRequestBody` 为纯 completions 形状（顶层 `messages`/`stream_options`/`tools`，无原生分支），而 dev 将其指向原生路由——原生路由要求 `input.messages` 嵌套（与 rerank 注释实证的 400 同类）。该组合当前是否可用未经证实，列 **P0 首项实测**；若现状即坏，SDK 化顺带修复（SDK 负责原生形状）；
> ④ **SDK facade 路由问题新增（§4.2.4）**：`Generation` 封装 `text-generation/generation`（纯文本模型），`MultiModalConversation` 封装 `multimodal-generation/generation`（qwen3.7-plus 等多模态路由模型，官方 workspace 域名示例用的正是它）——`BailianChatClient` 需按模型族选 facade，或 P0 实测 Generation 路由对 qwen3.7-plus 的可用性；
> ⑤ **rerank 决策获项目内实证**：dev 配置注释明确记录原生路由对顶层格式 400（`Field required: input.query & input.documents`）且响应解析需兼容顶层 `results` 与 `output.results`——与 v3 官方文档结论互证；
> ⑥ **配置面订正**：dev 已在 `provider.url` + `endpoints.{chat,embedding,rerank}` 完整声明三能力（同一 workspace 域名）；工厂域名硬编码的消除方式从"新增 embedding-base-uri/rerank-base-uri"改为"读 provider.url + endpoints.*（dev 现状即目标形状，stable 补声明）"。
>
> **修订记录（v2 → v3，官方文档核验）**：① rerank 结论反转（qwen3-rerank 官方推荐路径即兼容端点，原生路由仅 gte-rerank-v2/qwen3-vl-rerank）；② MaaS 域名正名为业务空间专属域名（官方推荐生产域名）；③ baseUrl 覆盖官方确证（构造器 vs 全局静态，须用前者）；④ workspace 域名 Key 归属约束（401）；⑤ embedding 分批官方同构（10 条切片 + text_index 重排归调用方）；⑥ gte-rerank 已下线（项目未用）。
>
> **修订记录（v1 → v2）**：① SDK 依赖清单订正（RxJava2/gson/okhttp 4.12，非 reactor-core/jackson）；② OkHttp 解法改为项目回退 4.12.0 同版对齐；③ 流式 API 订正为 `streamCall → Flowable`；④ rerank 现状订正为兼容端点；⑤ 补 reasoningContent 映射；⑥ 明确轮末汇总包契约复刻；⑦ 补 history 工具消息回传映射；⑧ 补 BYOK 守卫；⑨ 补 embedding 并发批处理重建；⑩ response_format 双路径；⑪ 勘误 EndpointConfig 无默认路径。

---

## 1. 背景与现状（已核验代码 + 官方文档 + 双 profile 配置）

### 1.1 协议现状：chat-completions，非 Responses API

OpenAI 当前有两套对话协议，本项目用的是前者：

| 维度 | Chat Completions API | Responses API | 本项目 |
|---|---|---|---|
| 路径 | `/v1/chat/completions` | `/v1/responses` | `app.llm.providers.<id>.endpoints.chat`（`EndpointConfig` 无默认值，路径完全由 profile YAML 提供） |
| 请求形状 | `{model, messages[], stream, tools, response_format…}` | `{model, input, instructions, previous_response_id…}` | `GenericChatClient.buildRequestBody` 构造的正是 completions 形状（顶层 messages/stream_options/tools/temperature…），无 input/instructions |
| 状态管理 | 无状态，历史由客户端回传 | 服务端可托管会话状态 | 无状态（历史走 `ChatRequest.history` 回传） |
| 生态 | 全行业 OpenAI 兼容事实标准 | OpenAI 专属演进方向 | 依赖跨厂商兼容性，**通用层只能选 completions** |

结论：`GenericChatClient` 是 chat-completions 协议实现，服务 deepseek/zhipu/minimax 等兼容端点 provider；Responses API 不在通用层引入。若某 provider 需要 Responses API 特性，走 provider 工厂分流（同本设计扩展模式）。**注意**：dev 环境 bailian 的 `endpoints.chat` 指向 DashScope 原生路由，与 completions 形状存在错配疑点（§1.2 注 3，P0 实测）。

### 1.2 三能力客户端矩阵（双 profile 对照）

| 能力 | dev profile（默认激活） | stable profile | 分流机制 |
|---|---|---|---|
| CHAT | 候选 `qwen3.7-plus`（priority 2）/ `qwen3.8-max`（thinking，priority 4），默认 chat 为 deepseek-v4-flash；bailian `url=llm-l3buonxbvhgk4qiy.cn-beijing.maas.aliyuncs.com`（workspace 域名）+ `endpoints.chat=/api/v1/services/aigc/multimodal-generation/generation`（**原生多模态路由**） | 候选 `qwen3-max`（thinking budget 16000，默认）/ `qwen-plus-latest`；`url=dashscope.aliyuncs.com/compatible-mode/v1` + `endpoints.chat=/chat/completions`（**共享域名兼容层**） | **无分流**（`ChatCapabilityStrategy` 未继承工厂基类）——P1 补齐 |
| EMBEDDING | `qwen3.7-text-embedding`（默认，dimension 1536，`params.batch-size: 20`——官方批次上限 20）+ `text-embedding-v4`（备选，1536，批次 10）；endpoint `/api/v1/services/embeddings/text-embedding/text-embedding`（原生路由） | `Qwen/Qwen3-Embedding-8B`（开源系命名，1536）；endpoint 未声明（工厂硬编码 workspace 域名 + 原生路由） | `BailianEmbeddingClientFactory`（providerId=bailian） |
| RERANKING | `qwen3-rerank`；endpoint `/compatible-api/v1/reranks`（**OpenAI 兼容端点**，workspace 域名） | 同左（endpoint 未声明，工厂硬编码） | `BailianRerankClientFactory` |

注：
1. dev 的 `application-dev.yml` 未被 git 跟踪（含真实密钥，文件头注明禁止上传）——本设计引用其配置形状，不复制密钥值。
2. dev 的 rerank endpoint 注释是项目内一手实证：**qwen3-rerank 必须走兼容端点**（顶层 `model/query/documents/top_n`），原生路由要求 `{input:{query,documents}}`，顶层格式 400（`Field required: input.query & input.documents`）；响应解析需兼容顶层 `results` 与 `output.results` 两种形状。
3. dev chat 疑点：`GenericChatClient` 发 completions 形状（顶层 `messages`），而 dev 将其指向原生 multimodal 路由（要求 `input.messages` 嵌套 + 多模态 content 数组）。dev 默认 chat 是 deepseek（bailian 候选非首选），该组合是否被实际触发并成功未经证实——P0 首项实测。若 400，说明 dev bailian chat 现状已坏，SDK 化是修复而非迁移。

**关键流式契约（SDK 化必须保真）**：`ChatModelAdapter` 的流式 ReAct 依赖「**轮末汇总包**」语义——每个工具轮的最后一个 `StreamChunk` 携带**完整合并后的 toolCalls + finishReason + usage + 累计 reasoningContent**。该契约由 `GenericChatClient.readSse` + `ToolCallAccumulator`（增量拼接 + 轮末收口）生产。SDK 替换后必须在 `BailianChatClient` 内复刻（见 §4.2.3）。

### 1.3 域名体系（官方《Base URL》文档核验）

| 域名 | 性质 | API Key 约束 | 本项目使用 |
|---|---|---|---|
| `dashscope.aliyuncs.com` | legacy 共享域名，仍可用 | 同区域任意 workspace 的 Key | stable：chat 兼容层 |
| `{WorkspaceId}.{region}.maas.aliyuncs.com` | **业务空间专属域名，官方推荐生产域名**（更高吞吐/更低延迟/workspace 级流量隔离） | **Key 必须属于该 workspace**（不匹配 401） | dev：chat/embedding/rerank 三能力全走此域名（`llm-l3buonxbvhgk4qiy`）；stable：embedding/rerank（工厂硬编码） |

原生 DashScope API = 域名 + `/api/v1`（两域名均支持）；OpenAI 兼容 = `/compatible-mode/v1`；qwen3-rerank 兼容端点 = `/compatible-api/v1/reranks`。项目 dev 的 `DASHSCOPE_API_KEY` 已通过 workspace 域名调三能力，Key 归属满足。

## 2. SDK 选型对比

| 方案 | 描述 | 结论 |
|---|---|---|
| **A. dashscope-sdk-java 适配 SPI（本设计）** | 官方 SDK（Maven `com.alibaba:dashscope-sdk-java`，当前 **2.22.30**，2026-08-14 发版）包一层实现 `ChatCapable`/`EmbeddingCapable`/`RerankCapable`，经既有 `ProviderClientFactory` 注入 | ✅ 保留全部架构资产（弹性/计量/registry），协议维护交还官方。**覆盖范围 = CHAT + EMBEDDING**（rerank 见决策 5） |
| B. Spring AI Alibaba starter | `spring-ai-alibaba-dashscope`（1.1.x 系，与 Spring AI 1.1.6 兼容）直接获得 DashScopeChatModel | ❌ 绕过自研 SPI；自动装配需排除；与"Spring AI 只作类型层"决策冲突 |
| C. 维持自研 HTTP | 不引入 SDK | 短期可行，长期持续承担协议跟进成本（dev chat 形状疑点即自维护协议的典型代价） |

## 3. 目标架构

```
app.llm.providers.bailian (YAML, 不变——dev 现状即目标形状)
        │
LlmClientRegistry (不变)
        │  按 (providerId, capability) 查 CapabilityStrategy
        ▼
AbstractProviderFactoryAwareStrategy（CHAT 补齐加入此模式）
        │  providerId=bailian 命中工厂？
        ├── 是 → BailianChatClientFactory → BailianChatClient（新， implements ChatCapable + ToolCallingCapable）
        │        BailianEmbeddingClientFactory → BailianEmbeddingClient（SDK 实现，同名全量替换手写实现）
        │        BailianRerankClientFactory → BailianRerankClient（保留，仅域名来源改配置）
        └── 否 → Generic*Client（不变）
        │
        ▼ wrapWithResilience（不变：CircuitBreaker + Retry + Probe + Metrics）
        ▼ ChatModelAdapter / BailianSpringAiEmbeddingAdapter（不变：Spring AI 类型桥）
```

关键点：**SDK 客户端只替换最底层的 HTTP 实现**，上层装饰与桥接零改动——ProviderClientFactory 扩展点已由 EMBEDDING/RERANKING 验证。透明性成立的前提是 §4.2.3 轮末汇总包契约复刻。

## 4. 详细设计

### 4.1 依赖引入与 OkHttp 对齐（P0 先行验证）

#### 4.1.1 SDK 真实依赖面（2.22.30 POM 已核验）

```xml
<dependency>
    <groupId>com.alibaba</groupId>
    <artifactId>dashscope-sdk-java</artifactId>
    <version>2.22.30</version>
</dependency>
```

| 依赖 | 版本 | 与本项目关系 |
|---|---|---|
| `okhttp` / `okhttp-sse` / `logging-interceptor` | 4.12.0 | 项目回退 4.12.0 后**同版对齐**，零 exclude |
| `okio` | 3.6.0 | 随 okhttp 对齐；项目已直接用 `okio.BufferedSource` |
| `kotlin-stdlib-jdk8` | 1.8.21 | 项目 `kotlin.version=2.1.21` 覆盖 Boot BOM（1.9.25）后仲裁至 2.1.21；stdlib 二进制前向兼容 |
| **RxJava 2** | 2.2.21 | SDK 流式 API 返回 `Flowable`；与项目 reactor-core 无坐标交叉 |
| gson | 2.8.9 | SDK 内部序列化；与项目 jackson 无坐标交叉 |
| victools jsonschema-generator | 4.31.1 | 其传递 jackson 由 Boot BOM 仲裁（P0 dependency:tree 确认） |
| lombok | provided | 不参与运行时 |

SDK 为 Java 8 target，本项目 Java 21，无兼容问题。

#### 4.1.2 OkHttp 回退对齐

**现状**：pom 以自有属性 `okhttp.version=5.4.0` 直接依赖 `okhttp-jvm`（KMP 变体）；`kotlin.version=2.1.21` 因 okhttp 5.4 需要而覆盖 Boot BOM 的 1.9.25。Boot 3.5.14 BOM **不管理 okhttp/okio**（已核验零命中），无 BOM 对抗。

**回退变更**：

1. 依赖声明 `okhttp-jvm` → `okhttp`（经典构件），版本 `4.12.0`；pom 注释同步改写（删除"KMP 空壳"说明，改为"与 dashscope-sdk-java 传递的 okhttp 4.12.0 同版对齐"）。
2. `kotlin.version=2.1.21` **保留不动**：okhttp 4.12 仅要求 stdlib ≥1.8.21，此 pin 同时把 SDK 传递的 1.8.21 仲裁到 2.1.21。
3. MinIO 9.0.0 的 okhttp exclusion **保持**——回退后与 MinIO 编译基线（4.x）更匹配。
4. SDK 依赖**零 exclusion**——classpath 无重复类。

**回退可行性（已核验）**：项目 okhttp API 面仅三处——`HttpClientFactory`（Builder 超时 + 单例缓存 + `@PreDestroy`）、`GenericChatClient`（`Request.Builder`/`Call.execute()`/`body().source()`）、`GenericChatClientSseTest`（fake `okhttp3.Call` + `okio.Buffer`）。全部 4.x 稳定公共 API。

**残余风险**：5.4→4.12 是传输层全局降版（chat/embedding/rerank/MinIO 共用）——P0 全 HTTP 链路回归。

#### 4.1.3 P0 核对清单（按优先级）

1. **dev chat 形状实测（首项）**：现配置下 GenericChatClient（completions 形状）打 bailian 原生 multimodal 路由——确认当前是通是坏（§1.2 注 3 疑点）。结果记录进本设计，作为 P1 基线。
2. **SDK facade 路由实测**：`Generation.call`（text-generation 路由）与 `MultiModalConversation`（multimodal 路由）分别调 `qwen3.7-plus`/`qwen3.8-max`——确定 §4.2.4 的路由策略（按模型族分流 or Generation 通用）。
3. okhttp-jvm 5.4.0 → okhttp 4.12.0 回退 + 全 HTTP 链路回归（chat 阻塞+流式、embedding、rerank、MinIO）。
4. `mvn dependency:tree`：okhttp 系全树唯一 4.12.0、okio 唯一 3.6.0、kotlin-stdlib 唯一 2.1.21、jackson 仲裁归 Boot、RxJava2 仅来自 SDK。
5. SDK 最小 main 冒烟：chat 阻塞 + 流式（构造器传 baseUrl `https://llm-….maas.aliyuncs.com/api/v1` + workspace Key）；embedding `qwen3.7-text-embedding`（官方模型，预期通）与 stable 的 `Qwen/Qwen3-Embedding-8B`（开源系命名，非官方模型表内——冒烟定去留）。
6. SDK 内置 retry/超时行为探测（官方文档未明确开关，需源码/实测）。

### 4.2 CHAT：BailianChatClient

实现 `ChatCapable` + `ToolCallingCapable`，内部委托 SDK：

| SPI 方法 | SDK 映射 |
|---|---|
| `chat(ChatRequest)` | 阻塞调用（facade 按 §4.2.4 路由） |
| `chatStream(ChatRequest)` | `streamCall` → **RxJava2 `Flowable`**，薄桥接为 `Flux<StreamChunk>`（手写约 20 行：`Flux.create` + subscribe/dispose 透传，**不引入 reactor-adapter**），再经 §4.2.3 汇总逻辑收口 |
| `chatWithTools(request, tools)` | SDK 原生工具调用（`GenerationParam.toolCalls` 或 MultiModal 等价参数），工具定义从 `ChatTool.inputSchemaJson()` 转 SDK Tool 形状 |
| `supportsStreaming()` | 仍由 `ModelCandidate.supportsStreaming` 声明（不变） |

注：v1 稿写的 `Generation.stream → Flux` 不存在；实际入口为 `streamCall` 返回 RxJava2 `Flowable`。

**参数映射表**（`ChatRequest` → SDK 参数 builder）：

| ChatRequest 字段 | SDK 参数 |
|---|---|
| `input` / `systemPrompt` / `history` | messages，system → SYSTEM_ROLE；**history 中 assistant 的 tool_calls 元数据 → SDK assistant Message，tool 结果 → TOOL_ROLE**（见 §4.2.2） |
| `temperature` / `maxTokens` / `topP` | `temperature` / `maxTokens` / `topP` |
| `thinking`（ThinkingConfig） | `enableThinking(boolean)` + `thinkingBudget`（SDK 原生；dev qwen3.8-max / stable qwen3-max 的 dialect=budget 配置映射为 `enableThinking(true).thinkingBudget(…)`；ThinkingBodyResolver 保留服务 Generic 路径） |
| `extraParams` | 透传 SDK 支持的扩展；其中 **`response_format` → SDK responseFormat**（决策 6：评估 JSON 修复必须覆盖 SDK 路径） |
| `incrementalOutput` | 显式请求增量输出以对齐现 StreamChunk 增量语义 |
| `resultFormat` | 显式 `message`（官方推荐；部分模型默认 `text`，统一为 message 避免 content 解析分歧） |

**reasoningContent 映射（必补）**：`StreamChunk` 第 5 字段 `reasoningContent`；dev qwen3.8-max / stable qwen3-max 均为 thinking 候选，reasoning 流是主链路。SDK `GenerationResult` 暴露 reasoning 内容字段，逐 chunk 透传 + 轮末累计（与现行为一致：增量即时下发 + 轮末汇总包携带累计值）。

**usage 映射**：SDK usage（input_tokens/output_tokens/total_tokens）→ `LlmResponse.TokenUsage(promptTokens, completionTokens, totalTokens, …)`——UsageRecordingChatModel 计量链路零改动。缓存命中 token（百炼 `prompt_tokens_details.cached_tokens`）一并映射，保持 cacheHit 计量。

**流式 usage 终止块**：SDK 流末 usage 与 `stream_options.include_usage` 语义对齐，在轮末汇总包收口。

**错误映射**：SDK `ApiException`/`NoApiKeyException` → 既有 `RemoteException(RemoteErrorCode…)` 分类（对齐 `HttpClientErrorHandler`：4xx 限流/参数、5xx 远端故障），保证 CircuitBreaker/Retry 语义不变。workspace 域名 401（Key 归属不匹配）归入鉴权类错误。

#### 4.2.2 history 工具消息回传（新增适配工作）

现 `ChatModelAdapter` 回传的 history 中，assistant 工具轮含 OpenAI 兼容形状的 `tool_calls` 元数据，tool 结果为 `role=tool` 消息——SDK 原生协议要求转换为 SDK Message（assistant 携带 toolCalls 元数据 + TOOL_ROLE 携带 tool 结果）。适配器实现双向形状转换并纳入单测。

**利好**：现 GenericChatClient 在 tool_calls 分支注入 `reasoning_content` 回传是 **DeepSeek 兼容层 quirk**；Qwen 原生协议不需要，该分支不迁移（Generic 路径保留不动）。

#### 4.2.3 轮末汇总包契约复刻（accumulator 搬迁，非删除）

SDK 化后 SSE 解析消失，但**增量拼接与轮末收口逻辑仍需存在**——从"SSE 行流上的 accumulator"搬迁为"SDK Flowable 事件流上的 accumulator"（toolCall 增量按 id/index 拼接、reasoning 累计、流末组装完整 toolCalls + finishReason + usage 的终止 StreamChunk）。`ToolCallAccumulator` 类本体保留服务 Generic 路径（P3 仅删百炼专属分支）。**全新实现而非复用**：`ToolCallAccumulator` 是 `client.generic` 包的包私有类，且按 OpenAI 的 `index` 语义合并分片；DashScope SDK 流式 delta 形状不同，`BailianChatClient` 需在 bailian 包内**新写** accumulator（合并键按 SDK delta 形状定），勿复制旧类。

#### 4.2.4 SDK facade 路由（v4 新增，dev 模型族驱动）

官方将千问模型分两族、两路由（qwen-api-via-dashscope）：纯文本模型（如 qwen-plus）→ `/api/v1/services/aigc/text-generation/generation`（SDK `Generation`）；**多模态模型（如 qwen3.7-plus）→ `/api/v1/services/aigc/multimodal-generation/generation`（SDK `MultiModalConversation`，官方 workspace 域名示例用的正是它）**。本项目模型落点：dev `qwen3.7-plus`/`qwen3.8-max` 属多模态路由族；stable `qwen3-max`/`qwen-plus-latest` 属文本路由族。

`BailianChatClient` 两种实现策略，P0 实测后定：
- **策略 a（优先验证）**：统一用 `Generation`（text-generation 路由）调全部模型——若服务端对 qwen3.7-plus 等模型也接受 text-generation 路由（部分模型双路由可调），实现最简；
- **策略 b（保底）**：按模型族路由——candidate params 声明 `route: text|multimodal`（或工厂内模型名规则映射），text 族走 `Generation`，multimodal 族走 `MultiModalConversation`（纯文本用法：content 为 text 部分）。

多模态 facade 的代价：`MultiModalConversation` 的消息形状（content 数组）与流式事件结构与 `Generation` 不同，§4.2.1/§4.2.3 的映射要各写一份——策略 b 工作量约翻倍，故 P0 优先验证策略 a。

### 4.3 CHAT 策略补工厂感知 + BYOK 守卫

`ChatCapabilityStrategy` 改为 `extends AbstractProviderFactoryAwareStrategy`（与 Embedding/Rerank 同构）：
- `createGenericClient` 返回现 `GenericChatClient`（非 bailian provider 行为不变）
- `BailianChatClientFactory implements ProviderClientFactory`（providerId=bailian, capability=CHAT）产出 `BailianChatClient`
- **影响面**：`ChatCapabilityStrategy` 是唯一改动的主干类，GitNexus impact 已跑（LOW：仅 `CapabilityStrategyRegistry` 收集；chat 客户端真实消费方 `GenericOpenAiProvider.createClient` + `LlmClientFactory` 均经 strategy 接口）
- **基类 Javadoc 同步订正**：`AbstractProviderFactoryAwareStrategy` 的 Javadoc 现声明「ChatCapabilityStrategy 不使用此基类（chat 没有 ProviderClientFactory 扩展点）」——P1 补工厂感知后该句失效，随本改动同步删除

**BYOK/DB 路径 engagement 守卫**：DB 配置路径（V16 `llm_config`，`provider_code='bailian'`）与 YAML 路径共用同一 `CapabilityStrategyRegistry`——DB 行若携带自定义 baseUrl（私有网关/代理），今天由 `GenericChatClient` 以 OpenAI 兼容协议访问，SDK 化后若被 bailian 工厂命中会改走 DashScope 原生协议打该 URL → 静默打挂。工厂守卫：**仅当 baseUrl 为 dashscope.aliyuncs.com / \*.maas.aliyuncs.com 域（或显式配置 `sdk-client: true`）时产出 BailianChatClient，否则回落 GenericChatClient**。

### 4.4 EMBEDDING 迁移 / RERANK 保留

| 能力 | 决策 | 说明 |
|---|---|---|
| **EMBEDDING** | **迁移 SDK** | 现手写的正是 DashScope 原生协议（`{model, input:{texts}, parameters:{dimension,text_type,output_type,instruct}}` → `/api/v1/services/embeddings/text-embedding/text-embedding`），SDK `TextEmbedding.call(TextEmbeddingParam)` 封装同一路由——**协议零切换**。参数映射（官方 Java 示例风格）：`texts`、`parameter("dimension", 1536)`、`textType(TEXT_TYPE_DOCUMENT/QUERY)`、`outputType(DENSE)`、`instruct`（官方约束：instruct 须搭配 `text_type=query`——映射时保持现客户端的配套逻辑）。**模型覆盖**：dev `qwen3.7-text-embedding`（官方表内：2560~256 维、批次 20、单批 128k token）与 `text-embedding-v4`（64~2048 维、批次 10）SDK 均一等支持；stable `Qwen/Qwen3-Embedding-8B`（开源系命名，workspace 域名服务）P0 冒烟定去留 |
| **RERANK** | **保留手写客户端** | `qwen3-rerank` 的官方推荐路径**就是**兼容端点 `/compatible-api/v1/reranks`（扁平 `{model,query,documents,top_n,instruct}`，响应顶层 `results[{index,relevance_score}]`）；SDK `TextReRank` 封装的原生路由（嵌套 `{input,parameters}`）仅服务 `gte-rerank-v2`/`qwen3-vl-rerank`。**项目内实证**（dev 配置注释）：顶层格式打原生路由 400 `Field required: input.query & input.documents`；现客户端响应解析已兼容顶层 `results` 与 `output.results` 双形状。仅做：域名来源从工厂硬编码改为配置（§4.5）。`RerankCapable` 契约与 `ResilientRerankClient` 全部不动 |

**embedding 并发批处理重建（官方文档同构佐证）**：现 `BailianEmbeddingClient` 自研 ScopedTasks 并发分批——batchSize 取 candidate params（dev qwen3.7-text-embedding 声明 20，未声明默认 10）、`MAX_CONCURRENCY=4`、text_index 对位重排防御、零向量兜底。官方 Java SDK 示例**同样由调用方自行分批**（`DASHSCOPE_MAX_BATCH_SIZE` 切片 + `setTextIndex(batchCounter+…)` 重排 + usage 累加）——证实分批/对位归调用方。`BailianEmbeddingClient`（SDK 实现，同名全量替换手写实现）在 SDK 调用之上重建同等语义，P2 验收含大批量 embedBatch 一致性测试。

`BailianSpringAiEmbeddingAdapter`（Spring AI EmbeddingModel 桥）**不动**——它桥接的是 `EmbeddingCapable`，底层替换对它透明；仅泛化其构造参数类型（`BailianEmbeddingClient` → `EmbeddingCapable`）解除具体类引用。

### 4.5 配置面

dev 现状即目标形状（三能力同域名 + endpoint 全声明）；stable 对齐补声明：

```yaml
app:
  llm:
    providers:
      bailian:
        # dev 现状：workspace 专属域名三能力共用（官方推荐生产域名；Key 须属该 workspace）
        # stable 现状：dashscope.aliyuncs.com/compatible-mode/v1（chat 兼容层）——迁移后建议对齐 dev
        url: https://llm-xxxx.cn-beijing.maas.aliyuncs.com
        api-key: ${DASHSCOPE_API_KEY}
        endpoints:
          # Generic 回退路径（sdk 开关关闭时）使用；SDK 路径由 facade 内置路由 + baseUrl(=url+/api/v1) 决定
          chat: /api/v1/services/aigc/multimodal-generation/generation   # 或 text-generation/generation，按模型族
          embedding: /api/v1/services/embeddings/text-embedding/text-embedding
          rerank: /compatible-api/v1/reranks    # qwen3-rerank 专用兼容端点（原生路由会 400，见 §1.2 注 2）
        # sdk-client: true                      # BYOK 自定义 baseUrl 场景强制启用 SDK 客户端的显式开关（§4.3 守卫）
```

要点：
- **工厂域名硬编码消除方式**：embedding/rerank 工厂从硬编码 MaaS 域名改为读 `provider.url + endpoints.*`（dev 已完整声明；stable 补 `endpoints.embedding/rerank` 声明即可）。不再新增 `embedding-base-uri`/`rerank-base-uri` 独立键——v3 方案作废，避免同一域名三处声明。
- **SDK baseUrl 格式**：`= provider.url + "/api/v1"`（若 url 已带路径则取域名部分）；构造器注入，**禁用** `Constants.baseHttpApiUrl` 全局静态（决策 8）。
- **chat 域名**：SDK 化后 dev/stable 统一走原生路由，两域名均支持；stable 可继续共享域名（baseUrl=`https://dashscope.aliyuncs.com/api/v1`）或迁 workspace 域名（官方推荐，吞吐/延迟/隔离更优），P1 联调二选一。
- BYOK 解密路径（ApiKeyCipher）不变——apiKey 在工厂创建客户端时已是明文。
- dev 文件含真实密钥未跟踪，本设计只引用配置形状。

### 4.6 测试策略

- **单元**：Mockito 桩 SDK 入口（Generation / MultiModalConversation / TextEmbedding），断言参数映射（messages/thinking/toolCalls/text_type/instruct 配套/responseFormat/reasoningContent 透传/resultFormat=message）与响应映射（usage 含 cached_tokens/toolDelta/轮末汇总包完整性/错误分类）
- **流式专项**：toolCall 增量序列（Flowable 多 chunk → 合并 toolCalls）、reasoning 增量 + 轮末累计、流末 usage 收口——守护 §4.2.3 契约
- **facade 路由专项**（若采策略 b）：text/multimodal 两族模型的 facade 选择与消息形状转换
- **history 回传专项**：OpenAI 兼容形状 ↔ SDK Message 形状转换（tool_calls 元数据 / TOOL_ROLE）
- **契约回归**：现有 ResilientChatClient/ChatModelAdapter 测试不动（桩在 SPI 层），证明装饰栈无感
- **OkHttp 回退回归**：GenericChatClient 阻塞+流式、embedding/rerank（保留的手写客户端）、MinIO 链路在 okhttp 4.12.0 上全绿
- **联调冒烟**（手动/可选 profile）：dev chat 阻塞+流式（qwen3.7-plus/qwen3.8-max）、stable chat（qwen3-max）、embedding 维度一致性（迁移前后同文本向量余弦=1，含大批量并发分批，qwen3.7-text-embedding 与 text-embedding-v4 各一轮）、rerank 回归（实现未动，仅域名来源变更）

## 5. 分期实施

| 期 | 内容 | 验收 |
|---|---|---|
| **P0 依赖探测 + 现状实证** | dev chat 形状实测（completions × 原生路由，是通是坏）→ SDK facade 路由实测（qwen3.7-plus 走 Generation vs MultiModalConversation，定 §4.2.4 策略）→ okhttp-jvm 5.4.0 → okhttp 4.12.0 → 引入 SDK 2.22.30 → dependency:tree 核对 → 全 HTTP 链路回归 → SDK 最小冒烟（chat 阻塞+流式；embedding qwen3.7-text-embedding + Qwen/Qwen3-Embedding-8B 两模型）→ SDK retry 探测 | 两个实测结论明确（chat 现状、facade 路由策略）；编译通过、依赖树无冲突、回退后链路全绿、SDK 调用通 |
| **P1 CHAT SDK 化** | `ChatCapabilityStrategy` 工厂感知 + `BailianChatClientFactory`（含 BYOK 守卫）+ `BailianChatClient`（facade 路由 + Flowable→Flux 薄桥 + 轮末汇总包 accumulator + reasoning/usage 含 cached_tokens/toolDelta/responseFormat 映射 + history 工具消息转换 + 错误映射）+ 单测 | dev+stable chat 候选全链路回归（qwen3.7-plus/qwen3.8-max/qwen3-max）；agent 工具调用 + 评估链路；开关切回 Generic 验证回退 |
| **P2 EMBEDDING SDK 化 + 域名配置化** | `BailianEmbeddingClient` **SDK 化同名全量重写**（手写实现同 commit 删除，无过渡共存；含并发批处理重建 + 零向量兜底 + instruct/text_type 配套）+ adapter 泛化 + embedding/rerank 工厂域名来源硬编码 → `provider.url + endpoints.*`（stable 补 endpoint 声明；rerank 客户端实现不动） | 向量化健康检查绿；大批量 embedBatch 一致性通过（两模型）；rerank 链路回归绿；全量 `mvn test` 绿（旧类已删） |
| **P3 清理** | 删 `ToolCallAccumulator` 百炼专属分支（**accumulator 类本体与 `BailianRerankClient` 保留**——手写 `BailianEmbeddingClient` 已在 P2 同名替换时删除）；spec 更新 | 全量 `mvn test` 绿；detect_changes 影响面在 llm 模块内 |

## 6. 风险与回滚

| 风险 | 缓解 |
|---|---|
| dev chat 现状为坏（completions 形状 × 原生路由 400） | P0 首项实测定性；若坏则 SDK 化即修复，P1 验收以 dev 候选真通为准；回退路径切回 Generic（deepseek 默认不受影响） |
| SDK facade 路由错配（qwen3.7-plus 等多模态路由模型被 Generation 拒绝） | §4.2.4 双策略，P0 实测定夺；策略 b（按模型族分流）为保底，candidate params 显式声明路由 |
| okhttp 5.4→4.12 降版传输层回归 | API 面已核验仅 4.x 公共 API；P0 全链路回归（LLM 三能力 + MinIO）作 go/no-go 门 |
| victools 传递 jackson 与 Boot 仲裁交叉 | Boot BOM 管理全部 jackson artifact，P0 dependency:tree 确认 |
| SDK 流式 chunk 语义与轮末汇总包契约偏差 | §4.2.3 accumulator 复刻 + P1 流式专项单测；联调用 agent 工具调用链路实测 |
| stable embedding 模型 `Qwen/Qwen3-Embedding-8B`（开源系命名）经 SDK 调用不通 | P0 冒烟前置；不通则 stable 候选切 `text-embedding-v4`（同 1536 维、官方模型、SDK 全支持——维度/索引无需变更），或该候选暂留手写客户端 |
| workspace 专属域名 401（Key 不属于该 workspace） | dev 三能力已在此域名运行（归属满足）；stable 迁 workspace 域名时纳入冒烟 |
| 双层重试/超时叠加（SDK 内置 retry vs Resilient RetryPolicy） | P0 实测 SDK retry 行为；构造参数显式关闭其内置重试，重试语义唯一归 Resilient 层 |
| BYOK/DB 行 provider_code='bailian' + 自定义 baseUrl 被 SDK 化劫持 | §4.3 工厂 engagement 守卫（域名白名单 + `sdk-client` 显式开关），不命中回落 GenericChatClient |
| RxJava2/gson/victools 新增传递依赖面 | 坐标与项目 reactor/jackson 无交叉，P0 dependency:tree 复核；工厂层隔离使升级影响面限于百炼 SDK 客户端类（BailianChatClient / BailianEmbeddingClient） |
| SDK 版本迭代快（2.x 发版频繁） | 锁定版本 + 升级独立任务；工厂层隔离 |
| 回滚 | 每期独立：CHAT 删 `BailianChatClientFactory` 的 @Component 即回 GenericChatClient（BYOK 守卫不命中亦自动回落）；EMBEDDING 为同名全量替换，回滚 = revert P2 commit（rerank 未动不受影响）；OkHttp 回退独立成 commit 可单独 revert |

---

**附：参考资料**

**官方文档（v3/v4 核验依据）**：
- [Base URL（地域/域名体系：共享域名 vs 业务空间专属域名 vs 试用域名）](https://help.aliyun.com/zh/model-studio/base-url)
- [DashScope API 参考（千问双路由：text-generation vs multimodal-generation；Java SDK baseUrl 两方式）](https://help.aliyun.com/zh/model-studio/qwen-api-via-dashscope)
- [通用文本排序模型 API（qwen3-rerank 走 /compatible-api/v1/reranks；原生路由仅 gte-rerank-v2/qwen3-vl-rerank）](https://help.aliyun.com/zh/model-studio/text-rerank-api)
- [通用文本向量同步接口（DashScope 原生路由 + Java SDK 分批示例；instruct 须搭配 text_type=query）](https://help.aliyun.com/zh/model-studio/text-embedding-synchronous-api)
- [向量化（Embedding）总览（模型表：qwen3.7-text-embedding 2560~256 维/批次 20；text-embedding-v4 64~2048 维/批次 10）](https://help.aliyun.com/zh/model-studio/embedding)
- [首次调用千问 API（SDK 安装）](https://help.aliyun.com/zh/model-studio/first-api-call-to-qwen)

**SDK 与生态**：
- [dashscope-sdk-java（Maven Central，当前 2.22.30）](https://central.sonatype.com/artifact/com.alibaba/dashscope-sdk-java)
- [dashscope-sdk-java 源码仓库](https://github.com/dashscope/dashscope-sdk-java)
- [Spring AI Alibaba DashScope 接入文档（方案 B 参考，已否决）](https://java2ai.com/integration/chatmodels/dashScope)

**项目内实证**：
- `src/main/resources/application-dev.yml`（未跟踪）——dev 三能力域名/endpoint/模型矩阵；rerank 兼容端点实证注释（原生路由顶层格式 400）
