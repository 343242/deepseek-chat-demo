# Replace JDBC ChatMemory with Redis (Lettuce)

## 目标
用 Lettuce + Spring Data Redis 重写 ChatMemoryRepository，替换现有 JDBC 实现。用户会话数据存 Redis，支持用户隔离。

## 背景
- 当前使用 `JdbcChatMemoryRepository` + PostgreSQL 存储会话消息
- 已将 Spring AI 的 Redis ChatMemory 源码复制到 `chat/memory/` 下，但它依赖 Jedis + RediSearch + RedisJSON + Gson + jspecify，过于重量级
- 项目已有 `spring-boot-starter-data-redis`（Lettuce），无需额外依赖

## 实现清单

### 1. 删除原始 Jedis 实现
- 删除 `chat/memory/AdvancedRedisChatMemoryRepository.java`（高级搜索接口，不需要）
- 删除 `chat/memory/RedisChatMemoryConfig.java`（Jedis 配置类）
- 重写 `chat/memory/RedisChatMemoryRepository.java`

### 2. 重写 RedisChatMemoryRepository
- 实现 `ChatMemoryRepository` 接口（4 个方法）
- 使用 `RedisTemplate<String, String>` + Lettuce
- 序列化用 Jackson（Spring Boot 自带），不用 Gson
- Key 设计：`chat:memory:{conversationId}` → Hash 或 JSON String 存消息列表
- Conversation ID 列表用 Redis Set 维护：`chat:memory:conversations`

### 3. 新增自动配置类
- 创建 `chat/memory/RedisChatMemoryAutoConfiguration.java`
- 注册 `ChatMemoryRepository` Bean（Redis 实现）
- 读取配置项 `app.chat.memory.redis.key-prefix` 等

### 4. 修改 AdvisorAutoConfiguration
- 移除 `JdbcChatMemoryRepository` Bean
- 移除 JDBC 相关 import
- `ChatMemory` Bean 改用 Redis `ChatMemoryRepository`
- 移除 `spring-ai-starter-model-chat-memory-repository-jdbc` 依赖（可选，后续清理）

### 5. 配置
- `application.yml` 新增 Redis memory 相关配置项

## 设计决策
- **不使用 RedisJSON / RediSearch**：ChatMemory 只需 KV 读写，不需要搜索
- **不引入 Jedis**：项目已有 Lettuce，不引入第二个 Redis 客户端
- **不使用 Gson**：用 Jackson 保持一致性
- **用户隔离**：通过 conversationId 本身隔离（conversationId 已包含用户信息或由上层保证归属）

## Key 设计
```
chat:memory:{conversationId}  →  String (JSON array of messages)
chat:memory:sessions          →  Set    (all conversation IDs, for findConversationIds)
```

## 涉及文件
- `chat/memory/RedisChatMemoryRepository.java` — 重写
- `chat/memory/RedisChatMemoryConfig.java` — 删除
- `chat/memory/AdvancedRedisChatMemoryRepository.java` — 删除
- `chat/memory/RedisChatMemoryAutoConfiguration.java` — 新增
- `config/AdvisorAutoConfiguration.java` — 修改
- `pom.xml` — 可选：移除 JDBC memory 依赖

## 验收
- [ ] 编译通过（`mvn compile`）
- [ ] 三个原始 Jedis 文件已清理
- [ ] ChatMemoryRepository Bean 使用 Redis 实现
- [ ] AdvisorAutoConfiguration 不再引用 JDBC ChatMemory
- [ ] Git commit + push
