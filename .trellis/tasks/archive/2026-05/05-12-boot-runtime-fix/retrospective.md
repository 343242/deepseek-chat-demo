# 复盘：修复运行时启动失败

## 问题

应用编译 + 测试全通过（214 tests），但 `spring-boot:run` 启动后实际聊天请求报错。

### 错误 1: SPRING_AI_CHAT_MEMORY 表不存在
```
bad SQL grammar [SELECT content, type FROM SPRING_AI_CHAT_MEMORY WHERE conversation_id = ? ...]
```
- **根因**：`application-dev.yml` 配置了 `spring.ai.chat.memory.repository.jdbc.initialize-schema: never`，Spring AI 不自动建表，但项目也没有 Flyway 迁移创建这张表
- **修复**：新增 `V7__spring_ai_chat_memory.sql` Flyway 迁移脚本

### 错误 2: TIMESTAMPTZ → LocalDateTime 转换失败
```
PSQLException: Cannot convert the column of type TIMESTAMPTZ to requested type java.time.LocalDateTime
```
- **根因**：V5 迁移创建的 `conversation` 和 `message` 表使用 `timestamp with time zone`（PG 的 TIMESTAMPTZ），但 Java 实体 `Conversation`/`Message` 用了 `LocalDateTime`（无时区信息）
- **修复**：Conversation/Message 实体 + DTO + Mapper 全部 `LocalDateTime` → `OffsetDateTime`

## 教训

1. **编译通过 ≠ 运行通过**：单元测试用 mock 不连真实 DB，类型不匹配只在运行时暴露
2. **新增功能必须端到端验证**：conversation 模块重构后只跑了 `mvn test`，没有实际 `spring-boot:run` + 发请求验证
3. **新增表/配置后检查依赖链**：引入 Spring AI JDBC Chat Memory 时设了 `initialize-schema: never`，但没有补充建表脚本
4. **实体类型必须匹配 DDL**：DDL 是 `timestamptz`，Java 就该用 `OffsetDateTime`；约定：**PG 用 timestamptz，Java 用 OffsetDateTime，不用 LocalDateTime**

## 待解决

启动后发现 MULTI_TURN 模式报 `conversationId cannot be null`（MessageChatMemoryAdvisor 内部），是独立问题，需要后续排查。
