# 复盘：Conversation 模块 Code Review 修复

## 背景

conversation 模块从 chat 模块抽离后，派发三个子代理分别进行架构、安全、代码质量审查。
共发现 5 项严重问题 + 8 项建议改进。

## 发现的问题及修复

### 🔴 严重问题

#### 1. 双写事务缺失
- **问题**：`saveMessagesAndNotify()` 中 USER 消息写入、ASSISTANT 消息写入、会话计数更新三个操作不在同一事务中，异常被 catch 吞掉
- **根因**：没有考虑 Spring AI 的 memory 写入和我们的 message 表写入是两个独立操作，中间任何一个失败都会导致数据不一致
- **正确做法**：用编程式事务（TransactionTemplate）包装 message 写入 + 会话计数更新，保证原子性
- **教训**：涉及多表写入的操作必须用事务保证原子性，不能依赖 try-catch 静默处理

#### 2. N+1 查询
- **问题**：`buildMessageTree()` 对每个根消息执行一次 `selectChildren()`，50 轮对话 = 51 条 SQL
- **根因**：先查出根消息列表再循环查子消息，典型的 ORM N+1 反模式
- **正确做法**：一次查出所有消息，内存中 `groupingBy(parentId)` 分组
- **教训**：树形结构的查询应该用一次全量查询 + 内存组装，而不是递归/循环查询

#### 3. SQL 拼接分页
- **问题**：`wrapper.last("LIMIT " + size + " OFFSET " + offset)` 直接拼接 SQL
- **根因**：图省事用了 `last()` 而不是 MyBatis-Plus 的分页插件
- **正确做法**：用 `Page` + `selectPage`
- **教训**：项目已有分页插件就应该统一使用，不要走捷径

#### 4. getOrCreate 并发竞态
- **问题**：两个线程同时判断不存在然后同时 insert，异常被 `ensureConversationExists` 吞掉
- **根因**：check-then-act 模式没有考虑并发，且唯一约束冲突的异常被泛化 catch 吞掉
- **正确做法**：catch `DuplicateKeyException` 后重新查询返回（唯一约束是最后的防线）
- **教训**：并发场景下必须考虑竞态条件，唯一约束是数据库层面的最后保障，不能把它的异常当错误处理

#### 5. 异常静默吞掉
- **问题**：`log.warn("...", e.getMessage())` 丢失异常栈，核心业务功能失败无感知
- **根因**：为了避免影响主流程（响应已生成），把所有异常都 catch 了
- **正确做法**：区分可恢复异常（DuplicateKeyException → debug）和真实错误（→ error + 完整栈）
- **教训**：异常处理不能一刀切，必须区分类型和严重程度

### 🟡 建议改进

#### 6. 实体贫血模型 + String 存枚举
- **教训**：MyBatis-Plus 支持 `@EnumValue` 直接映射枚举到数据库，实体应该用枚举类型而不是 String

#### 7. ServiceImpl 职责过重
- **教训**：当 Service 超过 300 行就要考虑拆分，消息树构建是独立的关注点，应该抽到专门的类

#### 8. ID 生成策略
- **教训**：`System.currentTimeMillis()` 在高并发下会冲突，必须用雪花算法或 UUIDv7

#### 9. 接口命名
- **教训**：`ConversationQueryService` 包含 save 操作但叫 Query，名不副实

#### 10-12. 安全和校验
- **教训**：DTO 层校验要 fail-fast（`@Pattern`），SQL 查询要加完整约束（conversation_id），删除要级联清理

## 模式总结

| 反模式 | 本次出现次数 | 预防措施 |
|--------|-------------|----------|
| 多表写入无事务 | 1 | 写入前问自己"这些操作需要在同一个事务吗？" |
| N+1 查询 | 1 | 树形/列表查询一律用全量查+内存组装 |
| 异常被泛化 catch 吞掉 | 2 | 区分异常类型，核心操作失败必须记录完整栈 |
| check-then-act 竞态 | 1 | 唯一约束兜底 + catch DuplicateKey 重查 |
| String 替代枚举 | 3 | 实体字段优先用枚举类型 |
| 接口命名不符 | 1 | Code review 时检查接口名是否准确描述行为 |
