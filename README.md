# DeepSeek Chat Demo

基于 **Spring Boot 3 + Spring AI 1.x** 的 DeepSeek 聊天助手后端项目。支持动态模型加载、SSE 流式响应、内存对话记忆，并通过自定义 Advisor 链实现限流与内容安全过滤。

## 技术栈

| 依赖 | 版本 | 用途 |
|------|------|------|
| Java | 21 | 运行时 |
| Spring Boot | 3.5.14 | 应用框架 |
| Spring AI | 1.1.5 | AI 模型集成 |
| spring-ai-starter-model-deepseek | 1.1.5 | DeepSeek 官方 SDK |
| sensitive-word | 0.29.5 | DFA 敏感词过滤（纯内存，14W+ QPS） |
| Lombok | - | 减少样板代码 |
| Maven | - | 构建工具 |

## 快速开始

### 1. 配置 API Key

编辑 `src/main/resources/application.yml`：

```yaml
spring:
  ai:
    deepseek:
      api-key: sk-your-actual-api-key
```

或通过环境变量：

```bash
export DEEPSEEK_API_KEY=sk-your-actual-api-key
```

### 2. 编译运行

```bash
mvn clean package -DskipTests
java -jar target/deepseek-chat-demo-0.0.1-SNAPSHOT.jar
```

### 3. 验证

```bash
# 查看可用模型
curl http://localhost:8080/api/models

# 阻塞式聊天
curl -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" \
  -d '{"model":"deepseek-chat","message":"你好","conversationId":"test"}'

# SSE 流式聊天
curl http://localhost:8080/api/chat/stream?model=deepseek-chat&message=你好&conversationId=test
```

## API 接口

| 方法 | 路径 | 说明 |
|------|------|------|
| `GET` | `/api/models` | 获取可用模型列表 |
| `POST` | `/api/chat` | 阻塞式聊天 |
| `GET` | `/api/chat/stream` | SSE 流式聊天（query params） |
| `POST` | `/api/chat/stream` | SSE 流式聊天（JSON body） |
| `POST` | `/api/models/refresh` | 刷新模型列表 |

### 请求 / 响应示例

**聊天请求：**

```json
{
  "model": "deepseek-chat",
  "message": "用一句话介绍 Spring AI",
  "conversationId": "conv-001"
}
```

**聊天响应：**

```json
{
  "model": "deepseek-chat",
  "content": "Spring AI 是 Spring 生态中用于集成各类 AI 模型的统一抽象层。",
  "conversationId": "conv-001"
}
```

## 项目结构

```
src/main/java/com/demo/deepseekchat/
├── DeepseekChatApplication.java        # 启动类
│
├── config/                             # 配置层
│   ├── DeepSeekProperties.java         #   配置属性（record，绑定 spring.ai.deepseek.*）
│   ├── DeepSeekAutoConfiguration.java  #   自动配置：启动时拉取模型列表并注册 ChatClient
│   └── AdvisorAutoConfiguration.java   #   Advisor 编排：集中创建和注册所有 Advisor Bean
│
├── chat/                               # ChatClient 管理层
│   ├── ChatClientFactory.java          #   ChatClient 构建工厂（封装创建流程）
│   └── ChatClientRegistry.java         #   ChatClient 注册中心（存储和查询）
│
├── advisor/                            # Advisor 层（请求拦截链）
│   ├── RateLimiter.java                #   限流器接口
│   ├── TokenBucketLimiter.java         #   令牌桶限流器实现
│   ├── RateLimitAdvisor.java           #   限流 Advisor（order=0）
│   ├── ContentFilterAdvisor.java       #   内容安全 Advisor（order=1）
│
├── content/                            # 内容安全层
│   ├── ContentFilterService.java       #   过滤服务接口
│   └── SensitiveWordFilterService.java #   sensitive-word DFA 实现
│
├── service/                            # 业务服务层
│   ├── ModelService.java               #   模型管理（列表查询、刷新）
│   └── ChatService.java                #   聊天服务（阻塞 + 流式 + 对话记忆）
│
├── controller/                         # 接口层
│   └── ChatController.java             #   REST API 入口
│
├── model/dto/                          # 数据传输对象
│   ├── ChatRequest.java                #   聊天请求
│   ├── ChatResponse.java               #   聊天响应
│   ├── ModelInfo.java                  #   模型信息
│   └── ModelsResponse.java             #   模型列表响应
│
└── exception/                          # 异常处理
    ├── RateLimitExceededException.java #   限流异常（→ 429）
    ├── ContentFilteredException.java   #   内容过滤异常（→ 400）
    ├── ModelNotFoundException.java     #   模型不存在异常（→ 404）
    └── GlobalExceptionHandler.java     #   全局异常处理器
```

## 模块划分与核心设计

### 1. 动态模型加载（config + chat）

启动时通过 `DeepSeekAutoConfiguration` 调用 DeepSeek `GET /models` API 拉取模型列表，利用 `ChatClientFactory` 为每个模型创建独立的 `ChatClient` 实例，注册到 `ChatClientRegistry` 中。运行时可随时调用 `/api/models/refresh` 热刷新。

**关键抽象：**
- `ChatClientFactory` — 只负责创建，不管存储
- `ChatClientRegistry` — 只负责存储和查询，不管创建
- `DeepSeekProperties` — 纯数据持有，不包含业务逻辑

### 2. Advisor 链（advisor + content）

基于 Spring AI 的 `BaseAdvisor` 接口，实现请求拦截链。`ChatService` 通过 Spring 自动注入 `List<Advisor>` 获取所有 Advisor，不直接依赖具体实现。

**执行顺序：**

```
请求 → RateLimitAdvisor (order=0, 限流)
     → ContentFilterAdvisor (order=1, 输入检测)
     → DeepSeek 模型调用
     → ContentFilterAdvisor (after, 输出过滤)
     → MessageChatMemoryAdvisor (order=2, 记忆写入)
     → 响应
```

**关键抽象：**
- `RateLimiter` 接口 — 解耦具体限流算法（令牌桶、滑动窗口等可替换）
- `ContentFilterService` 接口 — 解耦具体敏感词实现（可替换为第三方 API）

### 3. 对话记忆（service）

使用 `MessageWindowChatMemory` + `InMemoryChatMemoryRepository`，按 `conversationId` 隔离，保留最近 20 条消息。通过 `MessageChatMemoryAdvisor` 自动管理上下文注入。

### 4. 异常处理（exception）

全局异常处理器将业务异常转为标准 HTTP 响应：

| 异常 | HTTP 状态码 | 场景 |
|------|------------|------|
| `RateLimitExceededException` | 429 | 请求过于频繁 |
| `ContentFilteredException` | 400 | 输入包含敏感词 |
| `ModelNotFoundException` | 404 | 指定模型不存在 |
| `IllegalArgumentException` | 400 | 参数错误 |
| 通用 `Exception` | 500 | 服务内部错误 |

## 设计原则

- **单一职责**：每个类只做一件事（Factory 创建、Registry 存储、Service 业务）
- **依赖倒置**：Advisor 依赖接口（`RateLimiter`、`ContentFilterService`），不依赖具体实现
- **开闭原则**：新增限流算法只需实现 `RateLimiter`，新增过滤方式只需实现 `ContentFilterService`
- **关注点分离**：配置、ChatClient 管理、Advisor 链、业务逻辑、接口层各自独立

## License

MIT
