# 架构设计

> 核心架构概览，包含多厂商 Provider、模型路由、用户隔离、Advisor 链、Tool Calling 等设计。
> 详细的安全设计见 [SECURITY.md](SECURITY.md)，会话模块见 [CONVERSATION-DESIGN.md](CONVERSATION-DESIGN.md)，RAG 见 [RAG-DESIGN.md](RAG-DESIGN.md)。

## 多厂商 Provider 抽象（核心架构）

```
ChatController
     │
     ▼
ChatService ─── ModelRouter.resolve("deepseek/deepseek-chat") → Route("deepseek", "deepseek-chat")
     │                                                              │
     │                    ProviderRegistry.get("deepseek")           │
     │                              │                               │
     │                              ▼                               │
     │              ┌──────── ModelProvider (interface) ────────┐   │
     │              │                  │                        │   │
     │     DeepSeekProvider   ZhipuProvider          MiniMaxProvider
     │              │                │                       │      │
     │              ▼                ▼                       ▼      │
     │         DeepSeekApi     ZhiPuAiApi             MiniMaxApi   │
     │              │                │                       │      │
     │              ▼                ▼                       ▼      │
     │         ChatClient      ChatClient             ChatClient   │
     │              │                │                       │      │
     │              └───────┬────────┴──────────┬───────────┘      │
     │                      ▼                   ▼                   │
     │             ChatClientRegistry ← ModelRegistryRefresher     │
     │                                                              │
     ▼                                                              │
  ChatClient.call(prompt) ──→ 流式/阻塞响应                         │
```

**关键设计：**
- **策略模式**：`ModelProvider` 接口封装所有厂商差异（API 调用、ChatOptions 类型、模型列表获取）
- **服务定位模式**：`ProviderRegistry` 通过 Spring 构造器注入自动发现所有 Provider 实现
- **开闭原则 (OCP)**：新增厂商只需新增 Provider 实现类 + Properties record + RestClient Bean，零修改现有代码
- **容错启动**：未配置 API Key 的 Provider 静默跳过，不影响其他 Provider 和服务启动

## 模型 ID 路由

```
请求 model="deepseek/deepseek-chat"  → Route(providerId="deepseek", modelId="deepseek-chat")
请求 model="zhipu/glm-4.7"          → Route(providerId="zhipu", modelId="glm-4.7")
请求 model="minimax/MiniMax-M2.1"    → Route(providerId="minimax", modelId="MiniMax-M2.1")
请求 model="deepseek-chat"           → Route(providerId="deepseek", modelId="deepseek-chat")  // 向后兼容
```

- `providerId/modelId` 复合格式精确路由到指定厂商
- 无前缀时回退到默认厂商（`model.router.default-provider`，默认 `deepseek`）


所有对话和用量数据通过 `ConversationIdUtil` 自动附加用户前缀：

```
用户 42 请求 conversationId="test" → 存储 "u_42_test"
```

- Controller/Service 通过 `ConversationIdUtil.buildIsolatedId()` 统一构建
- 对外 API 透明：请求/响应始终使用原始 ID，内部自动隔离
- 用量统计查询通过 LIKE 前缀过滤当前用户数据

## 用户隔离

所有对话和用量数据通过 `ConversationIdUtil` 自动附加用户前缀：

```
用户 42 请求 conversationId="test" → 存储 "u_42_test"
```

- Controller/Service 通过 `ConversationIdUtil.buildIsolatedId()` 统一构建
- 对外 API 透明：请求/响应始终使用原始 ID，内部自动隔离
- 用量统计查询通过 LIKE 前缀过滤当前用户数据

## Advisor 链

```
请求 → ConversationContextAdvisor (order=-1, 注入 conversationId)
     → RateLimitAdvisor (order=0, 限流)
     → ContentFilterAdvisor (order=1, 输入检测)
     → RetrievalAugmentationAdvisor (ragEnabled=true 时由 RagAdvisorFactory 动态创建，携带 userId 隔离)
     → ToolCallAdvisor (order=2, 工具调用循环)
     → MessageChatMemoryAdvisor (对话记忆写入)
     → 模型调用
     → ContentFilterAdvisor (after, 输出过滤)
     → 响应
```

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
- 内置工具：`DateTimeTools`（日期时间查询）、`CalculatorTools`（数学计算，基于 exp4j）、`CodeExecutionTool`（沙箱代码执行）

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
