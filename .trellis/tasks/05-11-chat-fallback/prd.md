# PRD: Chat 模块兜底策略

## 1. 背景

当前 Chat 模块调用大模型时，若目标模型不可用（API 故障、厂商限流、网络超时等），请求直接失败返回 500 错误。系统中已接入 3 个厂商（DeepSeek、智谱、MiniMax），但调用失败时没有自动降级到备选模型的能力。

## 2. 目标

当主模型调用失败时，按预配置的**模型级降级链**依次尝试备选模型，流式场景先同模型重试再降级，直到成功或链耗尽，提升系统可用性。

## 3. 功能需求

### 3.1 降级链配置（模型级粒度）

支持为每个模型/厂商独立配置降级链，格式示例：

```yaml
app:
  chat:
    fallback:
      enabled: true
      max-retries: 3          # 同模型最大重试次数（流式专用，含首次请求）
      default-chain:          # 未命中 per-model 时的全局降级链
        - deepseek/deepseek-v4-flash
        - zhipu/glm-4.7-flash
        - minimax/MiniMax-M2.1
      chains:                 # 模型级降级链（key 支持复合格式和纯 modelId）
        deepseek/deepseek-v4-flash:
          - zhipu/glm-4.7-flash
          - minimax/MiniMax-M2.1
        zhipu/glm-4.7-flash:
          - deepseek/deepseek-v4-flash
          - minimax/MiniMax-M2.1
        minimax/MiniMax-M2.1:
          - deepseek/deepseek-v4-flash
          - zhipu/glm-4.7-flash
```

**匹配规则**：
- 先匹配复合格式 `provider/model`，再匹配纯 `modelId`
- 未命中任何 per-model 配置时回退到 `default-chain`
- 降级链中的每个模型也会按相同规则查找自己的链（但为防止无限循环，已尝试过的模型不再重复）

**配置项**：

| 配置项 | 说明 | 默认值 | 环境变量 |
|--------|------|--------|---------|
| `enabled` | 全局开关 | `true` | `CHAT_FALLBACK_ENABLED` |
| `max-retries` | 同模型重试次数（流式专用） | `3` | `CHAT_FALLBACK_MAX_RETRIES` |
| `default-chain` | 全局降级链 | `[]` | — |
| `chains` | 模型级降级链映射 | `{}` | — |

### 3.2 降级触发规则

**可降级的异常**（模型侧故障，换模型可能恢复）：
- `ModelNotFoundException` — 模型未注册
- `ProviderNotFoundException` — 厂商未配置
- 网络超时 / API 5xx / 429 限流等运行时异常

**不可降级的异常**（用户侧错误，换模型结果相同）：
- `ContentFilteredException` — 用户内容违规
- `BusinessException`（参数校验类）— 请求本身有问题

### 3.3 阻塞式降级行为（`POST /api/chat`）

- 按降级链顺序尝试，每次独立调用
- 当前模型失败 → 记录 WARN 日志 → 切换到下一个备选
- 成功时返回 ChatResponse，携带 `fallback` 字段（含原始模型标识）
- 全部失败 → 抛出 BusinessException，提示所有模型均不可用
- 不做同模型重试（阻塞式调用失败通常是确定性的，重试意义不大）

### 3.4 流式降级行为（`GET/POST /api/chat/stream`）

流式场景采用**两阶段策略**：

**阶段一：同模型重试**
- 流中断（网络断开、API 异常等）时，先对同一模型重试
- 重试前丢弃已收到的部分回复，重新发送完整 prompt
- 最多重试 `max-retries` 次（含首次请求，默认 3 次）
- 同模型重试全部失败后，进入阶段二

**阶段二：降级切换**
- 切换到降级链中的下一个备选模型
- 丢弃之前所有的部分回复，将原始 prompt 重新发送给新模型
- 新模型同样享有阶段一的重试机会
- 降级链耗尽 → 发送 SSE error 事件，客户端收到明确的失败信息

**递归深度保护**：
- 已尝试过的模型不再重复（防无限循环）
- 最大总尝试次数 = sum(降级链长度 × max-retries)，硬上限 15 次

### 3.5 降级链构建算法

```
resolve(requestedModel):
  chain = [requestedModel]
  current = requestedModel
  while chain.length < maxTotalAttempts:
    next = lookupChain(current) 的第一个不在 chain 中的模型
    if next == null: break
    chain.append(next)
    current = next
  return chain
```

### 3.6 ChatResponse 扩展

```java
public record ChatResponse(
    String model,           // 实际使用的模型 ID
    String content,         // 模型回复内容
    String conversationId,  // 对话 ID
    FallbackMeta fallback   // null 时序列化省略（非降级场景）
) {}

public record FallbackMeta(
    String requestedModel,  // 用户原始请求的模型
    boolean fallback        // 始终为 true
) {}
```

### 3.7 日志规范

| 级别 | 场景 | 格式 |
|------|------|------|
| WARN | 单次调用失败 | `Chat attempt failed for model '{}': {}` |
| WARN | 流式同模型重试 | `Stream retry {}/{} for model '{}': {}` |
| INFO | 降级成功 | `Fallback succeeded: '{}' → '{}' (attempt {})` |
| ERROR | 链耗尽 | `All fallback attempts exhausted for model '{}', tried: {}` |

### 3.8 SSE 流式错误事件

当降级链耗尽时，发送结构化 SSE 事件：

```
event: error
data: {"error":"all_models_failed","message":"所有模型均不可用，请稍后重试","attempted":["deepseek/deepseek-v4-flash","zhipu/glm-4.7-flash","minimax/MiniMax-M2.1"]}
```

## 4. 非功能需求

- 不影响现有 API 契约（ChatResponse.fallback 为 null 时不序列化，兼容旧客户端）
- 不修改异常处理体系（GlobalExceptionHandler 不变）
- 降级逻辑封装在独立组件中，ChatServiceImpl 通过依赖注入使用
- 遵循项目 OCP 原则：新增兜底策略不修改现有 Provider / Advisor 代码

## 5. 改动范围

| 文件 | 改动类型 | 说明 |
|------|---------|------|
| `chat/fallback/ChatFallbackProperties.java` | 新增 | 兜底策略配置（含 per-model chains） |
| `chat/fallback/FallbackChainResolver.java` | 新增 | 降级链构建（模型级匹配 + 去重 + 环检测） |
| `chat/fallback/FallbackEligibility.java` | 新增 | 异常可降级判定（SRP，独立于 Resolver） |
| `chat/fallback/FallbackAutoConfiguration.java` | 新增 | 启用配置属性 |
| `chat/fallback/StreamRetryHandler.java` | 新增 | 流式同模型重试逻辑 |
| `chat/dto/ChatResponse.java` | 修改 | 新增 FallbackMeta fallback 字段 |
| `chat/dto/FallbackMeta.java` | 新增 | 降级元数据 record |
| `chat/dto/ChatRequest.java` | 修改 | 新增 withModel() 方法 |
| `chat/service/impl/ChatServiceImpl.java` | 修改 | 集成降级逻辑（阻塞 + 流式） |
| `application.yml` | 修改 | 添加 fallback 配置段 |

## 6. 不做的事

- 不做按响应时间/错误率的动态模型选择（静态降级链即可）
- 不做降级事件的持久化/通知（仅日志）
- 不做模型健康检查/自动摘除
- 不做阻塞式调用的同模型重试（失败通常是确定性的）
- 不做流式中途续传（丢弃部分回复，重发完整 prompt）

## 7. 验收标准

- [ ] 主模型不可用时自动降级到备选，返回正常响应
- [ ] ChatResponse 在降级场景包含 `fallback.requestedModel` 和 `fallback.fallback=true`
- [ ] 内容过滤等用户侧错误不触发降级
- [ ] 全部模型不可用时返回明确错误信息
- [ ] 流式聊天支持同模型重试（max-retries 次）+ 降级切换
- [ ] 流式降级时丢弃旧回复，重新发送完整 prompt
- [ ] 不同模型拥有独立的降级链配置
- [ ] 配置开关可关闭降级功能
- [ ] 日志清晰记录降级过程（重试 + 切换）
- [ ] 新增自定义降级策略只需新增 Resolver 实现，零修改 ChatServiceImpl

## 8. OCP 验证

新增一个自定义降级策略（如按响应时间排序）需要：
1. 新增一个 Resolver 实现
2. 替换 Spring Bean 注册
3. **零修改** ChatServiceImpl、ChatController、GlobalExceptionHandler

## 9. 新增同类功能 Checklist

如需新增一种降级策略（如"按延迟排序"）：
1. 新增 `{Strategy}FallbackChainResolver` 实现
2. 在配置中切换 Bean
3. 不修改 ChatServiceImpl / ChatController / DTO / GlobalExceptionHandler
