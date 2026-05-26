# Redisson 集成方案

> 基于 Redisson 的分布式能力增强计划，优先解决多实例限流、ETL 互斥和跨实例缓存一致性问题。Redisson 不应被视为对现有 Redis/Lua/Pipeline 的全面性能替换，所有替换必须先确认语义等价和压测收益。

## 1. 概述

### 当前 Redis 使用现状

项目通过 Spring Data Redis（Lettuce 驱动）接入 Redis，核心操作依赖 `StringRedisTemplate` / `RedisTemplate` 手动执行命令，部分场景辅以手写 Lua 脚本保证原子性。整体可用，但不同场景的问题性质不同：

- **必须解决**：聊天限流仍基于本地内存，多个应用实例之间不共享状态。
- **可以增强**：ETL/文档处理缺少跨实例互斥能力，同一文档在多实例场景下可能重复处理。
- **谨慎替换**：Lua 脚本和 Pipeline 虽然维护成本较高，但当前承载了原子性和批量写入语义，不能只因为 Redisson API 更简洁就直接替换。
- **已部分解决**：Prompt 加载已使用带 TTL 的 `setIfAbsent(key, value, ttl)` 原子写入，热路径已有 Caffeine 本地缓存，并不存在简单的 `setIfAbsent` + `expire` 两步竞态。

### 引入 Redisson 的动机

Redisson 是一个驻内存数据网格（In-Memory Data Grid）客户端，提供丰富的分布式数据结构和同步/异步接口。引入 Redisson 的核心收益：

| 维度 | 收益 |
|------|------|
| **分布式能力** | 开箱即用的分布式限流器、锁、ID 生成器，多实例共享状态 |
| **简化代码** | 用更明确的数据结构表达部分缓存/锁/限流意图 |
| **增强可靠性** | 内置看门狗续约、原子操作、连接故障转移，减少部分手写边界 Bug |
| **性能优化** | 仅在本地缓存命中、减少网络往返或减少重复外部 API 调用时成立，必须压测验证 |

## 2. 当前 Redis 使用方式及痛点

| 模块 | 当前实现 | 代码位置 | 痛点 |
|------|----------|----------|------|
| 聊天限流 | 内存 `ConcurrentHashMap`（`TokenBucketLimiter`） | `chat/advisor/` | 单实例限流，多实例不共享，重启丢失 |
| 上传初始化限流 | `INCR` + 首次 `EXPIRE` | `rag/upload/ChunkUploadServiceImpl` | Redis 已共享，但 TTL 设置仍是两步；进程崩溃时可能留下无 TTL key |
| Token 缓存 | `StringRedisTemplate` + 手写 Lua 脚本 | `security/service/TokenCacheService` | Refresh Token 旋转原子逻辑复杂；可读性和可维护性一般 |
| 聊天记忆 | ZSet + 手动 Pipeline | `chat/memory/RedisChatMemoryRepository` | 批量操作代码冗长；并发覆盖问题不能靠简单数据结构替换自动解决 |
| 分片上传 | Hash + 外部 Lua 脚本 | `rag/upload/ChunkUploadServiceImpl` | Lua 维护成本高，但当前承担幂等、计数、合并锁的原子状态机 |
| Prompt 缓存 | 启动加载 + Redis + Caffeine | `chat/service/impl/PromptLoaderServiceImpl` / `SystemPromptServiceImpl` | 不存在两步 TTL 竞态；只有跨实例动态更新/失效需求时才需要 Redisson 本地缓存 |

## 3. Redisson 带来的能力

以下列出 Redisson 核心数据结构和功能，结合项目具体场景说明适用性：

| Redisson 组件 | 项目场景 | 说明 |
|---------------|----------|------|
| **RRateLimiter** | 分布式令牌桶限流 | 替代内存 `TokenBucketLimiter`，支持多实例共享，用户级限流 |
| **RLock / RReadWriteLock** | ETL 文档处理互斥 | 分布式可重入锁，看门狗自动续约，可防止同一文档多实例重复处理 |
| **RMapCache** | 权限/状态/短期缓存 | 带 TTL 的分布式 Map，适合简单 key/value 或 map field TTL；Refresh Token 旋转仍需验证原子语义 |
| **RScoredSortedSet** | 聊天记忆 | 可提升 API 可读性，但不保证比 Pipeline 更快，也不自动解决并发覆盖 |
| **RBloomFilter** | 文档上传预筛 | 只能用于预筛；命中后必须再查 DB/权威索引确认，禁止因 BloomFilter 命中直接跳过 ETL |
| **RIdGenerator / RSnowflakeId** | 分布式 ID 生成 | 项目已有雪花算法，可作为备选方案 |
| **RLocalCachedMap** | 跨实例可失效的热点缓存 | 仅当需要 Redis 驱动的跨实例失效时使用；当前 Prompt 热路径已有 Caffeine |

## 4. 分阶段实施计划

### Phase 1：基础设施 + 分布式限流（优先级最高）

**目标**：引入 Redisson 依赖，实现分布式聊天限流，解决多实例部署下限流状态不共享的问题。

- 添加 `redisson-spring-boot-starter` 依赖
- 配置 Redisson 连接（复用现有 Redis 连接参数）
- 实现 `RedissonRateLimiter`，通过 `RateLimiter` 接口替换默认聊天限流实现
- 限流维度从 `conversationId` 调整为 `userId` 时必须确认产品语义：同一用户多个会话将共享配额
- 未认证请求 fallback 到 IP 维度；需要明确 IP 来源，避免直接信任可伪造 Header
- 保留内存限流器作为 Redis 不可用时的降级方案
- 降级后每个应用实例都有独立内存桶，总配额会随实例数放大，必须在监控和告警中暴露

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

**目标**：优先处理真实存在的原子性和跨实例一致性问题；避免把已经有原子 TTL 和 Caffeine 的 Prompt 热路径重复改造。

| 模块 | 变更前 | 变更后 |
|------|--------|--------|
| `TokenCacheService.rotateRefreshToken()` | Lua 脚本保证 GET + DEL + SREM 原子性 | 只有在 Redisson 实现能保持同等原子语义时才替换；否则保留 Lua |
| 上传初始化限流 | `INCR` + 首次 `EXPIRE` | 可用 `RRateLimiter` 或 Lua 修复 TTL 两步窗口 |
| `PromptLoaderServiceImpl` | 已使用带 TTL 的 `setIfAbsent` | 不需要为“原子写入”改造；只在需要跨实例失效时评估 `RLocalCachedMap` |
| 高频 Prompt 读取 | Caffeine 本地缓存 + Redis/DB fallback | 保持现状，除非动态配置刷新需要 Redis 广播失效 |

### Phase 3：聊天记忆 + 分片上传简化

**目标**：仅在并发语义和性能验证通过后，再考虑可读性重构。该阶段不作为性能优化优先项。

| 模块 | 变更前 | 变更后 |
|------|--------|--------|
| `RedisChatMemoryRepository` | 手动 ZSet + Pipeline 批量操作 | 可评估 `RScoredSortedSet`，但必须保持批量写入效率和 TTL 行为 |
| `ChunkUploadServiceImpl` | 外部 Lua 脚本维护原子状态机 | 默认保留 Lua；若替换为 `RMap` + `RLock`，必须先补齐并发测试和失败恢复设计 |

分片上传 Lua 当前一次完成以下语义：分片幂等检查、合并中检查、记录 ETag、统计已上传分片、原子设置合并标记。`RMap` + `RLock` 不是等价替换，可能引入锁租期、看门狗、MinIO 已上传但 Redis 状态失败等新问题。

### Phase 4：ETL 分布式锁 + 文档去重

**目标**：增强 ETL 处理的并发安全和去重能力。

- ETL 文档处理：`RLock` 防止同一文档多实例重复处理
- 文档去重预筛：`RBloomFilter` 快速判断内容可能已入库，但命中后必须再查 DB/权威索引确认
- 向量化结果缓存：`RMapCache` 缓存 embedding 结果时必须用模型、参数、文本规范化版本组成 cache key，避免错误复用

```
文档上传 → RBloomFilter.contains(contentHash)
              │
              ├─ 可能存在 → 查询 DB/权威索引确认
              │              ├─ 确认存在 → 复用已有文档/向量结果
              │              └─ 不存在 → 按新文档继续处理
              │
              └─ 不存在 → RLock.tryLock(docId) → ETL 处理 → RBloomFilter.add(contentHash)
                              │
                              └─ 获取锁失败 → 其他实例正在处理，等待/跳过
```

## 5. 技术决策

| 决策项 | 选项 | 建议 | 理由 |
|--------|------|------|------|
| Redisson vs Lettuce 共存 | 共存 / 替换 | **共存** | Spring Data Redis 生态（`RedisTemplate`）广泛使用，全量替换成本高 |
| Starter 版本 | `redisson-spring-boot-starter` | **按 Spring Boot 3.5 / Spring Data Redis 3.5 兼容性验证后选择** | 项目使用 Spring Boot 3.5，需要通过 `dependency:tree` 和启动测试确认 starter 传递依赖不冲突 |
| 序列化方式 | `MarshallingCodec` / `JsonJacksonCodec` | **JsonJacksonCodec** | 可读性好，调试友好 |
| 限流降级策略 | 直接报错 / 本地限流 | **本地限流降级** | Redis 故障时仍可单实例限流，保障服务可用性 |
| 分片上传 Lua | 保留 / Redisson 锁重写 | **默认保留** | 当前 Lua 是上传状态机的原子边界，替换风险高 |
| BloomFilter 去重 | 直接跳过 / 预筛后确认 | **预筛后确认** | BloomFilter 存在假阳性，不能作为跳过 ETL 的唯一依据 |

## 6. 风险与注意事项

| 风险项 | 说明 | 应对措施 |
|--------|------|----------|
| 连接池独立 | Redisson 与 Lettuce 连接池各自独立，需分别调优 | 生产环境根据实际连接数分别配置 `connectionPoolSize` |
| 看门狗超时 | `lockWatchdogTimeout` 默认 30s，ETL 长任务可能不够 | 根据业务调整，或使用 `leaseTime` 显式指定 |
| 限流性能 | `RRateLimiter` 基于 Redis 协调，多实例正确性优先于单机极限吞吐 | 以压测结果决定参数；Redis 故障降级后要监控配额放大 |
| 聊天记忆性能 | Redisson 集合 API 不一定比 Pipeline 更快 | 替换前必须比较命令数、RTT 和吞吐 |
| 分片上传一致性 | `RMap` + `RLock` 可能破坏现有 Lua 原子状态机 | 保留 Lua，或先补并发/失败恢复测试 |
| BloomFilter 假阳性 | 命中不代表文档真实存在 | 命中后必须查询 DB/权威索引确认 |
| Redis 版本要求 | Redisson 版本可能对 Redis/Spring Data Redis 有兼容要求 | 部署前确认 Redis 版本；项目 Docker Compose 当前使用 Redis 8.2.6 |
| 内存占用 | Redisson 客户端自身占用额外 JVM 内存 | 关注本地缓存（`RLocalCachedMap`）的 `cacheSize` 上限配置 |
| 本地缓存一致性 | `RLocalCachedMap` 会引入失效策略和订阅开销 | 仅在跨实例动态刷新需求明确时使用 |

## 7. 依赖引入

### Maven 依赖

```xml
<dependency>
    <groupId>org.redisson</groupId>
    <artifactId>redisson-spring-boot-starter</artifactId>
    <!-- 版本需与 Spring Boot 3.5 / Spring Data Redis 3.5 兼容性验证后锁定 -->
    <version>${redisson.version}</version>
</dependency>
```

建议在 `pom.xml` properties 中集中管理版本，并在引入后执行：

```bash
mvn dependency:tree -Dincludes=org.redisson,org.springframework.data
mvn test
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

## 8. 验证要求

Redisson 改造必须以“正确性优先，性能用数据证明”为准：

| 场景 | 必测内容 |
|------|----------|
| 分布式聊天限流 | 多实例共享同一 `userId` 配额；Redis 不可用时降级到本地限流；降级后配额放大可观测 |
| 上传初始化限流 | TTL 不丢失；窗口到期后可恢复；并发初始化不会产生永久限流 key |
| Token 旋转 | 并发 refresh 只能成功一次；旧 refresh token 被删除；用户反向索引同步清理 |
| 聊天记忆 | 保存、读取、TTL、并发 `saveAll()` 行为与现状一致；替换后吞吐不低于 Pipeline 方案 |
| 分片上传 | 重复分片幂等、最后一个分片只触发一次合并、合并失败可恢复、MinIO 与 Redis 状态一致 |
| 文档去重 | BloomFilter 假阳性不会导致 ETL 被跳过；DB/权威索引确认逻辑覆盖个人和团队空间隔离 |

## 9. 推荐落地顺序

1. Phase 1 先做：Redisson 基础设施 + 分布式聊天限流。
2. 修复上传初始化限流的 TTL 两步窗口，可选 Redisson `RRateLimiter` 或保留 Spring Data Redis + Lua。
3. 评估 ETL 分布式锁，只锁同一 `documentId` 或同一工作空间内的同一内容 hash。
4. 暂缓分片上传 Lua 替换，除非先补齐并发测试和失败恢复设计。
5. 暂缓聊天记忆 Pipeline 替换，除非 benchmark 证明 Redisson 实现不退化。
6. Prompt 缓存保持现状；只有动态 Prompt 跨实例刷新成为明确需求时，再评估 `RLocalCachedMap`。
