# Logging Guidelines

> Structured logging, log levels, and logging practices.

---

## Framework

SLF4J + Logback（Spring Boot 默认）。

```java
private static final Logger log = LoggerFactory.getLogger(MyClass.class);
```

---

## Log Levels

| 级别 | 使用场景 | 示例 |
|------|---------|------|
| `ERROR` | 影响功能的异常 | 数据库连接失败、外部 API 调用失败 |
| `WARN` | 可恢复的异常/异常情况 | JWT 认证失败、限流触发 |
| `INFO` | 关键业务节点 | 用户登录、Token 刷新、模型列表更新 |
| `DEBUG` | 开发调试信息 | 验证码生成、权限缓存命中/未命中 |
| `TRACE` | 详细调用链 | 不常用 |

---

## Rules

### ✅ DO

- 使用参数化日志（不拼接字符串）：`log.debug("Captcha generated: id={}", captchaId)`
- 安全相关事件记录 WARN：认证失败、Token 过期
- 关键操作记录 INFO：登录成功、角色变更

### ❌ DON'T

- 不在日志中记录密码、Token 原文
- 不用 `e.printStackTrace()`
- 不在循环中打 INFO 以上级别的日志
- 不记录敏感用户信息（完整手机号、邮箱等）

---

## Profile 差异

| Profile | 包日志级别 | 说明 |
|---------|-----------|------|
| `dev` | `com.demo.deepseekchat: DEBUG` | 开发调试 |
| `stable` | `com.demo.deepseekchat: INFO` | 测试环境 |
| `prod` | `com.demo.deepseekchat: WARN` | 生产最小化 |
