# Error Handling

> Error types, handling strategies, and response formats.

---

## Overview

统一异常处理通过 `GlobalExceptionHandler` 实现。所有异常统一返回 **HTTP 200 + 业务码**，通过 `GlobalResponse.code` 区分错误类型。

---

## Error Response Format

```json
{
  "code": 200001,
  "message": "友好描述",
  "data": null
}
```

- `code` — 0 表示成功，非 0 表示错误码
- `message` — 用户友好的中文描述（不含内部细节）
- `data` — 成功时有值，失败时为 null

---

## Exception Hierarchy

### 三级异常体系

| 异常类 | 错误码范围 | 使用场景 |
|--------|----------|---------|
| `ClientException` | 100001–199999 | 客户端错误：参数错误、权限不足、重复提交、内容过滤 |
| `ServiceException` | 200001–299999 | 服务端错误：数据不存在、状态异常、业务逻辑不符合预期 |
| `RemoteException` | 300001–399999 | 第三方服务错误：模型调用失败、向量数据库不可用 |

### 继承关系

```
RuntimeException
  └── AbstractException (abstract, carries IErrorCode + detail)
        ├── ClientException
        │     ├── ContentFilteredException
        │     └── RateLimitExceededException
        ├── ServiceException
        │     └── ModelNotFoundException
        ├── RemoteException
        │     └── ProviderNotFoundException
        └── MessagingException
              ├── MessageConsumeException
              ├── MessagePublishException
              └── PermanentConsumeException
```

### 面向用户的专用异常

| 异常类 | 错误码 | 附加信息 | 使用场景 |
|--------|-------|---------|---------|
| `ContentFilteredException` | 100006 | — | 敏感词过滤 |
| `RateLimitExceededException` | 100005 | — | 请求过于频繁 |
| `ModelNotFoundException` | 203001 | modelId | 模型不存在 |
| `ProviderNotFoundException` | 300001 | providerId | 厂商未配置 |

### 消息总线异常（MessagingException 体系）

| 异常类 | 错误码 | 使用场景 |
|--------|-------|---------|
| `MessagingException` | — | 消息总线基础异常，不直接使用 |
| `MessageConsumeException` | 400002 | 消息消费处理失败 |
| `MessagePublishException` | 400001 | Producer 发送失败 |
| `PermanentConsumeException` | 400003 | 永久性消费错误（反序列化失败、payload 格式错误等），重试无意义，直接进 DLQ |

### 基础设施内部异常（不继承 AbstractException）

以下异常直接继承 `RuntimeException`，不经过 GlobalExceptionHandler 统一响应，用于基础设施内部控制流信号：

| 异常类 | 包 | 使用场景 |
|--------|---|---------|
| `ModelCircuitOpenException` | `infrastructure.fallback` | 熔断器开启，模型暂不可用，触发降级 |
| `ProbeTimeoutException` | `infrastructure.fallback` | 首包探测超时，触发立即降级到下一候选模型 |
| `ModelStreamException` | `infrastructure.stream` | 流式响应异常 |
| `ScopeExecutionException` | `infrastructure.concurrent` | 结构化并发作用域执行异常 |
| `ScopeClosedException` | `infrastructure.concurrent` | 作用域已关闭 |
| `ScopeTimeoutException` | `infrastructure.concurrent` | 作用域超时 |
| `ScopeViolationException` | `infrastructure.concurrent` | 作用域违规（如线程逃逸） |
| `SubtaskException` | `infrastructure.concurrent` | 子任务基础异常 |
| `SubtaskCancelledException` | `infrastructure.concurrent` | 子任务被取消 |
| `SubtaskFailedException` | `infrastructure.concurrent` | 子任务执行失败 |
| `SubtaskNotCompletedException` | `infrastructure.concurrent` | 子任务未完成 |
| `DocumentParseException` | `rag.parser` | 文档解析异常 |

### 兼容过渡

| 异常类 | 状态 | 说明 |
|--------|------|------|
| `BusinessException` | `@Deprecated` | 旧版异常，extends AbstractException，保留至下个大版本移除 |

---

## IErrorCode Interface

```java
public interface IErrorCode {
    int getCode();
    String getMessage();
}
```

每个枚举实现 `IErrorCode`，异常构造器接受 `IErrorCode`（实际传入对应分类枚举）：

```java
new ClientException(ClientErrorCode.VALIDATION_ERROR)     // 编译期类型安全
new ServiceException(ServiceErrorCode.USER_NOT_FOUND)
new RemoteException(RemoteErrorCode.PROVIDER_NOT_FOUND)
```

---

## Error Code Enums

| 枚举 | 范围 | 分类 |
|------|------|------|
| `ClientErrorCode` | 100001–105013 | 通用、认证、用户冲突、聊天客户端、RAG上传、团队客户端 |
| `ServiceErrorCode` | 200001–205007 | 通用、用户/角色/权限、会话、聊天、RAG、团队 |
| `RemoteErrorCode` | 300001–301010 | 厂商、模型超时、向量数据库、LLM 弹性层（熔断/限流/解析等） |
| `MessagingErrorCode` | 400001–400011 | 消息发送、消费、DLQ、熔断、Topic/Tag/Group 校验、消息体超限、配置无效 |
| `ErrorCode` (旧版) | 0–50099 | 保留兼容，逐步迁移 |

---

## Rules

### DO

- 客户端错误抛 `ClientException`，服务端错误抛 `ServiceException`，第三方服务错误抛 `RemoteException`
- 错误消息用中文，面向用户友好
- 在 Service 层抛异常，Controller 不处理异常（交给 GlobalExceptionHandler）
- 参数校验用 `@Valid` + Jakarta Validation 注解

### DON'T

- 不要在新代码中使用 `BusinessException`（已 Deprecated）
- 不要在错误消息中暴露堆栈、SQL、内部类名
- 不要在 Controller 里 try-catch 后返回手动构造的错误 JSON
- 不要使用不同的 HTTP 状态码区分业务错误——统一 HTTP 200 + 业务码

---

## Validation Pattern

```java
// DTO — 用 record + @Valid 注解
public record LoginRequest(
    @NotBlank(message = "用户名不能为空") String username,
    @NotBlank(message = "密码不能为空") String password,
    @NotBlank(message = "验证码ID不能为空") String captchaId,
    @NotBlank(message = "验证码不能为空") String captchaCode
) {}

// Controller — @Valid 触发校验
@PostMapping("/login")
public LoginResponse login(@Valid @RequestBody LoginRequest request, ...) { ... }
```

---

## Exception Usage Examples

```java
// 客户端错误
throw new ClientException(ClientErrorCode.BAD_REQUEST, "分片序号超出范围: " + chunkIndex);

// 服务端错误
throw new ServiceException(ServiceErrorCode.USER_NOT_FOUND);

// 第三方服务错误
throw new ProviderNotFoundException(providerId, "厂商未配置: " + providerId);

// 面向用户的专用异常
throw new ContentFilteredException("内容包含敏感词");
throw new ModelNotFoundException(modelId, "模型不存在: " + modelId);
```

---

## Security Error Handling

认证/授权错误不暴露具体原因：

```java
// 用户不存在、密码错误、账号禁用 — 统一返回 "用户名或密码错误"
if (user == null || !passwordEncoder.matches(password, user.getPassword())) {
    throw new ClientException(ClientErrorCode.LOGIN_FAILED);
}
```

防止枚举攻击：不告诉攻击者是"用户不存在"还是"密码错误"。

---

## Common Mistakes

### `instanceof` 守卫必须用 `AbstractException` 而非 `BusinessException`

降级/回退框架中判断"是否为不可降级异常"时，必须检查 `instanceof AbstractException`。

> **Warning**: `BusinessException` 是 `AbstractException` 的子类，但 `ClientException`/`ServiceException`/`RemoteException` 是 `BusinessException` 的兄弟类，不是子类。用 `instanceof BusinessException` 会遗漏所有新异常，导致用户错误意外触发模型回退。

```java
// Wrong — 新异常全部漏过
if (exception instanceof BusinessException) { ... }

// Correct — 覆盖所有面向用户的异常
if (exception instanceof AbstractException) { ... }
```
