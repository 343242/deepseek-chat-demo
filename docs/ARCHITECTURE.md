# 架构设计

> 核心架构概览，包含统一 LLM SPI、Agentic RAG、模型路由、用户隔离、Advisor 链、Tool Calling 等设计。
> 详细的安全设计见 [SECURITY.md](SECURITY.md)，会话模块见 [CONVERSATION-DESIGN.md](CONVERSATION-DESIGN.md)，RAG 见 [RAG-DESIGN.md](RAG-DESIGN.md)，Agentic RAG 见 [AGENTIC-RAG-DESIGN.md](AGENTIC-RAG-DESIGN.md)，LLM SPI 契约见 [`.trellis/spec/backend/llm-spi.md`](../.trellis/spec/backend/llm-spi.md)。

## 整体分层

```
HTTP Controller 层（chat / agent / rag / team / user）
        ↓
业务编排层（chat / agent / rag / team 模块的 service）
        ↓
统一 LLM SPI 入口：RewriteClientResolver（chat / rag / agent 共用）
        ↓
LlmClientRegistry（无锁读写分离，AtomicReference<RegistrySnapshot>）
        ↓
CapabilityClient（ChatCapable / EmbeddingCapable / RerankCapable / ToolCallingCapable）
        ↓
厂商实现（infrastructure/llm/client/{bailian,generic}/）
        ↓
HTTP / SDK（DashScope、DeepSeek、智谱、MiniMax）
```

- **业务层不感知厂商**：`chat` / `rag` / `agent` 三个上层模块统一通过 `RewriteClientResolver` 拿 `ChatClient`（已 build），不再直接注入 Spring AI `ChatClient.Builder` / `ChatModel`
- **基础设施层 `infrastructure/`**：跨业务复用的能力（LLM、Advisor、Memory、Fallback、并发、消息、异常）集中在此，业务模块只编排

## 统一 LLM SPI（核心架构）

```
ChatController / AgentController / RagController
     │
     ▼
业务 Service ──── RewriteClientResolver.resolve(candidateId) ────┐
                                                                  │
                                                                  ▼
                                              LlmClientRegistry.get(candidateId, LlmCapability.CHAT)
                                                                  │
                                                                  ▼
                                              CapabilityClient（候选 ID + provider ID + model name）
                                                  │                       │
                                                  │ 一对一能力契约         │
                                                  ▼                       ▼
                                          ChatCapable            EmbeddingCapable / RerankCapable
                                                  │
                                                  ▼ 桥接
                                          ChatModelAdapter（implements Spring AI ChatModel）
                                                  │
                                                  ▼
                                          ChatClient.Builder → ChatClient（业务侧拿到的是已 build 实例）
                                                  │
                                                  ▼
                                  Resilience（Retry + Circuit Breaker，app.llm.resilience.*）
                                                  │
                                                  ▼
                                  厂商实现：infrastructure/llm/client/{bailian,generic}/
                                                  │
                                                  ▼
                                  ProviderClientFactory + strategy/provider 工厂族
                                                  │
                                                  ▼
                                  DashScope / DeepSeek / Zhipu / MiniMax HTTP API
```

**核心抽象（`infrastructure/llm/`）：**

| 类型 | 角色 |
|------|------|
| `CapabilityClient`（根接口） | 所有能力客户端的公共契约，仅暴露 `candidateId()` / `providerId()` / `modelName()` / `capability()` / `isAvailable()`，**不暴露 `ModelCandidate` 引用**（避免循环耦合） |
| `ChatCapable` / `EmbeddingCapable` / `RerankCapable` / `ToolCallingCapable` | 一对一的能力契约（ISP），客户端实现一个能力接口即被识别为该能力 |
| `LlmClientRegistry` | 注册表，**无锁读写分离**（`AtomicReference<RegistrySnapshot>`）。读路径直读快照，写路径（refresh / disable / enable）CAS 替换。提供 `get(id, type)` / `getDefault(type)` / `getChain(capability)`（Fallback 链） |
| `LlmClientFactory` | 根据 `ModelCandidate` 构造 `CapabilityClient` 实例，封装策略选择 |
| `ChatModelAdapter` | 把任意 `ChatCapable` 桥接为 Spring AI `ChatModel`，**默认 options 暴露为 `ToolCallingChatOptions`**（让自建 `ChatClient` 能挂载 `ToolCallAdvisor`，规避 Spring AI 强校验）。桥接逻辑（Prompt→ChatRequest、LlmResponse→ChatResponse）集中于此（SRP） |
| `RewriteClientResolver` | chat / rag / agent 的统一入口，**返回 `ChatClient`（已 build）而非 `Builder`**。需要 Builder 时由调用方 `.mutate()` |
| `ProviderClientFactory` + `strategy/provider/*` | 厂商差异封装：`BailianEmbeddingClientFactory`、`BailianRerankClientFactory`、generic 厂商适配 |
| `ResilienceConfig` | Retry + Circuit Breaker（`app.llm.resilience.retry.*` / `circuit-breaker.*`） |

**关键设计原则：**

- **策略模式 + 服务定位**：`LlmClientRegistry` 自动装配所有候选，运行期按 `candidateId` + `LlmCapability` 查询；新增厂商 = 新增 `ProviderClientFactory` 实现，零改主链路（OCP）
- **接口隔离（ISP）**：`CapabilityClient` 不被迫继承 Spring AI `ChatModel` 全部方法，桥接代码唯一存在于 `ChatModelAdapter`
- **配置驱动**：候选与厂商在 `app.llm.providers.{provider}.*` + `app.llm.capabilities.{chat,embedding,reranking}.candidates[]` 声明，启动期绑定，运行期无前缀解析
- **容错启动**：未配置 API Key 的 Provider 静默跳过，不影响其他 Provider 与服务启动
- **韧性内建**：所有调用经 `ResilienceConfig` 包装，单候选失败自动沿 `getChain(capability)` 降级
- **与 Spring AI 解耦**：业务模块禁止直接注入 `ChatClient.Builder` / `ChatModel`，必须走 `RewriteClientResolver` 或 `LlmClientRegistry`（详见 LLM SPI spec 的 Good/Base/Bad Cases）

**配置示例（节选自 `application.yml`）：**

```yaml
app:
  llm:
    providers:
      bailian:
        url: https://dashscope.aliyuncs.com
        api-key: ${DASHSCOPE_API_KEY:sk-***}
        endpoints:
          chat: /api/v1/services/aigc/text-generation/generation
          embedding: /api/v1/services/embeddings/text-embedding/text-embedding
      deepseek:
        api-key: ${DEEPSEEK_API_KEY:sk-***}
    capabilities:
      chat:
        default-model: qwen3-max
        candidates:
          - id: qwen-plus
            provider: bailian
            model: qwen-plus-latest
            priority: 1
          - id: deepseek-v4-flash
            provider: deepseek
            model: deepseek-v4-flash
            supports-streaming: true
            priority: 2
          - id: qwen3-max
            provider: bailian
            model: qwen3-max
            supports-thinking: true
            priority: 4
      embedding:
        default-model: text-embedding-v4
        candidates:
          - id: text-embedding-v4
            provider: bailian
            model: text-embedding-v4
            dimension: 1024
            priority: 1
      reranking:
        default-model: qwen3-rerank
        candidates:
          - id: qwen3-rerank
            provider: bailian
            model: qwen3-rerank
            priority: 1
    resilience:
      retry:
        max-attempts: 3
        base-delay-ms: 500
        max-delay-ms: 5000
        multiplier: 2.0
      circuit-breaker:
        failure-threshold: 5
        open-duration-ms: 30000
```

## 模型 ID 路由

```
请求 model="deepseek-v4-flash"  → ChatRequest.model 直传 LlmClientRegistry.get(candidateId)
请求 model="qwen-plus"          → 同上，registry 内解析候选与厂商的映射
请求 model=""（或 null）         → llmRegistry.getDefault(LlmCapability.CHAT).candidateId()
```

- API 请求 `model` 字段必须为 **registry 候选 ID**（candidate ID，与 `app.llm.capabilities.chat.candidates[].id` 一致）
- 不再支持 `providerId/modelId` 复合格式——`ChatServiceImpl.resolveCandidateId` 检测到 `/` 立即 fail-fast 抛 `IllegalArgumentException`（被 `GlobalExceptionHandler.handleIllegalArgument` 映射为 `ClientErrorCode.BAD_REQUEST`，业务码 100001）
- 候选与厂商的映射在 `LlmClientRegistry` 启动期绑定，运行期无需解析前缀
- 详细契约见 [`.trellis/spec/backend/llm-spi.md`](../.trellis/spec/backend/llm-spi.md)

## 用户隔离

所有对话和用量数据通过 `ConversationIdUtil` 自动附加用户前缀：

```
用户 42 请求 conversationId="test" → 存储 "u_42_test"
```

- Controller/Service 通过 `ConversationIdUtil.buildIsolatedId()` 统一构建
- 对外 API 透明：请求/响应始终使用原始 ID，内部自动隔离
- 用量统计查询通过 LIKE 前缀过滤当前用户数据

## Agentic RAG 架构

```
HTTP /api/chat（agent 模式） 或 意图识别命中
     │
     ▼
IntentResolver（agent.intent-model，registry 候选 ID）
     │
     ▼ 命中 RAG 意图
Agent 主循环（agent/service/）
     │  ├── 单轮 workspace：ToolWorkspaceFactory.newWorkspace()
     │  ├── 双限位：max-tool-iterations / max-consecutive-same-tool
     │  ├── 工具调用 → AgentToolCallbackFactory 装配的工具族
     │  ├── 实时埋点：AgentTrace + ToolCallRecord（agent/trace/）
     │  └── Guardrail 守门（agent/guardrail/）
     │
     ▼ 工具族（agent/tool/）
     ├── 检索：VectorSearchTool / Bm25SearchTool / HybridSearchTool
     ├── 精排：RerankTool（→ BailianRerankPostProcessor）
     ├── 改写：QueryRewriteTool
     ├── 回查：ParentDocLookupTool / DocDetailTool
     ├── 元数据：KnowledgeBaseInfoTool / AgentEventLookupTool
     └── 通用 RAG：RagTool（封装完整 Pipeline）
     │
     ▼ 失败 / 超时
degrade-on-failure=true → 回退到普通 RAG Pipeline
```

- **意图驱动**：`app.agent.intent-model`（registry 候选 ID，默认 `deepseek-v4-flash`）+ `intent-temperature=0.1` + `intent-retries=2` + `intent-timeout-ms=5000`
- **可观测**：`AgentTrace` 记录每次迭代的工具调用、中间答案（`IntermediateAnswer`）、自我反思（`SelfReflection`）
- **降级安全**：意图识别失败 / 工具循环超时 / Guardrail 拦截 → 自动降级到六阶段 RAG Pipeline（`degrade-on-failure`）
- **Workspace 隔离**：`ToolWorkspace` 持有 `RetrievedDocument` 集合，单次会话内累积上下文，请求结束自动清理
- **配置全外部化**：`app.agent.*`（`AgentRagProperties`）

> 详细设计见 [AGENTIC-RAG-DESIGN.md](AGENTIC-RAG-DESIGN.md) 与 [AGENTIC-RAG-IMPLEMENTATION-NOTES.md](AGENTIC-RAG-IMPLEMENTATION-NOTES.md)。

## Advisor 链

```
请求 → ConversationContextAdvisor (order=-1, 注入 conversationId)
     → RateLimitAdvisor (order=0, 限流，令牌桶)
     → ContentFilterAdvisor (order=1, 输入检测，DFA 敏感词)
     → RetrievalAugmentationAdvisor (ragEnabled=true 时由 RagAdvisorFactory 动态创建，携带 userId 隔离)
     → ToolCallAdvisor (order=2, 工具调用循环)
     → MessageChatMemoryAdvisor (对话记忆写入，Redis + JDBC 双层)
     → ChatModelAdapter → CapabilityClient（统一 SPI 出口）
     → ContentFilterAdvisor (after, 输出过滤)
     → 响应
```

> Agent 模式下额外挂载 `AgentSystemPromptAdvisor`，工具回调经 `AgentToolCallbackFactory` 注入 `ToolCallingChatOptions`。

## Tool Calling（工具调用）

```
用户提问 "明天星期几？"
     │
     ▼ 模型决定需要调用工具
     │
ToolCallAdvisor → ToolCallingManager
     │
     ▼ 分发到 DateTimeTools.getCurrentWeekday(offsetDays=1)
     │
     ▼ 工具返回 "2026-05-10 是星期日"
     │
     ▼ 结果回传模型，生成最终回复
```

- **声明式注册**：`@Component` + `@Tool` 注解，Spring 自动发现
- **OCP**：新增工具 = 新增类，零改现有代码
- **ToolCallAdvisor** 显式在 advisor 链中处理，限流/内容过滤可拦截工具调用过程
- **disableMemory()**：已有 MessageChatMemoryAdvisor 管理对话历史，避免重复
- **两类工具族**：
  - **通用工具**（`chat/tool/`）：`DateTimeTools`、`CalculatorTools`（exp4j）、`CodeExecutionTool`（沙箱）
  - **RAG 工具**（`agent/tool/`）：见上方 Agentic RAG 架构

### 沙箱代码执行

```
模型返回 tool_call: { code: "print(sum(range(1, 101)))", language: "python" }
     │
     ▼ CodeExecutionTool.executeCode()
     │
     ▼ SandboxService
     │  docker create --rm --network=none --read-only --user nobody
     │           --memory=128m --cpus=1 --pids-limit=64
     │           sandbox-python:bookworm timeout 10 python3 /tmp/code.py
     │
     ▼ 执行完毕，容器自动删除
     │
     ▼ 返回结果 "退出码: 0
输出:
5050"
     │
     ▼ 模型生成最终回复："1 到 100 的和是 5050"
```

| 安全层级 | 措施 |
|---------|------|
| 网络 | `--network=none` |
| 文件系统 | `--read-only` + `tmpfs /tmp` |
| 用户 | `--user nobody` |
| 内存 | `--memory=128m` |
| CPU | `--cpus=1` |
| 进程 | `--pids-limit=64` |
| 超时 | `timeout 10` + Java Future 双重保障 |
| 清理 | `--rm` 用完即弃 |

> 沙箱资源限额也可通过 `app.sandbox.*`（`max-concurrency` / `timeout` / `max-memory-mb` / `max-cpus` / `max-output-bytes`）调整。

#### 沙箱代码执行

```
模型返回 tool_call: { code: "print(sum(range(1, 101)))", language: "python" }
     │
     ▼ CodeExecutionTool.executeCode()
     │
     ▼ SandboxService
     │  docker create --rm --network=none --read-only --user nobody
     │           --memory=128m --cpus=1 --pids-limit=64
     │           sandbox-python:bookworm timeout 10 python3 /tmp/code.py
     │
     ▼ 执行完毕，容器自动删除
     │
     ▼ 返回结果 "退出码: 0\n输出:\n5050"
     │
     ▼ 模型生成最终回复："1 到 100 的和是 5050"
```

| 安全层级 | 措施 |
|---------|------|
| 网络 | `--network=none` |
| 文件系统 | `--read-only` + `tmpfs /tmp` |
| 用户 | `--user nobody` |
| 内存 | `--memory=128m` |
| CPU | `--cpus=1` |
| 进程 | `--pids-limit=64` |
| 超时 | `timeout 10` + Java Future 双重保障 |
| 清理 | `--rm` 用完即弃 |

## 数据访问分层

```
Controller (@Valid 校验)
    ↓ 注入 Service 接口
Service 接口
    ↓
ServiceImpl (业务编排，不含 SQL)
    ↓
Mapper (语义化接口 + XML，所有查询逻辑在此)
    ↓
Database
```

- **Service 层接口/实现分离**：chat 模块与 user 模块一致，Controller 注入接口，实现类独立维护
- **Service 层不含 `LambdaQueryWrapper`**：所有查询通过 Mapper 语义化方法（`selectByModelId`、`selectAllOrdered`）或 XML SQL 实现
- **Mapper SQL 全部由 XML 维护**：不使用 `@Select`/`@Delete` 注解，SQL 集中在 `resources/mapper/*.xml`
- **编程式事务** `TransactionTemplate`，不使用 `@Transactional`
- DTO 全部使用 Java record，Entity 不暴露给前端
- **Flyway 数据库迁移**：`V1__init_schema.sql` 全量建表 + `V2__vector_store_bm25.sql` BM25 支持

## 缓存策略

| 层级 | 技术 | TTL | 场景 |
|------|------|-----|------|
| 本地 | Caffeine | 30s | ModelParams 热路径 |
| 本地 | Caffeine | 5min | SystemPrompt / 验证码答案 |
| 分布式 | Redis | 300s | 用户权限缓存 |
| 分布式 | Redis | 900s | Access Token 元数据 |
| 分布式 | Redis | 86400s | Refresh Token + 用户状态标记 |

## 异常处理

| 异常 | HTTP 状态码 | 场景 |
|------|------------|------|
| `RateLimitExceededException` | 429 | 登录限流 / API 限流 |
| `AuthenticationException` | 401 | 未认证 / Token 失效 |
| `AccessDeniedException` | 403 | 权限不足 |
| `ContentFilteredException` | 400 | 敏感词 |
| `ModelNotFoundException` | 404 | 模型不存在 |
| `ProviderNotFoundException` | 404 | 厂商不存在或未配置 |
| `BusinessException` | 400 | 业务逻辑错误 |
| `MethodArgumentNotValidException` | 400 | 参数校验失败 |
| 通用 `Exception` | 500 | 服务内部错误 |

## 设计原则

| 原则 | 实践 |
|------|------|
| **单一职责 (SRP)** | Controller 只做 HTTP 转发、Service 只做业务编排、Mapper 只做数据访问 |
| **依赖倒置 (DIP)** | 依赖接口（`AuthService`、`ChatService`、`RateLimiter`、`ContentFilterService`），不依赖实现类 |
| **开闭原则 (OCP)** | 新增厂商 = 新增 Provider 类；新增限流算法 = 实现 `RateLimiter` 接口；零改旧代码 |
| **接口隔离 (ISP)** | 每个 Service 提供独立接口（chat 模块与 user 模块一致），Controller 按需注入 |
| **策略模式** | `ModelProvider` 封装厂商差异，`ProviderRegistry` 自动发现 |
| **DTO 隔离** | Entity 不暴露给前端，全部通过 record DTO 转换 |
| **数据访问下沉** | `LambdaQueryWrapper` 全部在 Mapper 层，Service 层不含 SQL 构建逻辑 |
| **编程式事务** | `TransactionTemplate` 精确控制事务边界 |
| **统一响应** | 所有接口返回 `GlobalResponse<T>`（code/message/data），结构化错误码（`ErrorCode` 枚举 46 个），分页统一 `PagedResult<T>`（Service 层强类型，零 Map<String, Object>），前端精确识别错误类型 |
| **安全纵深** | JWT + Redis 吊销 + 用户状态 + IP 限流 + 滑块验证码 + Cookie SameSite + 资源级 owner 校验 |
| **自研核心** | 雪花 ID 生成器、UUIDv7 (RFC 9562) 生成器、滑块验证码均为纯 Java 实现，无外部依赖 |
| **模板方法 + 接口分离** | ETL Pipeline 拆分为 Extractor/Transformer/Loader 独立接口，Pipeline 只做编排 |
| **四阶段检索管道** | 查询改写→混合检索+RRF→Rerank→MMR，各阶段独立可配，管道通过 `RagConfig` 统一装配 |
| **双层检索（Parent-Child）** | 子切分保证检索精度，父文档保证 LLM 上下文完整性 |
| **并发 ETL + 策略路由** | IO/CPU 双线程池分离，FastTrack/Standard 策略路由，OCP 零改旧代码 |
| **ETL 状态集中管理** | EtlStatus 常量类 + EtlStatusManager 独立事务，消除重复代码 |
| **EmbeddingModel 接口适配** | 自建 DashScopeEmbeddingModel 实现标准接口，PgVectorStore 自动注入，零耦合 |
| **启动优化** | MapperScan 精确化（显式列出 5 个包替代通配符）、scanBasePackages 精确到业务包、ConfigurationPropertiesScan 覆盖全包；启动时间 8s → 5.6s |
| **团队上传策略** | UploadStrategy 接口 + PersonalUploadStrategy/TeamUploadStrategy，工厂根据 teamId 路由，OCP 零改旧代码 |
| **Bucket 隔离** | BucketResolver 封装 bucket 命名规则（个人默认 bucket / 团队 `rag-team-{teamId}`），上传策略、定时任务、分片服务统一调用，避免硬编码 |
| **审批状态机** | PENDING → APPROVED/REJECTED + 超时自动拒绝，定时任务 fixedDelay 1h 扫描 |
| **角色枚举设计** | CREATOR(30) > ADMIN(20) > MEMBER(10) 数值比较，@EnumValue 存 int、@JsonValue 返回 name()，DB 与 API 分离 |
| **统一权限门面** | DocumentOwnershipChecker 跨 team/rag 模块，个人文档 owner 检查、团队文档成员资格 + 角色检查 |
| **统一 LLM SPI** | `infrastructure/llm/` 通过 `CapabilityClient`（ISP 一能力一接口）+ `LlmClientRegistry`（无锁读写分离）+ `ChatModelAdapter`（桥接唯一存放点）+ `RewriteClientResolver`（统一入口）解耦业务与厂商；业务层禁止直接注入 `ChatClient.Builder`/`ChatModel` |
| **模型 ID 强约束** | 全项目模型 ID 统一为 registry 候选 ID（`deepseek-v4-flash`），`/` 复合格式在 service 入口 fail-fast；`compositeId` / `modelId` 字段名历史遗留，值是单段候选 ID |
| **Agentic RAG 可观测 + 可降级** | `AgentTrace` + `ToolCallRecord` 全链路埋点，`max-tool-iterations` / `max-consecutive-same-tool` 双限位，`degrade-on-failure` 自动回退六阶段 Pipeline |

## 团队协作架构

### 数据模型

```sql
-- 团队基本信息（V9）
CREATE TABLE team (
    id              BIGINT PRIMARY KEY GENERATED BY DEFAULT AS IDENTITY,
    team_name       VARCHAR(128) NOT NULL,
    team_desc       VARCHAR(512),
    creator_id      BIGINT NOT NULL REFERENCES sys_user(id),
    default_upload_limit_mb  BIGINT NOT NULL DEFAULT 50,
    creator_upload_limit_mb  BIGINT NOT NULL DEFAULT 200,
    status          SMALLINT NOT NULL DEFAULT 1,  -- 0=INACTIVE, 1=ACTIVE
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    deleted         SMALLINT NOT NULL DEFAULT 0   -- 逻辑删除
);

-- 成员关系
CREATE TABLE team_member (
    id               BIGINT PRIMARY KEY GENERATED BY DEFAULT AS IDENTITY,
    team_id          BIGINT NOT NULL REFERENCES team(id),
    user_id          BIGINT NOT NULL REFERENCES sys_user(id),
    role             SMALLINT NOT NULL DEFAULT 10,  -- 10=MEMBER, 20=ADMIN, 30=CREATOR
    upload_limit_mb  BIGINT NOT NULL DEFAULT 50,
    status           SMALLINT NOT NULL DEFAULT 1,  -- 0=INACTIVE, 1=ACTIVE（不用 @TableLogic）
    joined_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- 上传审批
CREATE TABLE team_upload_approval (
    id              BIGINT PRIMARY KEY GENERATED BY DEFAULT AS IDENTITY,
    team_id         BIGINT NOT NULL REFERENCES team(id),
    document_id     BIGINT NOT NULL REFERENCES rag_document(id),
    uploader_id     BIGINT NOT NULL REFERENCES sys_user(id),
    status          SMALLINT NOT NULL DEFAULT 0,  -- 0=PENDING, 1=APPROVED, 2=REJECTED
    reviewer_id     BIGINT REFERENCES sys_user(id),
    review_comment  VARCHAR(512),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    reviewed_at     TIMESTAMPTZ
);
```

### 角色模型

```
CREATOR(30) > ADMIN(20) > MEMBER(10)
```

- 数值越大权限越高，可直接通过 `role > ADMIN` 比较判断权限
- `@EnumValue` 注解标记 int 值存入数据库，`@JsonValue` 注解标记 name() 用于 API 序列化
- DB 存数字保证查询效率，API 暴露字符串保证可读性，两者解耦

### 上传策略模式（OCP）

```
                   ┌─── UploadStrategy 接口 ───┐
                   │  (common.upload 包)        │
                   └────────┬──────────────────┘
                            │
            ┌───────────────┴───────────────┐
            ▼                               ▼
  PersonalUploadStrategy           TeamUploadStrategy
    (rag.upload 包)                  (team.upload 包)
    teamId = null                   teamId ≠ null
         │                               │
         ▼                               ▼
    直接上传 + ETL              额度校验 + 审批路由
                                      │
                        ┌─────────────┴─────────────┐
                        ▼                           ▼
                  管理员/创建者                   普通成员
                  → PROCESSING                → PENDING_APPROVAL
                  → 直接触发 ETL               → 创建审批记录
                                                 → 等待审批

UploadStrategyFactory
    → 根据 teamId == null 路由到个人/团队策略
    → 新增策略零改工厂代码
```

- **PersonalUploadStrategy**：个人上传，`teamId == null`，直接写入文档并触发 ETL
- **TeamUploadStrategy**：团队上传，`teamId != null`，先校验额度，再根据角色分流
  - 管理员/创建者 → 文档状态 `PROCESSING`，直接触发 ETL
  - 普通成员 → 文档状态 `PENDING_APPROVAL`，创建 `team_upload_approval` 记录，等待管理员审批
- **UploadStrategyFactory** 通过 `teamId == null` 作为路由键，简洁且符合 OCP

### 审批流

```
上传 → 策略路由
         │
         ├── 管理员/创建者 → PROCESSING → ETL → 完成
         │
         └── 普通成员 → PENDING_APPROVAL
                            │
                            ├── 管理员审批 → APPROVED → 触发 ETL
                            │
                            ├── 管理员拒绝 → REJECTED → 通知上传者
                            │
                            └── 超时未审批 → REJECTED（定时任务自动拒绝）
```

定时任务通过 `@Scheduled(fixedDelay = 3600000)` 每小时扫描超时审批记录并自动标记为 `REJECTED`（附带 "审批超时，系统自动拒绝" 注释）。

### 权限模型

```
DocumentOwnershipChecker（统一权限校验门面）
         │
         ├── 个人文档 → owner 检查（document.ownerId == currentUserId）
         │
         └── 团队文档 → 成员资格检查（team_member 表）
                        → 角色权限检查（role >= 所需最低角色）
```

- `DocumentOwnershipChecker` 作为跨 `team`/`rag` 模块的统一入口
- 调用方无需关心文档归属域，门面内部根据 `teamId` 自动路由校验逻辑
- 新增权限规则只需扩展门面，不影响调用方

### 并发安全

| 场景 | 策略 |
|------|------|
| **解散团队** | `SELECT ... FOR UPDATE` 行锁锁定 team 记录，阻止并发操作 |
| **添加成员** | 事务内插入 + `DuplicateKey` 异常兜底，防止重复成员 |
| **审批 review** | 事务内先查状态，仅 `PENDING` 状态允许变更，防止并发重复审批 |

### 分页

- `listMembers` / `listPending` / `listMyApprovals` 统一接入 `PageRequest` + `PagedResult<T>`
- 批量查询替代 N+1：`selectBatchIds` 收集所有 ID 后一次性查询，`Collectors.toMap` 建立映射
- 分页参数通过 Controller 层 `PageRequest` 注入，Service/Mapper 层透传，与项目分页规范一致

## 日志架构

### Log4j 2 异步架构

```
业务线程 → AsyncLogger（Disruptor 无锁环形队列）→ 后台线程批量写文件
```

- **AsyncLogger**：基于 LMAX Disruptor 的无锁队列，业务线程将日志事件放入队列即返回，不阻塞
- **全局异步**：`log4j2.contextSelector=org.apache.logging.log4j.core.async.AsyncLoggerContextSelector`
- 后台 I/O 线程从队列消费事件并写入磁盘，实现日志与业务线程完全解耦

### 日志目录结构

```
logs/
├── error/   error.log    ← ERROR + FATAL，保留 30 天，100MB 滚动
├── warn/    warn.log     ← WARN，保留 30 天，200MB 滚动
├── info/    info.log     ← INFO，保留 30 天，500MB 滚动
├── debug/   debug.log    ← DEBUG，保留 7 天，500MB 滚动
└── 控制台（彩色输出）
```

### 关键配置

- **LevelRangeFilter**：每个 Appender 通过 `LevelRangeFilter` 严格限定日志级别范围，确保 error.log 只含 ERROR/FATAL、warn.log 只含 WARN，各级别文件互不混杂
- **RollingRandomAccessFile**：使用内存映射文件（Memory-Mapped I/O）写入，比传统 `FileOutputStream` 性能大幅提升
- **滚动策略**：基于文件大小触发滚动（`SizeBasedTriggeringPolicy`），滚动时自动压缩旧文件

### 日志降噪

```xml
<!-- 第三方框架默认 WARN，减少噪音 -->
<Logger name="org.springframework" level="WARN"/>
<Logger name="com.baomidou.mybatisplus" level="WARN"/>
<Logger name="org.apache.ibatis" level="WARN"/>
<Logger name="io.minio" level="WARN"/>
<Logger name="org.flywaydb" level="WARN"/>
```

- Spring、Netty、HikariCP 等框架日志默认设为 WARN，避免 DEBUG/INFO 级别的框架内部日志淹没业务日志
- 需要排查框架问题时，可通过环境变量动态调低特定 Logger 级别

### 环境变量动态调级

```bash
# 调整根日志级别
-DLOG_ROOT_LEVEL=DEBUG

# 调整应用日志级别（不影响第三方框架）
-DLOG_APP_LEVEL=TRACE
```

- 生产环境默认 `LOG_ROOT_LEVEL=INFO`、`LOG_APP_LEVEL=INFO`
- 排查问题时无需改配置重启，通过 JVM 参数覆盖即可
