# Task: 修复应用启动后聊天失败的两个问题

## 问题清单

### 问题 1: SPRING_AI_CHAT_MEMORY 表不存在
- Spring AI JDBC Chat Memory 配置了 `initialize-schema: never`
- 表从未创建，MULTI_TURN 模式查询时 `SELECT ... FROM SPRING_AI_CHAT_MEMORY` 报错
- 修复：改为 `always` 或创建 Flyway 迁移脚本

### 问题 2: TIMESTAMPTZ → LocalDateTime 类型不匹配
- DB 列类型是 `timestamp with time zone`（PostgreSQL TIMESTAMPTZ）
- Java 实体 Conversation/Message 用了 `LocalDateTime`（无时区）
- MyBatis-Plus 无法自动转换，报 `PSQLException: Cannot convert TIMESTAMPTZ to LocalDateTime`
- 修复：实体字段改为 `OffsetDateTime`

## 修复计划

- [1] Conversation.java: LocalDateTime → OffsetDateTime
- [2] Message.java: LocalDateTime → OffsetDateTime
- [3] 相关 DTO/Service 中的 LocalDateTime 一并修正
- [4] application-dev.yml: initialize-schema 改为 always（或创建 V7 迁移）
- [5] 编译 + 测试通过
- [6] 启动验证
- [7] Git commit + push
- [8] 复盘
