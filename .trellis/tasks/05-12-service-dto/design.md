# Task: Service 层返回类型领域化 — 消除 Map<String, Object>

## 背景

当前多个 Service 方法返回 `Map<String, Object>`，问题：
1. **无类型安全** — 调用方不知道 Map 里有什么 key
2. **无文档价值** — 看接口签名无法理解返回结构
3. **分页不统一** — 手动拼 `content/page/size/total`，conversation 模块直接返回 `List` 没分页信息

## 目标

1. 定义通用分页封装 `PagedResult<T>`（替代 Map 拼接的分页数据）
2. 为每个 Map 返回值定义专用 DTO
3. Service 接口和实现全部改用强类型返回
4. 分页接口统一返回 `GlobalResponse<PagedResult<T>>`

## 新增类型

### 1. `PagedResult<T>` — 通用分页封装

位置：`com.demo.chat.common.response.PagedResult<T>`

```java
public record PagedResult<T>(
    List<T> content,      // 当前页数据
    int page,             // 当前页码（从 1 开始）
    int size,             // 每页大小
    long total,           // 总记录数
    int totalPages        // 总页数
) {
    /** 从 MyBatis-Plus Page 转换 */
    public static <T> PagedResult<T> of(Page<T> page) { ... }
    
    /** 从 MyBatis-Plus Page + 转换函数 */
    public static <E, T> PagedResult<T> of(Page<E> page, Function<E, T> converter) { ... }
}
```

### 2. `PageRequest` — 通用分页请求

位置：`com.demo.chat.common.request.PageRequest`

```java
public record PageRequest(
    @Min(1) int page,     // 页码，默认 1
    @Min(1) @Max(500) int size  // 每页大小，默认 20
) {
    public static PageRequest of(int page, int size) { ... }
    public static PageRequest defaults() { return new PageRequest(1, 20); }
}
```

### 3. User 模块专用 DTO

- `UserVO` — 用户视图对象（替代 `toSafeMap`）
- `UserStatusUpdateResult` — 状态更新结果
- `RoleAssignResult` — 角色分配结果
- `UserDeleteResult` — 用户删除结果
- `RoleDetailVO` — 角色详情（含权限列表）

### 4. Conversation 模块

- ConversationController.list 返回 `GlobalResponse<PagedResult<ConversationSummary>>`

## 改造计划

### Phase 0: 基础类型
- [0.1] 创建 `common.response.PagedResult<T>`
- [0.2] 创建 `common.request.PageRequest`
- [0.3] 编译通过

### Phase 1: user 模块 DTO + Service 改造
- [1.1] 创建 `UserVO`（替代 toSafeMap）
- [1.2] 创建 `UserStatusUpdateResult`、`RoleAssignResult`、`UserDeleteResult`
- [1.3] 创建 `RoleDetailVO`
- [1.4] `SysUserService` 接口改签名
- [1.5] `SysUserServiceImpl` 改实现
- [1.6] `SysRoleService` + `SysRoleServiceImpl` 改签名 + 实现
- [1.7] `UserController` 适配新返回类型
- [1.8] `RoleController` 适配新返回类型
- [1.9] 更新测试 JSON path
- [1.10] 编译 + 全量测试通过

### Phase 2: conversation 模块分页改造
- [2.1] `ConversationService.list` 改返回 `PagedResult<ConversationSummary>`
- [2.2] `ConversationServiceImpl` 改实现（使用 PagedResult.of）
- [2.3] `ConversationController.list` 接收 `PageRequest`（或保持 @RequestParam）
- [2.4] 编译 + 测试通过

### Phase 3: 清理 + commit
- [3.1] 确认所有 Map<String, Object> 返回已消除
- [3.2] 更新 API-DOCS.md 反映新响应格式
- [3.3] 全量编译 + 测试
- [3.4] Git commit + push

## 约束

1. Controller 接收分页参数保持 `@RequestParam`（前端习惯 query params），内部转 `PageRequest`
2. `PagedResult` 的 converter 函数避免 N+1，转换在内存中完成
3. 每个模块改造后编译 + 测试通过才进入下一个
4. SSE 流式接口不受影响

## 验收标准

- [x] Service 层零 Map<String, Object> 返回
- [x] 分页接口统一返回 PagedResult<T>
- [x] 全量测试通过
- [x] API-DOCS.md 已更新
