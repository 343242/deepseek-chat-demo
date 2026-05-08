# Quality Guidelines

> Code standards, forbidden patterns, and review criteria.

---

## Code Style

- **Java 21**：使用 record 定义 DTO，使用 var 局部变量类型推断
- **Spring Boot 3.5**：Jakarta EE 命名空间（`jakarta.*`）
- **编码**：UTF-8，LF 换行
- **缩进**：4 空格

---

## Design Principles

| 原则 | 实现 |
|------|------|
| 单一职责 | 每个类只做一件事 |
| 依赖倒置 | Advisor 依赖接口（`RateLimiter`、`ContentFilterService`） |
| 开闭原则 | 新增限流算法只需实现接口，不改动已有代码 |
| DTO 隔离 | Entity 不暴露给前端，通过 DTO 转换 |
| 编程式事务 | `TransactionTemplate`，不用 `@Transactional` |

---

## Security Checklist

每次改动涉及以下内容时必须检查：

- [ ] **密码**：BCrypt 哈希，不存明文
- [ ] **Token**：HttpOnly Cookie 存储，不在 JSON body 返回
- [ ] **权限**：`@PreAuthorize` 注解保护接口
- [ ] **输入校验**：`@Valid` + Jakarta Validation
- [ ] **状态枚举**：用枚举类约束，不接受裸 Integer
- [ ] **唯一约束**：业务层先查重 + 数据库 partial unique index 兜底
- [ ] **软删除**：查询条件必须包含 `deleted = 0`
- [ ] **错误消息**：认证失败不暴露具体原因

---

## Forbidden Patterns

| 模式 | 替代方案 | 原因 |
|------|---------|------|
| `@Transactional` | `TransactionTemplate` | 精确控制事务边界 |
| JPA / Hibernate | MyBatis-Plus | 项目已全量替换 |
| `IllegalArgumentException` | `BusinessException` | 统一异常处理 |
| `System.out.println` | SLF4J Logger | 日志框架 |
| 裸 Integer 状态字段 | 枚举类 + 校验 | 防止无效值 |
| Controller 内 try-catch | GlobalExceptionHandler | 统一错误格式 |
| 返回 Entity 给前端 | DTO 转换 | 隔离内部结构 |
| Token 放 JSON body | HttpOnly Cookie | 安全性 |
| Flyway | schema.sql | 已移除 |
| `docker pull *:alpine` | `*:bookworm` | 项目规则 |
| 不经允许拉 Docker 镜像 | 先问用户 | 项目规则 |

---

## DTO Rules

- Request DTO：用 `record`，加 `@Valid` 注解
- Response DTO：用 `record`，不加敏感信息（如 permissions 列表）
- 字段校验：`@NotBlank`、`@Email`、`@Size`、`@Pattern`
- email 统一 `toLowerCase` 处理

---

## Git Commit Rules

- 每次改动后必须 commit + push
- commit message 要写清楚改了什么
- 格式参考：`feat: ...` / `fix: ...` / `refactor: ...` / `docs: ...` / `chore: ...`
