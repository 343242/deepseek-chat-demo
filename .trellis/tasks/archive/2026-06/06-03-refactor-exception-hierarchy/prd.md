# 重构异常体系与全局异常处理器

## Goal

参考 Ragent 基础设施层研究报告前七章的建议，对 smart-rag 项目的异常体系、错误码规范、全局异常处理器进行系统化重构，建立统一的三层异常分类、规范的错误码体系和单一全局异常处理器。

## What I already know

### 当前异常体系

```
RuntimeException
  ├── BusinessException (ErrorCode + detail, 5 个构造器)
  │     └── ProviderNotFoundException (providerId)
  ├── ModelNotFoundException (modelId) — 平铺，无 ErrorCode
  ├── ContentFilteredException — 平铺，无 ErrorCode
  ├── RateLimitExceededException — 平铺，无 ErrorCode
  ├── SubtaskException (abstract)
  │     ├── SubtaskFailedException
  │     ├── SubtaskCancelledException
  │     └── SubtaskNotCompletedException
  ├── ScopeExecutionException → ScopeTimeoutException
  ├── ScopeClosedException
  ├── ScopeViolationException
  ├── ModelStreamException (infrastructure.stream)
  ├── ProbeTimeoutException (infrastructure.fallback)
  ├── ModelCircuitOpenException (infrastructure.fallback)
  ├── DocumentParseException (rag.parser)
  └── FileStorageException (inner class, MinioFileStorageService)
```

### 当前错误码

- 单一 `ErrorCode` 枚举，~60 条目，`int code` + `String message`
- **码值碰撞**: 40001 同时用于 `VALIDATION_ERROR` 和 `MODEL_EMPTY`
- 无 `IErrorCode` 接口，无按模块拆分

### 当前异常处理器

- **GlobalExceptionHandler** (全局): `mapToHttpStatus()` 将 ErrorCode 映射为真实 HTTP 状态码
- **ChatExceptionHandler** (chat 包): `mapHttpStatus()` 大部分业务错误返回 **HTTP 200** + 非零 `code`（前端通过 GlobalResponse.code 判断业务错误）
- 两个处理器逻辑高度重复（6 个相同 @ExceptionHandler），差异仅在 HTTP 状态映射策略

### 关键约束

- `ProviderNotFoundException` 的构造器 `super(message)` 走的是 `BusinessException(String)` 兼容构造器，映射到 `BAD_REQUEST` 而非 `PROVIDER_NOT_FOUND` — **这是一个 bug**
- `ModelNotFoundException`、`ContentFilteredException`、`RateLimitExceededException` 没有携带 `ErrorCode`，处理器里硬编码了映射
- SSE 流式端点的异常无法被 `@RestControllerAdvice` 捕获（两个处理器的 Javadoc 都承认了这点）

## Assumptions (temporary)

- 三层异常分类 (ClientException / ServiceException / RemoteException) 是正确方向
- 错误码保持 `int` 类型（而非报告建议的 `String`），因为前端已有约定
- `SubtaskException` / `Scope*Exception` 属于并发框架内部异常，不改入三层体系
- 幂等控制框架 (Phase 2) 不在本任务范围

## Decision 1: 异常层级 (ADR-lite)

**Context**: 当前 BusinessException 只有 ProviderNotFoundException 一个子类，ModelNotFoundException/ContentFilteredException/RateLimitExceededException 平铺继承 RuntimeException，散落异常无统一基类。报告建议三层分类。
**Decision**: 采用方案 A — AbstractException → ClientException / ServiceException / RemoteException 三层分类。
**Consequences**: 类型层面可区分"谁的错"；GlobalExceptionHandler 可按异常基类统一处理而非逐个枚举；BusinessException 标记 @Deprecated 过渡。

### 异常映射

| 异常类型 | 继承 | 错误码前缀 | 使用场景 |
|---------|------|-----------|---------|
| ClientException | AbstractException | A | 参数错误、权限不足、重复提交、内容过滤 |
| ServiceException | AbstractException | B | 数据不存在、状态异常、业务规则违反 |
| RemoteException | AbstractException | C | 模型调用超时、向量数据库连接失败、厂商未配置 |

| 现有异常 | 迁移目标 |
|---------|---------|
| ModelNotFoundException | ServiceException |
| ContentFilteredException | ClientException |
| RateLimitExceededException | ClientException |
| ProviderNotFoundException | RemoteException |

## Decision 2: HTTP 状态码映射策略 (ADR-lite)

**Context**: 两个处理器策略不同 — GlobalExceptionHandler 用真实 HTTP 状态码，ChatExceptionHandler 大部分返回 HTTP 200 + 业务码。目前无前端，不会破坏兼容性。
**Decision**: 全局统一为 Option 1 — 业务异常一律 HTTP 200 + `GlobalResponse.code`，只有框架级异常（认证/权限/参数校验）才用真实 HTTP 状态码。
**Consequences**: 合并为单一 GlobalExceptionHandler；前端只需一套错误处理逻辑；SSE 场景天然兼容。

### 映射规则

| 异常类型 | HTTP 状态码 | 说明 |
|---------|-----------|------|
| ClientException | 200 + 非零 code | 业务级客户端错误 |
| ServiceException | 200 + 非零 code | 业务级服务端错误 |
| RemoteException | 200 + 非零 code | 第三方服务错误 |
| MethodArgumentNotValidException | 400 | 框架级参数校验 |
| AuthenticationException | 401 | 未认证 |
| AccessDeniedException | 403 | 无权限 |
| Exception (兜底) | 500 | 未预期错误 |

## Decision 3: 错误码格式 (ADR-lite)

**Context**: 当前 ErrorCode 用 int，报告建议 String。无前端历史包袱。
**Decision**: 保持 int，重新规划码值区间与三层异常对应。
**Consequences**: GlobalResponse.code 字段类型不变；code 区间即可判断异常分类。

### 码值区间规划

| 范围 | 分类 | 说明 |
|------|------|------|
| 0 | SUCCESS | 成功 |
| 100001–199999 | ClientException (A类) | 参数、权限、内容过滤、限流 |
| 200001–299999 | ServiceException (B类) | 数据不存在、状态异常、业务规则 |
| 300001–399999 | RemoteException (C类) | 模型调用、向量库、厂商不可用 |

### 旧码 → 新码迁移

| 旧码 | 旧名 | 新码 | 新名 | 分类 |
|------|------|------|------|------|
| 0 | SUCCESS | 0 | SUCCESS | - |
| 40000 | BAD_REQUEST | 100001 | BAD_REQUEST | Client |
| 40001 | VALIDATION_ERROR | 100002 | VALIDATION_ERROR | Client |
| 40100 | UNAUTHORIZED | 100003 | UNAUTHORIZED | Client |
| 40300 | FORBIDDEN | 100004 | FORBIDDEN | Client |
| 42900 | RATE_LIMITED | 100005 | RATE_LIMITED | Client |
| 40004 | CONTENT_FILTERED | 100006 | CONTENT_FILTERED | Client |
| 40400 | NOT_FOUND | 200001 | NOT_FOUND | Service |
| 50000 | INTERNAL_ERROR | 200002 | INTERNAL_ERROR | Service |
| 40002 | MODEL_NOT_FOUND | 200003 | MODEL_NOT_FOUND | Service |
| 40003 | PROVIDER_NOT_FOUND | 300001 | PROVIDER_NOT_FOUND | Remote |
| (新) | - | 300002 | MODEL_TIMEOUT | Remote |
| (新) | - | 300003 | VECTOR_DB_UNAVAILABLE | Remote |
| 10001–10013 | 认证模块 | 110001–110013 | 认证模块 | Client |
| 20001–20011 | 用户/角色 | 120001–120011 | 用户/角色 | Client/Service |
| 30001–30003 | 会话模块 | 220001–220003 | 会话模块 | Service |
| 50001–50013 | RAG/上传 | 230001–230013 | RAG/上传 | Service |
| 55001–55020 | 团队模块 | 240001–240020 | 团队模块 | Client/Service |

> 注：最终码值在实现时细化，上表为区间规划示意。

## Decision 4: ErrorCode 拆分粒度 (ADR-lite)

**Context**: 单枚举 60+ 条目，三层异常体系提供自然拆分依据。
**Decision**: 选项 2 — IErrorCode 接口 + 3 个枚举（ClientErrorCode / ServiceErrorCode / RemoteErrorCode）。
**Consequences**: ClientException 构造器接受 ClientErrorCode，类型安全；GlobalResponse.error(IErrorCode) 接口多态。

### 结构

```java
public interface IErrorCode {
    int getCode();
    String getMessage();
}

public enum ClientErrorCode implements IErrorCode { ... }   // 100001–199999
public enum ServiceErrorCode implements IErrorCode { ... }  // 200001–299999
public enum RemoteErrorCode implements IErrorCode { ... }   // 300001–399999
```

### 异常类与枚举绑定

- `ClientException(IErrorCode)` 构造器 — 实际传入 ClientErrorCode
- `ServiceException(IErrorCode)` 构造器 — 实际传入 ServiceErrorCode
- `RemoteException(IErrorCode)` 构造器 — 实际传入 RemoteErrorCode

## Decision 5: 散落领域异常处理 (ADR-lite)

**Context**: ProbeTimeoutException、ModelCircuitOpenException 等是内部信号异常，不经过 GlobalExceptionHandler。
**Decision**: 只收拢面向用户的异常（ModelNotFoundException 等），内部信号异常保持现状不动。
**Consequences**: 并发框架异常、降级信号异常零风险；改动集中在 infrastructure.exception 包。

### 收拢范围

| 异常 | 动作 | 目标 |
|------|------|------|
| ModelNotFoundException | 改为继承 ServiceException | infrastructure.exception |
| ContentFilteredException | 改为继承 ClientException | infrastructure.exception |
| RateLimitExceededException | 改为继承 ClientException | infrastructure.exception |
| ProviderNotFoundException | 修复构造器 bug，改为继承 RemoteException | infrastructure.exception |

### 不动范围

- ProbeTimeoutException — 降级信号，保持 infrastructure.fallback
- ModelCircuitOpenException — 熔断信号，保持 infrastructure.fallback
- ModelStreamException — 流式错误，保持 infrastructure.stream
- DocumentParseException — ETL 内部，保持 rag.parser
- FileStorageException — 存储内部，保持 MinioFileStorageService
- SubtaskException / Scope*Exception — 并发框架，保持 infrastructure.concurrent

## Open Questions

(无 — 所有决策已确认)

## Requirements (evolving)

- [ ] 建立统一的异常层级，所有面向用户的业务异常收拢到统一基类
- [ ] 修复 ErrorCode 40001 码值碰撞
- [ ] 修复 ProviderNotFoundException 构造器 bug
- [ ] 合并 GlobalExceptionHandler 和 ChatExceptionHandler 为单一处理器
- [ ] 散落的异常类（ModelNotFoundException 等）统一携带 ErrorCode
- [ ] 所有异常处理有统一的日志策略

## Acceptance Criteria (evolving)

- [ ] 不存在两个 `@RestControllerAdvice` 处理相同的异常类型
- [ ] 所有 `infrastructure.exception` 包下的异常类继承自统一基类
- [ ] ErrorCode 无码值碰撞
- [ ] 现有测试全部通过
- [ ] 新增异常体系的单元测试

## Definition of Done

- 编译通过，现有测试绿色
- 新增代码有单元测试覆盖
- Impact analysis 通过（无意外影响范围）
- gitnexus_detect_changes 验证

## Out of Scope

- 幂等控制框架 (@IdempotentSubmit / @IdempotentConsume)
- SSE 心跳 / onOpen 回调
- GlobalResponse 增加 traceId / timestamp 字段
- 并发框架异常 (SubtaskException / Scope*Exception) 的重构
- VectorCollectionAlreadyExistsException（向量数据库层异常）

## Technical Notes

### 关键文件

- `src/main/java/com/smart/rag/infrastructure/exception/BusinessException.java`
- `src/main/java/com/smart/rag/infrastructure/exception/errorcode/ErrorCode.java`
- `src/main/java/com/smart/rag/infrastructure/exception/GlobalExceptionHandler.java`
- `src/main/java/com/smart/rag/chat/controller/ChatExceptionHandler.java`
- `src/main/java/com/smart/rag/infrastructure/exception/ModelNotFoundException.java`
- `src/main/java/com/smart/rag/infrastructure/exception/ContentFilteredException.java`
- `src/main/java/com/smart/rag/infrastructure/exception/RateLimitExceededException.java`
- `src/main/java/com/smart/rag/infrastructure/exception/ProviderNotFoundException.java`

### 参考文档

- `docs/ragent-design-reflection/framework-infrastructure-layer.md` 第一至七章
