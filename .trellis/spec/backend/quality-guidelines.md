# Quality Guidelines

> Code standards, forbidden patterns, and review criteria.

---

## Code Style

- **Java 21**：使用 record 定义 DTO，使用 var 局部变量类型推断
- **Spring Boot 3.5**：Jakarta EE 命名空间（`jakarta.*`）
- **编码**：UTF-8，LF 换行
- **缩进**：4 空格

---

## Design Principles — 面向对象设计七大原则

### SOLID（五大原则）

| 原则 | 缩写 | 定义 | 项目实践 |
|------|------|------|----------|
| **单一职责** | SRP | 一个类只有一个引起它变化的原因 | 每个类只做一件事；`OrphanChunkCleaner` 只管分片清理，`TeamBucketCleaner` 只管团队桶生命周期 |
| **开闭原则** | OCP | 对扩展开放，对修改关闭 | 新功能 = 新增类，不是改旧类；`BucketResolver` 新增场景只需扩展 `resolve()`，上传策略零改动 |
| **里氏替换** | LSP | 子类必须能替换其基类而不破坏正确性 | 接口实现必须遵守契约；`PersonalUploadStrategy` 和 `TeamUploadStrategy` 都实现 `UploadStrategy`，调用方无感知 |
| **接口隔离** | ISP | 客户端不应被迫依赖它不使用的接口 | 接口按职责拆分，不造大而全的接口；`TeamStatusService` 只暴露 `isTeamActive` / `isTeamMember`，不暴露团队 CRUD |
| **依赖倒置** | DIP | 高层模块不依赖低层模块，两者都依赖抽象 | `ChatService` 依赖 `ChatClientFactory` 接口而非具体实现；`ChunkUploadServiceImpl` 依赖 `TeamStatusService` 接口而非 `TeamMapper` |

### 补充原则

| 原则 | 缩写 | 定义 | 项目实践 |
|------|------|------|----------|
| **迪米特法则** | LoD | 一个对象应该对其他对象保持最少的了解 | Service 不直接操作其他模块的 Mapper；`rag` 模块需要团队状态时通过 `TeamStatusService` 接口查询，不直接注入 `TeamMapper` |
| **合成复用** | CRP | 优先使用对象组合而非继承来复用功能 | 用组合+委托替代继承；`TeamUploadStrategy` 组合 `DocumentValidator`、`FileStorageService`、`BucketResolver`，而非继承某个基类 |

### 强制规则

| 规则 | 说明 |
|------|------|
| **设计模式优先** | 策略、工厂、模板方法等主动运用，不能硬编码 |
| **OCP 强制** | 新功能 = 新增类，不是改旧类；加 Provider 不改 ChatService |
| **封装彻底** | 厂商差异、技术细节不泄漏到上层 |
| **DTO 隔离** | Entity 不暴露给前端，通过 DTO 转换 |
| **编程式事务** | `TransactionTemplate`，不用 `@Transactional` |
| **新增功能 Checklist** | PRD 必须验证"新增同类功能的步骤"确保零修改现有文件 |
| **批判式思考** | 每次编码前审视：设计模式、SOLID、OOP、可读性、可维护性 |

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
| Flyway | ~~已移除~~ → **已重新引入** | V4+ 迁移通过 Flyway 管理 |
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
