# Redisson 集成方案

> 基于 Redisson 的分布式能力增强计划，覆盖限流、缓存、锁、去重等场景，分阶段替换现有手动 Redis 操作。

## 1. 概述

### 当前 Redis 使用现状

项目通过 Spring Data Redis（Lettuce 驱动）接入 Redis，核心操作依赖 `StringRedisTemplate` / `RedisTemplate` 手动执行命令，部分场景辅以手写 Lua 脚本保证原子性。整体可用但存在以下局限：

- **分布式能力缺失**：限流、锁等机制基于本地内存，多实例部署时无法共享状态
- **代码复杂度高**：Lua 脚本维护成本大，Pipeline 批量操作代码冗长
- **可靠性不足**：部分操作（如 `setIfAbsent` + `expire` 两步操作）理论上存在竞态窗口

### 引入 Redisson 的动机

Redisson 是一个驻内存数据网格（In-Memory Data Grid）客户端，提供丰富的分布式数据结构和同步/异步接口。引入 Redisson 的核心收益：

| 维度 | 收益 |
|------|------|
| **分布式能力** | 开箱即用的分布式限流器、锁、ID 生成器，多实例共享状态 |
| **简化代码** | 手写 Lua 脚本 → 一行 API 调用，Pipeline 批量操作 → 数据结构原生方法 |
| **增强可靠性** | 内置看门狗续约、原子操作、连接故障转移，减少手写逻辑的边界 Bug |
| **性能优化** | 本地缓存（RLocalCachedMap）减少 Redis 网络往返 |

## 2. 当前 Redis 使用方式及痛点

| 模块 | 当前实现 | 代码位置 | 痛点 |
|------|----------|----------|------|
| 令牌桶限流 | 内存 `ConcurrentHashMap`（`TokenBucketLimiter`） | `chat/advisor/` | 单实例限流，多实例不共享，重启丢失 |
| Token 缓存 | `StringRedisTemplate` + 手写 Lua 脚本 | `security/service/TokenCacheService` | 原子刷新/撤销逻辑复杂 |
| 聊天记忆 | ZSet + 手动 Pipeline | `chat/memory/RedisChatMemoryRepository` | 批量操作代码冗长 |
| 分片上传 | Hash + 外部 Lua 脚本 | `rag/upload/ChunkUploadServiceImpl` | 原子性靠外部脚本维护 |
| Prompt 缓存 | `setIfAbsent` + `expire` 两步操作 | `chat/service/impl/PromptLoaderServiceImpl` | 理论上可竞态 |

## 3. Redisson 带来的能力

以下列出 Redisson 核心数据结构和功能，结合项目具体场景说明适用性：

| Redisson 组件 | 项目场景 | 说明 |
|---------------|----------|------|
| **RRateLimiter** | 分布式令牌桶限流 | 替代内存 `TokenBucketLimiter`，支持多实例共享，用户级限流 |
| **RLock / RReadWriteLock** | ETL 文档处理互斥 | 分布式可重入锁，看门狗自动续约，防止同一文档多实例重复处理 |
| **RMapCache** | Token 缓存 | 带 TTL 的分布式 Map，替代 `TokenCacheService` 的手动 Lua 脚本 |
| **RScoredSortedSet** | 聊天记忆 | 替代手动 ZSet + Pipeline 操作，API 更简洁 |
| **RBloomFilter** | 文档上传去重 | 布隆过滤器快速判断内容是否已入库，避免重复 ETL |
| **RIdGenerator / RSnowflakeId** | 分布式 ID 生成 | 项目已有雪花算法，可作为备选方案 |
| **RLocalCachedMap** | Prompt 缓存 | 本地缓存 + Redis 二级缓存，热数据读取零网络延迟 |

## 4. 分阶段实施计划

### Phase 1：基础设施 + 分布式限流（优先级最高）

**目标**：引入 Redisson 依赖，实现分布式限流替换内存限流器。

- 添加 `redisson-spring-boot-starter` 依赖
- 配置 Redisson 连接（复用现有 Redis 连接参数）
- 实现 `RedissonRateLimiter` 替换内存 `TokenBucketLimiter`
- 限流维度从 `conversationId` 改为 `userId`（用户级限流）
- 未认证请求 fallback 到 IP 维度
- 保留内存限流器作为 Redis 不可用时的降级方案

```
请求 → RateLimitAdvisor
          │
          ├─ 已认证 → RRateLimiter(userId)  ── 分布式令牌桶
          │
          └─ 未认证 → RRateLimiter(IP)      ── IP 维度限流
          │
          └─ Redis 不可用 → 内存 TokenBucketLimiter（降级）
```

### Phase 2：Token 缓存 + Prompt 缓存优化

**目标**：简化缓存操作，消除手写 Lua 脚本和竞态风险。

| 模块 | 变更前 | 变更后 |
|------|--------|--------|
| `TokenCacheService` | Lua 脚本手动管理 TTL | `RMapCache` 自动 TTL |
| `PromptLoaderServiceImpl` | `setIfAbsent` + `expire` 两步操作 | `RBucket.trySet()` 原子操作 |
| 高频 Prompt 读取 | 每次访问 Redis | `RLocalCachedMap` 本地缓存 + Redis 二级缓存 |

### Phase 3：聊天记忆 + 分片上传简化

**目标**：利用 Redisson 数据结构简化 Redis 操作代码。

| 模块 | 变更前 | 变更后 |
|------|--------|--------|
| `RedisChatMemoryRepository` | 手动 ZSet + Pipeline 批量操作 | `RScoredSortedSet` 原生 API |
| `ChunkUploadServiceImpl` | 外部 Lua 脚本维护原子性 | `RMap` + `RLock` 组合 |

### Phase 4：ETL 分布式锁 + 文档去重

**目标**：增强 ETL 处理的并发安全和去重能力。

- ETL 文档处理：`RLock` 防止同一文档多实例重复处理
- 文档去重：`RBloomFilter` 快速判断内容是否已入库
- 向量化结果缓存：`RMapCache` 缓存 embedding 结果，避免重复计算

```
文档上传 → RBloomFilter.contains(contentHash)
              │
              ├─ 可能存在 → 跳过 ETL（去重）
              │
              └─ 不存在 → RLock.tryLock(docId) → ETL 处理 → RBloomFilter.add(contentHash)
                              │
                              └─ 获取锁失败 → 其他实例正在处理，等待/跳过
```

## 5. 技术决策

| 决策项 | 选项 | 建议 | 理由 |
|--------|------|------|------|
| Redisson vs Lettuce 共存 | 共存 / 替换 | **共存** | Spring Data Redis 生态（`RedisTemplate`）广泛使用，全量替换成本高 |
| Starter 版本 | `redisson-spring-boot-starter` | **3.45+** | 兼容 Spring Boot 3.x |
| 序列化方式 | `MarshallingCodec` / `JsonJacksonCodec` | **JsonJacksonCodec** | 可读性好，调试友好 |
| 限流降级策略 | 直接报错 / 本地限流 | **本地限流降级** | Redis 故障时仍可单实例限流，保障服务可用性 |

## 6. 风险与注意事项

| 风险项 | 说明 | 应对措施 |
|--------|------|----------|
| 连接池独立 | Redisson 与 Lettuce 连接池各自独立，需分别调优 | 生产环境根据实际连接数分别配置 `connectionPoolSize` |
| 看门狗超时 | `lockWatchdogTimeout` 默认 30s，ETL 长任务可能不够 | 根据业务调整，或使用 `leaseTime` 显式指定 |
| 限流性能 | `RRateLimiter` 基于 Redis Lua 脚本实现，高并发下有少量开销 | 远优于网络往返，可接受；超高并发场景可结合本地缓存 |
| Redis 版本要求 | Redisson 3.45 要求 Redis >= 6.0 | 部署前确认 Redis 版本，项目 Docker Compose 已满足 |
| 内存占用 | Redisson 客户端自身占用额外 JVM 内存 | 关注本地缓存（`RLocalCachedMap`）的 `cacheSize` 上限配置 |

## 7. 依赖引入

### Maven 依赖

```xml
<dependency>
    <groupId>org.redisson</groupId>
    <artifactId>redisson-spring-boot-starter</artifactId>
    <version>3.45.1</version>
</dependency>
```

### 配置方式一：独立配置文件

```yaml
spring:
  redis:
    redisson:
      file: classpath:redisson.yml
```

`redisson.yml` 内容：

```yaml
singleServerConfig:
  address: "redis://${REDIS_HOST:localhost}:${REDIS_PORT:6379}"
  password: "${REDIS_PASSWORD:}"
  connectionPoolSize: 16
  connectionMinimumIdleSize: 4
  timeout: 3000
```

### 配置方式二：内联配置

```yaml
spring:
  redis:
    redisson:
      config: |
        singleServerConfig:
          address: "redis://${REDIS_HOST:localhost}:${REDIS_PORT:6379}"
          password: "${REDIS_PASSWORD:}"
          connectionPoolSize: 16
          connectionMinimumIdleSize: 4
          timeout: 3000
```

两种方式均复用现有 Redis 连接参数（`REDIS_HOST`、`REDIS_PORT`、`REDIS_PASSWORD`），无需额外环境变量。
