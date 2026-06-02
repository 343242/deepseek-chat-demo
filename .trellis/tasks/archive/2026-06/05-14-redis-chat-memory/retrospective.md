# Retrospective: Replace JDBC ChatMemory with Redis

## 任务概要
将 Spring AI 的 ChatMemory 存储从 JDBC（PostgreSQL）替换为 Redis（Lettuce）。

## 做对的事
1. **果断砍掉 Jedis/RediSearch/Gson/jspecify**：Spring AI 原始实现过度设计，ChatMemory 只需 4 个 CRUD 方法，不需要全文检索引擎
2. **零新增依赖**：全部复用项目已有的 `spring-boot-starter-data-redis`（Lettuce）和 Jackson
3. **中间 DTO 隔离序列化**：不直接序列化 Spring AI 的 Message 类，避免 Jackson 多态反序列化陷阱
4. **审查后修正了关键问题**：原子性（Pipeline）、数据结构（Sorted Set）、DTO 规范（record）、spec 合规

## 做错的事 / 教训

### 1. 用户隔离设计不够严谨
**问题**：当前 key 结构 `chat:memory:{conversationId}` 依赖上层把 userId 编入 conversationId（`u_{userId}_{rawId}`），是"约定式隔离"而非"强制隔离"。

**根本原因**：`ChatMemoryRepository` 接口没有 userId 参数——这是 Spring AI 的设计局限。但我们的实现可以做得更好：
- `findConversationIds()` 用 `SCAN chat:memory:*` 会返回所有用户的会话
- 如果上层任何调用链漏掉 `buildIsolatedId()`，直接暴露跨用户数据

**正确做法**：
- 在 Repository 层面，key 设计应该显式包含 userId：`chat:memory:{userId}:{rawConversationId}`
- 但由于 `ChatMemoryRepository` 接口没有 userId 参数，实际上做不到
- **可行的补救**：`findConversationIds()` 应该接受过滤参数，或在 Repository 外层包装一个用户隔离层
- **当前状态**：`findConversationIds()` 在项目中**无任何调用**，暂时安全，但这是一个潜在的隔离漏洞

**长期建议**：
- 如果 Spring AI 未来版本给 `ChatMemoryRepository` 加上 userId 参数，立即重构 key 结构
- 或者在上层（`ChatServiceImpl`）维护一个 `UserChatMemoryService` 装饰器，在调用 Repository 之前/之后做 userId 校验
- 如果要做真正的用户隔离，考虑在 Redis 层用 ACL（Redis 6+ 的用户权限系统）

### 2. 第一次实现没有充分思考数据结构选型
**问题**：第一版直接用了 String（全量 JSON 数组），没有比较不同 Redis 数据结构的优劣。

**正确做法**：编码前先列出候选方案（String/List/Hash/Sorted Set），按场景（读多写少/增量追加/范围查询/TTL）评估，选最优解。本次 Sorted Set 才是正确选择——增量追加、天然有序、范围取值、滑动窗口友好。

### 3. 静默吞异常是个坏习惯
**问题**：第一版 `findByConversationId` 反序列化失败时返回空列表，上层完全不知道数据损坏。

**正确做法**：数据完整性问题应该**快速失败（fail-fast）**，让调用方知道并决定如何处理。已修正为抛出 RuntimeException。

### 4. 没有第一时间检查 spec 合规
**问题**：配置类放在了 `memory/` 包而非 `config/`，DTO 用了 class 而非 record，Redis Key 模式未在 spec 注册。都是审查时才发现的。

**正确做法**：编码前先读相关 spec（directory-structure、quality-guidelines、database-guidelines），编码后对照检查。不依赖"审查时发现"。

## 改进 Checklist
- [ ] 编码前读相关 spec
- [ ] 数据结构选型先列方案再选最优
- [ ] 异常处理遵循 fail-fast
- [ ] DTO 一律用 record
- [ ] 配置类放 config/ 包
- [ ] Redis Key 在 database-guidelines 注册
- [ ] 用户隔离不能只靠命名约定，要考虑防御性设计
