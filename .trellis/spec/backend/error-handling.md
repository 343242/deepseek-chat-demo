# Error Handling

> Error types, handling strategies, and response formats.

---

## Overview

统一异常处理通过 `GlobalExceptionHandler` 实现，所有 API 返回一致的错误格式。

---

## Error Response Format

```json
{
  "error": "error_type",
  "message": "友好描述",
  "status": 400
}
```

- `error` — 机器可读的错误类型标识
- `message` — 用户友好的中文描述（不含内部细节）
- `status` — HTTP 状态码

---

## Exception Hierarchy

| 异常类 | HTTP 状态 | error 字段 | 使用场景 |
|--------|----------|-----------|---------|
| `BusinessException` | 400 | `business_error` | 业务逻辑错误（统一替代 IllegalArgumentException） |
| `MethodArgumentNotValidException` | 400 | `validation_error` | `@Valid` 参数校验失败 |
| `ContentFilteredException` | 400 | `content_filtered` | 敏感词过滤 |
| `AuthenticationException` | 401 | `unauthorized` | 未认证 / Token 失效 |
| `AccessDeniedException` | 403 | `access_denied` | 权限不足 |
| `ModelNotFoundException` | 404 | `not_found` | 模型不存在 |
| `RateLimitExceededException` | 429 | `rate_limit_exceeded` | 请求过于频繁 |
| 通用 `Exception` | 500 | `internal_error` | 未预期的服务错误 |

---

## Rules

### ✅ DO

- 业务异常统一抛 `BusinessException`
- 错误消息用中文，面向用户友好
- 在 Service 层抛异常，Controller 不处理异常（交给 GlobalExceptionHandler）
- 参数校验用 `@Valid` + Jakarta Validation 注解

### ❌ DON'T

- 不要抛 `IllegalArgumentException`，用 `BusinessException` 替代
- 不要在错误消息中暴露堆栈、SQL、内部类名
- 不要在 Controller 里 try-catch 后返回手动构造的错误 JSON
- 不要用 HTTP 200 返回业务错误

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

## Security Error Handling

认证/授权错误不暴露具体原因：

```java
// 用户不存在、密码错误、账号禁用 — 统一返回 "用户名或密码错误"
if (user == null || !passwordEncoder.matches(password, user.getPassword())) {
    throw new BusinessException("用户名或密码错误");
}
```

防止枚举攻击：不告诉攻击者是"用户不存在"还是"密码错误"。
