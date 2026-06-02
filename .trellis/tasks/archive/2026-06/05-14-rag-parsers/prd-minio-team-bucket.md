# 方案 A：每团队一个 MinIO Bucket — 影响评估

## 1. 方案概述

**当前**：所有上传（个人 + 团队）共用一个 bucket `rag-documents`。
**目标**：个人文档继续用默认 bucket；团队文档用 `rag-team-{teamId}`，每团队独立 bucket。

## 2. Bucket 命名规则

| 场景 | Bucket 名称 | 说明 |
|------|-------------|------|
| 个人文档 | `rag-documents`（配置默认值） | 不变 |
| 团队文档 | `rag-team-{teamId}` | 动态生成，如 `rag-team-1001` |

MinIO bucket 命名限制：3-63 字符，小写字母/数字/连字符。`rag-team-{teamId}` 符合约束。

## 3. 架构设计：BucketResolver

引入 `BucketResolver` 单一类，将 bucket 选择逻辑从各上传策略中抽离封装。
内部根据 teamId 是否为 null 自动路由。

```java
@Component
public class BucketResolver {

    private final MinioProperties minioProperties;

    public BucketResolver(MinioProperties minioProperties) {
        this.minioProperties = minioProperties;
    }

    /**
     * 根据团队 ID 解析目标 bucket。
     * teamId 为 null 时返回个人文档默认 bucket，否则返回团队专属 bucket。
     */
    public String resolve(@Nullable Long teamId) {
        return teamId == null
                ? minioProperties.getBucket()
                : "rag-team-" + teamId;
    }

    /** 获取个人文档默认 bucket 名 */
    public String defaultBucket() {
        return minioProperties.getBucket();
    }

    /** 判断是否为团队 bucket */
    public boolean isTeamBucket(String bucket) {
        return bucket != null && bucket.startsWith("rag-team-");
    }
}
```

### 设计决策：单一类而非接口+多实现

**原因**：
1. **避免 Spring Bean 歧义**：接口 + PersonalBucketResolver + TeamBucketResolver 三个 Bean，
   注入时 Spring 无法自动判断用哪个（`NoUniqueBeanDefinitionException`）
2. **调用方需要统一入口**：`ChunkUploadServiceImpl` 等需要根据 `teamId` 在同一方法内动态路由，
   不是"注入某一个实现"的场景
3. **命名规则简单**：一个 `if-else` 分支，不值得策略模式的复杂度

### OCP 保证

新增场景（如"企业空间"）时，将 `resolve` 内部改为查表或策略 Map 即可，
调用方（上传策略、ChunkUploadServiceImpl）零修改。

### 新增文件

| 文件 | 说明 |
|------|------|
| `rag/upload/BucketResolver.java` | bucket 解析器（单一类，放在 upload 包下，靠近使用方） |

### 不新增的文件（审查修正）

~~`rag/config/BucketResolver.java`~~ — spec 规定 config 包放"基础配置（无业务逻辑）"，
BucketResolver 是路由策略，应放 `rag/upload/`

~~`rag/config/PersonalBucketResolver.java`~~ / ~~`rag/config/TeamBucketResolver.java`~~ —
合并为单一类，消除 Bean 歧义

## 4. 受影响的代码文件

### 4.1 直接使用 `minioProperties.getBucket()` 的类（共 4 个）

| 文件 | 当前行为 | 改动方案 |
|------|---------|---------|
| `rag/upload/PersonalUploadStrategy` | `getBucket()` 取默认 bucket | 改为注入 `BucketResolver`，调用 `resolve(null)` |
| `rag/upload/ChunkUploadServiceImpl` | `getBucket()` 取默认 bucket | 改为注入 `BucketResolver`，init 时根据 teamId 调用 `resolve(teamId)` |
| `rag/upload/OrphanChunkCleaner` | 构造时固定 `getBucket()` | 改为懒加载扫描所有 bucket（详见风险点 2） |
| `team/upload/TeamUploadStrategy` | `getBucket()` 取默认 bucket | 改为注入 `BucketResolver`，调用 `resolve(teamId)` |

### 4.2 间接使用（bucket 从数据库 `rag_document` 表读取）

这些**不需要改动**——`rag_document.bucket` 字段已经存储了实际 bucket 名，
后续 ETL、下载、删除都从数据库读取：

| 文件 | 调用方式 | 影响 |
|------|---------|------|
| `rag/etl/EtlCandidate` (record) | `bucket` 作为参数传递 | ✅ 无需改动 |
| `rag/etl/DocumentExtractor` | `fileStorageService.download(bucket, objectKey)` | ✅ 无需改动 |
| `rag/etl/FastTrackStrategy` | `extractor.extract(c.bucket(), ...)` | ✅ 无需改动 |
| `rag/etl/StandardStrategy` | `extractor.extract(c.bucket(), ...)` | ✅ 无需改动 |
| `rag/service/impl/EtlPipelineServiceImpl` | `extractor.extract(bucket, objectKey, ...)` | ✅ 无需改动 |
| `rag/service/impl/EtlDispatchServiceImpl` | `dispatchAsync(id, bucket, ...)` | ✅ 无需改动 |
| `rag/service/impl/DocumentApplicationServiceImpl` | `doc.getBucket()` | ✅ 无需改动 |
| `rag/service/impl/DocumentLifecycleService` | `fileStorageService.delete(doc.getBucket(), ...)` | ✅ 无需改动 |
| `team/service/impl/TeamApprovalServiceImpl` | `doc.getBucket()` | ✅ 无需改动 |

### 4.3 团队生命周期相关

| 文件 | 改动方案 |
|------|---------|
| `team/service/impl/TeamServiceImpl.createTeam()` | **新增**：事务外调用 `fileStorageService.ensureBucketExists("rag-team-" + teamId)` |
| `team/service/impl/TeamServiceImpl.dissolveTeam()` | **新增**：软删 bucket 标记（详见风险点 3） |

## 5. 数据库影响

`rag_document` 表已有 `bucket` 字段（VARCHAR），**无需修改表结构**。

已有的文档记录 `bucket = "rag-documents"` 不受影响（个人文档继续用默认 bucket）。
新的团队文档 `bucket = "rag-team-{teamId}"`。

## 6. 风险点及解决方案

### 风险 1：ChunkUploadController 没有 teamId 路由（审查发现 CR-2）

**问题**：当前 `ChunkUploadController` 只有 `/api/documents/multipart` 一条路径，
没有 teamId 参数，也没有 `/api/teams/{teamId}/documents/multipart` 路由。
方案原先假设"Controller 层已通过路由确定了上传策略"**不符合现状**。

而普通上传的 `DocumentController` 是通过 `UploadStrategyFactory.route(teamId)` 区分个人/团队的。

**解决方案**：分阶段实现。

**Phase 1（本次）**：分片上传仅支持个人文档，团队上传走 `TeamUploadStrategy`（非分片）。
`ChunkUploadServiceImpl` 不改动，继续用默认 bucket。
理由：团队文档通常较小（知识库文档），非分片上传够用。

**Phase 2（后续）**：新增 `/api/teams/{teamId}/documents/multipart` 路由，
`ChunkUploadInitRequest` 加 `teamId` 字段，
init 时通过 `BucketResolver.resolve(teamId)` 算出 bucket 存入 Redis session。

**Phase 1 改动文件**：0 个（ChunkUpload 相关不改动）

### 风险 2：OrphanChunkCleaner 只扫描默认 bucket

**问题**：当前 `OrphanChunkCleaner` 构造时固定了一个 bucket，
改为多 bucket 后无法扫描团队的孤儿分片。

**解决方案**：改为懒加载扫描，每次定时任务执行时动态获取 bucket 列表。

```java
// 方案：扫描 MinIO 所有 rag-team-* bucket + 默认 bucket
List<String> allBuckets = minioClient.listBuckets().stream()
        .map(Bucket::name)
        .filter(name -> name.equals(defaultBucket) || name.startsWith("rag-team-"))
        .toList();

for (String bucket : allBuckets) {
    cleanOrphansInBucket(bucket);
}
```

**优势**：
- 无需维护 bucket 注册表，MinIO 自身就是 source of truth
- 新团队自动纳入扫描范围
- 已解散但未删除的 team bucket 也会被清理

**性能评估**：团队数 < 1000 时，`listBuckets()` 调用一次 + 逐 bucket 扫描，
每 6 小时一次，影响可忽略。未来团队数过万时需加缓存或分批扫描。

**改动文件**：`OrphanChunkCleaner`（构造函数不再固定 bucket，改为注入 MinioClient + BucketResolver）

### 风险 3：团队解散时的 Bucket 清理 + 事务边界

**问题**：
- `createTeam()` 内有 `TransactionTemplate` 包裹的 DB 事务。
- MinIO 的 `ensureBucketExists` 是网络调用，不能放在事务内（无法回滚）。
- `dissolveTeam()` 同理，删除 bucket 也应该在事务外。

**解决方案**：

#### createTeam() — 先 bucket 后事务

```java
public TeamVO createTeam(TeamCreateRequest request) {
    // 1. 事务外：预生成 teamId（雪花 ID 手动填入）
    Long teamId = snowflakeIdGenerator.nextId();
    String bucket = "rag-team-" + teamId;
    fileStorageService.ensureBucketExists(bucket);

    // 2. 事务内：写 DB
    try {
        return txTemplate.execute(status -> {
            team.setId(teamId);
            teamMapper.insert(team);
            teamMemberMapper.insert(creatorMember);
            return toTeamVO(team, 1, TeamMemberRole.CREATOR.name());
        });
    } catch (DuplicateKeyException e) {
        // DB 失败，bucket 已创建但不影响（空 bucket 无副作用）
        throw new BusinessException(ErrorCode.TEAM_NAME_DUPLICATE);
    }
}
```

**注意**：当前 `TeamServiceImpl` 的 teamId 是 MyBatis-Plus 自增生成的，
改为手动填入需要确认 `@TableId(type = IdType.INPUT)` 或 `ASSIGN_ID`。
如果不想改 ID 策略，可以先开事务拿自增 ID，回滚后 bucket 空创也无所谓（幂等）。
**推荐前者**（手动 ID），更干净。

#### 孤儿 Bucket 补偿（审查发现 MD-1）

**问题**：如果 `ensureBucketExists` 成功但 DB insert 失败（如 teamName 重复），
会产生空孤儿 bucket。

**补偿方案**：`OrphanChunkCleaner` 在扫描时顺便清理空 bucket：

```java
// 在 cleanOrphanChunks() 末尾增加
for (String bucket : allBuckets) {
    if (bucket.startsWith("rag-team-")) {
        Iterable<Result<Item>> objects = minioClient.listObjects(
                ListObjectsArgs.builder().bucket(bucket).maxKeys(1).build());
        if (!objects.iterator().hasNext()) {
            // 空桶，检查对应的团队是否存在
            String teamIdStr = bucket.substring("rag-team-".length());
            try {
                Long teamId = Long.parseLong(teamIdStr);
                Team team = teamMapper.selectById(teamId);
                if (team == null || team.getDeleted() != 0) {
                    minioClient.removeBucket(RemoveBucketArgs.builder().bucket(bucket).build());
                    log.info("Cleaned orphan bucket: {}", bucket);
                }
            } catch (NumberFormatException ignored) {}
        }
    }
}
```

这样即使产生孤儿 bucket，也会在 6 小时内被自动清理。

#### dissolveTeam() — 事务内软删 + 延迟清理

```java
public void dissolveTeam(Long teamId) {
    // 1. 事务内：软删除团队 + 成员
    txTemplate.executeWithoutResult(status -> {
        team.setDeleted(1);
        teamMapper.updateById(team);
        teamMemberMapper.batchDeactivate(teamId);
    });

    // 2. 事务外：异步标记 bucket 待清理（不阻塞主流程）
    // 30 天后由 OrphanChunkCleaner 自动清理（团队已软删 → 空桶 → 删除）
}
```

**30 天延迟清理的落地机制**（审查发现 MN-2）：
不需要独立表记录"待清理 bucket"。
`OrphanChunkCleaner` 的扫描逻辑已经能覆盖：
1. 团队解散 → `deleted=1`
2. 后续 `deleteByConversationId` / `DocumentLifecycleService.delete()` 逐步清空 bucket 内对象
3. `OrphanChunkCleaner` 扫描时发现空 bucket + 对应团队 `deleted=1` → 删除 bucket

这个链路自然闭环，无需额外数据结构。

### 风险 4：团队解散后，未完成的分片上传 session（审查发现 MD-3）

**问题**：团队软删除后 bucket 仍存在。
如果此时有未完成的分片上传 session 在 Redis 中（TTL 24h），
merge 时会写入已解散团队的 bucket。

**解决方案**：在 `ChunkUploadServiceImpl.merge()` 中，从 session 读到 bucket 后，
如果是团队 bucket，校验团队是否仍有效：

```java
String bucket = session.get("bucket");
if (bucketResolver.isTeamBucket(bucket)) {
    Long teamId = extractTeamIdFromBucket(bucket);
    Team team = teamMapper.selectById(teamId);
    if (team == null || team.getDeleted() != 0) {
        cleanupSession(uploadId, bucket);
        throw new BusinessException(ErrorCode.TEAM_NOT_FOUND, "团队已解散，上传已取消");
    }
}
```

**影响范围**：仅 Phase 2（团队分片上传）时需要。Phase 1 不涉及。

### 风险 5：大量团队 = 大量 bucket

**评估**：MinIO 单节点支持数万 bucket，当前阶段无性能问题。
如果未来团队数超过 1 万，可考虑按 `rag-team-{teamId % 100}` 分桶（100 个 bucket 池），
但这属于远期优化，当前无需考虑。

### 风险 6：历史数据兼容

**评估**：旧数据 `rag_document.bucket = "rag-documents"` 完全兼容。
所有读取 bucket 的代码都从 DB 字段取值，不会受影响。
新增的团队文档自然写入 `rag-team-{teamId}`。

## 7. MinIO 端影响

| 操作 | 说明 |
|------|------|
| 创建 bucket | `createTeam()` 时触发，`ensureBucketExists` 幂等 |
| 删除 bucket | `dissolveTeam()` 后，bucket 内对象被清理后由 `OrphanChunkCleaner` 自动删除空桶 |
| 孤儿清理 | `OrphanChunkCleaner` 扫描所有 `rag-team-*` + 默认 bucket，同时清理孤儿空桶 |
| 存储空间 | 不变，总量一样，只是分布到不同 bucket |

## 8. 改动量估算

| 改动类型 | 文件数 | 复杂度 |
|---------|-------|--------|
| 新增 BucketResolver | 1 个 | 低 |
| 上传策略改用 BucketResolver | 2 个（PersonalUploadStrategy, TeamUploadStrategy） | 低 |
| 团队生命周期新增 | 1 个（TeamServiceImpl） | 中 |
| 孤儿清理器改造 | 1 个（OrphanChunkCleaner） | 中 |
| 配置变更 | 0 个（无需新配置项） | 无 |
| 数据库迁移 | 0 个（`rag_document.bucket` 已存在） | 无 |
| 测试影响 | 相关单元测试需适配 | 中 |

**Phase 1 总计约 5 个文件（1 新增 + 4 改动），无数据库迁移，无新增依赖。**

Phase 2（团队分片上传）额外改动：
- 新增 `ChunkUploadController` 团队路由（或新增 Controller）
- 修改 `ChunkUploadInitRequest`（加 teamId）
- 修改 `ChunkUploadServiceImpl`（init 用 BucketResolver、merge 加团队状态校验）

## 9. 不受影响的模块

- ✅ ChatMemory（Redis，本次已改完）
- ✅ 文档解析器（Parser 层，不感知 bucket）
- ✅ ETL Pipeline（bucket 从参数/DB 传入，不硬编码）
- ✅ 向量库（PgVector，隔离靠 metadata.userId/teamId，不靠 bucket）
- ✅ 认证/鉴权（Security 模块完全不涉及）
- ✅ Chat 模块（不涉及文件存储）

## 10. Spec 合规总结

| 规范 | 合规 | 说明 |
|------|------|------|
| OCP（开闭原则） | ✅ | `BucketResolver.resolve` 内部可扩展，调用方零修改 |
| 单一职责 | ✅ | bucket 选择逻辑独立封装在 BucketResolver |
| 依赖倒置 | ✅ | 上传策略依赖 `BucketResolver`（`@Component`），不直接依赖 MinioProperties |
| 封装彻底 | ✅ | bucket 命名规则封装在 BucketResolver 内，不泄漏到上层 |
| 设计模式 | ✅ | 单一类 + 条件路由，避免过度设计（审查修正：从接口+多实现简化为单一类） |
| 编程式事务 | ✅ | MinIO 操作在事务外，DB 操作在 TransactionTemplate 内 |
| 目录结构 | ✅ | BucketResolver 放 `rag/upload/`（审查修正：不放 config/） |
| 错误处理 | ✅ | MinIO 失败走现有 GlobalExceptionHandler，团队解散 merge 校验用 BusinessException |
| 日志规范 | ✅ | 新增日志使用 SLF4J，级别合理 |
| 新增功能 Checklist | ✅ | 新增场景只需扩展 BucketResolver.resolve()，上传策略零改动 |

## 11. 审查记录

本方案经两轮审查：

**第一轮（主会话自审）**：
- 引入 BucketResolver 消除硬编码
- 识别 5 个风险点并给出解决方案
- Spec 基础合规检查

**第二轮（DeepSeek V4 Pro 子代理审查）**：
- CR-1：BucketResolver 接口+多实现 → Spring Bean 歧义 → **修正为单一类**
- CR-2：ChunkUploadController 无 teamId 路由 → **修正为分阶段实现，Phase 1 不改 ChunkUpload**
- MD-1：孤儿 Bucket 无补偿 → **OrphanChunkCleaner 增加空桶清理**
- MD-3：团队解散后 merge 写入 → **Phase 2 加团队状态校验**
- MN-1：BucketResolver 文件位置 → **从 rag/config/ 移到 rag/upload/**
- MN-2：30 天延迟删除落地机制 → **OrphanChunkCleaner 自然闭环，无需独立表**
