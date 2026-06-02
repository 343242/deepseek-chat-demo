# MinIO 团队 Bucket 隔离（Phase 1）

## PRD
详见 [prd-minio-team-bucket.md](../05-14-rag-parsers/prd-minio-team-bucket.md)

## Phase 1 实现清单

### 1. 新增 BucketResolver
- 文件：`rag/upload/BucketResolver.java`
- 单一类，`resolve(@Nullable Long teamId)` → bucket 名
- `defaultBucket()`、`isTeamBucket()` 辅助方法

### 2. 改造 PersonalUploadStrategy
- 移除 `MinioProperties` 依赖
- 注入 `BucketResolver`，`resolve(null)` 取默认 bucket

### 3. 改造 TeamUploadStrategy
- 移除 `MinioProperties` 依赖
- 注入 `BucketResolver`，`resolve(teamId)` 取团队 bucket
- `ensureBucketExists` 保留（首次上传时确保 bucket 存在）

### 4. 改造 TeamServiceImpl
- createTeam：事务外 `ensureBucketExists("rag-team-" + teamId)`
- dissolveTeam：注释 TODO 标记 bucket 延迟清理

### 5. 改造 OrphanChunkCleaner
- 注入 BucketResolver + MinioClient
- 构造函数不再固定 bucket
- 定时任务扫描所有 rag-team-* + 默认 bucket
- 顺便清理孤儿空桶（团队已软删）

### 6. 编译验证 + Git commit & push

## 验收
- [ ] 编译通过
- [ ] BucketResolver 单一类，无 Bean 歧义
- [ ] PersonalUploadStrategy 不再依赖 MinioProperties
- [ ] TeamUploadStrategy 使用 BucketResolver
- [ ] createTeam 创建团队 bucket
- [ ] OrphanChunkCleaner 扫描多 bucket
- [ ] Git commit + push
