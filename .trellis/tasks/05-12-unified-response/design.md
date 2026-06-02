# Task: 统一响应类型 GlobalResponse + 错误码体系

## 背景

当前系统响应风格混乱：
- 成功响应：有的直接返回实体、有的返回 `Map.of`、有的返回 `ResponseEntity`
- 错误响应：`ErrorResponse` 放在 `chat.dto` 包下，其他模块不应依赖
- `BusinessException` 只有 message 字段，没有错误码，前端无法精确处理

## 目标

1. 统一所有接口返回 `GlobalResponse<T>` 包装
2. 建立结构化错误码体系 `ErrorCode`
3. 增强 `BusinessException` 支持 `ErrorCode`
4. 改造 GlobalExceptionHandler 适配新体系
5. **模块逐个改造，前一个确认无问题后才动下一个**

## 设计

### GlobalResponse<T>

位置：`com.demo.chat.common.response.GlobalResponse<T>`

```java
public record GlobalResponse<T>(
    int code,       // 0=成功, 非0=错误码
    String message, // 友好提示
    T data          // 业务数据（成功时有值，失败时为 null）
) {
    public static <T> GlobalResponse<T> ok(T data) { ... }
    public static <T> GlobalResponse<T> ok(T data, String message) { ... }
    public static GlobalResponse<Void> ok() { ... }
    public static <T> GlobalResponse<T> error(ErrorCode errorCode) { ... }
    public static <T> GlobalResponse<T> error(ErrorCode errorCode, String detail) { ... }
}
```

### ErrorCode

位置：`com.demo.chat.common.errorcode.ErrorCode`

按模块分段：
- 通用 0xxxx：SUCCESS, BAD_REQUEST, UNAUTHORIZED, FORBIDDEN, NOT_FOUND, RATE_LIMITED, INTERNAL_ERROR, VALIDATION_ERROR
- 认证 10xxx：CAPTCHA_INVALID, CAPTCHA_RATE_LIMIT, LOGIN_FAILED, TOKEN_EXPIRED, TOKEN_INVALID, USER_DISABLED, CAPTCHA_PARAM_MISSING, CAPTCHA_FORMAT_ERROR
- 用户 20xxx：USERNAME_EXISTS, EMAIL_EXISTS, USER_NOT_FOUND, USER_STATUS_INVALID, OLD_PASSWORD_ERROR, ROLE_NOT_FOUND, ROLE_NAME_EXISTS, PERMISSION_NOT_FOUND, PERMISSION_NAME_EXISTS, PERMISSION_KEY_EXISTS
- 会话 30xxx：CONVERSATION_NOT_FOUND, CONVERSATION_ACCESS_DENIED
- 聊天 40xxx：MODEL_NOT_FOUND, PROVIDER_NOT_FOUND, CONTENT_FILTERED, MODEL_EMPTY
- RAG 50xxx：UPLOAD_FILE_EMPTY, UPLOAD_FILE_TOO_LARGE, UPLOAD_MIME_UNSUPPORTED, DOCUMENT_NOT_FOUND, DOCUMENT_OWNERSHIP_DENIED, ETL_NO_RESULT, ETL_FAILED, UPLOAD_LIST_EMPTY, USAGE_PARAM_MISSING

每个 ErrorCode 持有 `code`（int）和 `message`（默认友好提示）。

### BusinessException 增强

```java
public class BusinessException extends RuntimeException {
    private final ErrorCode errorCode;
    private final String detail; // 可选，覆盖默认 message

    public BusinessException(ErrorCode errorCode) { ... }
    public BusinessException(ErrorCode errorCode, String detail) { ... }
    
    // 兼容旧代码：纯 message 构造（映射到 BAD_REQUEST）
    public BusinessException(String message) {
        this(ErrorCode.BAD_REQUEST, message);
    }
}
```

### GlobalExceptionHandler 改造

- 所有异常处理器返回 `GlobalResponse<Void>` + 对应 HTTP status
- `handleBusiness` 从 exception 取 errorCode 生成响应
- `handleValidation` 使用 `VALIDATION_ERROR` 错误码

## 改造计划

### Phase 0: 基础设施（不改任何现有接口）
- [0.1] 创建 `com.demo.chat.common.response.GlobalResponse<T>`
- [0.2] 创建 `com.demo.chat.common.errorcode.ErrorCode`
- [0.3] 增强 `BusinessException`（保留旧构造器兼容）
- [0.4] 改造 `GlobalExceptionHandler` 适配新体系
- [0.5] 删除旧的 `chat.dto.ErrorResponse`（被 GlobalResponse 替代）
- [0.6] 编译 + 全量测试通过

### Phase 1: user 模块改造
- [1.1] AuthController → 所有方法返回 GlobalResponse<T>
- [1.2] UserController → 所有方法返回 GlobalResponse<T>
- [1.3] RoleController → 所有方法返回 GlobalResponse<T>
- [1.4] AuthServiceImpl → 所有 throw new BusinessException("xxx") 改为 throw new BusinessException(ErrorCode.XXX)
- [1.5] SysUserServiceImpl → 同上
- [1.6] SysRoleServiceImpl → 同上
- [1.7] SysPermissionServiceImpl → 同上
- [1.8] 编译 + 全量测试通过
- [1.9] 验收：用 curl 测试各 user 模块接口的响应格式

### Phase 2: conversation 模块改造
- [2.1] ConversationController → 所有方法返回 GlobalResponse<T>
- [2.2] ConversationServiceImpl → BusinessException 改用 ErrorCode
- [2.3] ConversationMessageServiceImpl → 同上
- [2.4] 编译 + 测试通过

### Phase 3: chat 模块改造
- [3.1] ChatController → SSE 流式除外，其他返回 GlobalResponse<T>
- [3.2] ModelParamsController → 返回 GlobalResponse<T>
- [3.3] PromptController → 返回 GlobalResponse<T>
- [3.4] UsageController → 返回 GlobalResponse<T>
- [3.5] ChatServiceImpl → BusinessException 改用 ErrorCode
- [3.6] ModelRouter → 同上
- [3.7] 编译 + 测试通过

### Phase 4: rag 模块改造
- [4.1] DocumentController → 返回 GlobalResponse<T>
- [4.2] DocumentValidator → BusinessException 改用 ErrorCode
- [4.3] EtlPipelineServiceImpl → 同上
- [4.4] EtlDispatchServiceImpl → 同上
- [4.5] DocumentApplicationServiceImpl → 同上
- [4.6] 编译 + 测试通过

### Phase 5: 清理 + 文档
- [5.1] 确认旧 ErrorResponse 已删除
- [5.2] 确认 BusinessException 旧构造器可移除（如无外部使用）
- [5.3] 更新 API-DOCS.md 反映新响应格式
- [5.4] 更新 README.md 设计原则
- [5.5] 最终全量编译 + 测试
- [5.6] Git commit + push

## 约束

1. SSE 流式接口（/api/chat/stream）不走 GlobalResponse 包装，保持 text/event-stream 原样
2. 每个模块改造后必须编译 + 全量测试通过才进入下一个
3. BusinessException 保留 `BusinessException(String message)` 旧构造器直到所有模块改造完毕
4. GlobalResponse 序列化时 null data 字段也要保留（`@JsonInclude(Include.ALWAYS)`）

## 验收标准

- [x] 所有非流式接口响应格式统一为 `{"code":0,"message":"ok","data":...}`
- [x] 所有错误响应格式统一为 `{"code":40001,"message":"友好提示","data":null}`
- [x] 前端可通过 code 精确识别错误类型
- [x] 全量测试通过
- [x] API-DOCS.md 已更新
