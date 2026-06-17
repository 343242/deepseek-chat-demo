# 分片上传设计文档

> 模块：`com.demo.chat.rag.upload`（个人）、`com.demo.chat.team.controller`（团队）
> 分支：`rag-dev`

## 概述

为大文件（≤50MB）提供分片上传能力，支持秒传、断点续传、并发上传分片、异步合并。适用于网络不稳定或需要进度展示的场景。同时支持**个人文档**和**团队文档**的分片上传，通过 `BucketResolver` 实现 bucket 隔离。

## 整体流程

```
客户端                              服务端
  │                                   │
  ├─ 1. POST /multipart (init) ──────→ 创建 session / 秒传命中返回 200
  │                                   │
  ├─ 2. PUT /multipart/{id}/chunks/{index} (×N 并发)
  │   携带 X-Chunk-MD5 header         │
  │                                   ├─ Lua 原子写入 + 幂等检查
  │                                   ├─ 最后一个分片触发 auto-merge
  │                                   │
  ├─ 3. POST /multipart/{id}/complete ─→ 显式合并（auto-merge 失败时）
  │                                   │   composeObject → MD5 校验 → DB 写入 → ETL
  │                                   │
  ├─ 4. GET /multipart/{id} ─────────→ 断点续传：返回已上传分片列表
  │                                   │
  └─ 5. DELETE /multipart/{id} ──────→ 取消上传，清理资源
```

## 核心组件

| 组件 | 职责 |
|------|------|
| `BucketResolver` | Bucket 名称解析：个人文档使用默认 bucket，团队文档使用 `rag-team-{teamId}` |
| `ChunkUploadController` | 个人分片上传 5 个 RESTful 端点（`/api/documents/multipart`） |
| `TeamChunkUploadController` | 团队分片上传 5 个 RESTful 端点（`/api/teams/{teamId}/documents/multipart`） |
| `ChunkUploadServiceImpl` | 核心业务：session 管理、分片上传、合并、秒传、速率限制（个人/团队共用） |
| `OrphanChunkCleaner` | 定时任务：每 6h 清理个人 + 团队 bucket 的 48h 以上孤儿分片 |
| `UploadRedisConstants` | Redis key 前缀 + TTL 单一数据源 |
| `ChunkSizeStrategy` | 分片大小策略接口，默认 5MB |
| `atomic_chunk_upload.lua` | Lua 原子脚本：记录 ETag + 幂等检查 + 完成判定 + 合并锁 |
| `ChunkUploadCompleteResult` | complete 返回语义化 DTO |

## API 端点

### 个人文档分片上传

| 方法 | URL | 说明 | 成功状态码 |
|------|-----|------|------------|
| POST | `/api/documents/multipart` | 创建上传会话（秒传 / 新建 / 续传） | 200（秒传）/ 201（新建） |
| PUT | `/api/documents/multipart/{id}/chunks/{index}` | 上传分片（携带 `X-Chunk-MD5`） | 200 |
| GET | `/api/documents/multipart/{id}` | 查询上传状态（断点续传） | 200 |
| POST | `/api/documents/multipart/{id}/complete` | 触发合并 | 202 |
| DELETE | `/api/documents/multipart/{id}` | 取消上传 | 204 |

### 团队文档分片上传

| 方法 | URL | 说明 | 成功状态码 |
|------|-----|------|------------|
| POST | `/api/teams/{teamId}/documents/multipart` | 创建团队上传会话 | 200（秒传）/ 201（新建） |
| PUT | `/api/teams/{teamId}/documents/multipart/{id}/chunks/{index}` | 上传团队文档分片 | 200 |
| GET | `/api/teams/{teamId}/documents/multipart/{id}` | 查询团队上传状态 | 200 |
| POST | `/api/teams/{teamId}/documents/multipart/{id}/complete` | 触发团队文档合并 | 202 |
| DELETE | `/api/teams/{teamId}/documents/multipart/{id}` | 取消团队上传 | 204 |

> 团队分片上传通过 `TeamChunkUploadController` 将 URL 中的 `teamId` 注入 `ChunkUploadInitRequest`，由 `ChunkUploadServiceImpl` 统一处理，通过 `BucketResolver` 路由到团队专属 bucket。

## MinIO Bucket 隔离（BucketResolver）

`BucketResolver` 封装 bucket 选择逻辑，避免各处硬编码命名规则：

| 场景 | teamId | bucket 名称 |
|------|--------|-------------|
| 个人文档 | `null` | `MinioProperties.bucket`（默认 `rag-docs`） |
| 团队文档 | 非 null | `rag-team-{teamId}` |

```java
@Component
public class BucketResolver {
    public String resolve(@Nullable Long teamId) {
        return teamId == null
            ? minioProperties.getBucket()
            : TEAM_BUCKET_PREFIX + teamId;
    }
}
```

- 上传策略、定时任务、分片服务统一通过 `BucketResolver` 获取 bucket 名
- 团队 bucket 按需创建（`minio.makeBucket()`），无需预配置

## MinIO 合并方案

MinIO SDK 9.0.0 不暴露原生 Multipart Upload API，采用 `putObject` + `composeObject` 方案：

```
个人分片：putObject → {personal-bucket}/chunks/{userId}/{uploadId}/part-{index}
团队分片：putObject → rag-team-{teamId}/chunks/{userId}/{uploadId}/part-{index}
合并：    composeObject(chunks/...) → 目标路径
清理：    removeObjects 批量删除临时分片
```

## Redis 数据结构

| Key 模式 | 类型 | TTL | 用途 |
|----------|------|-----|------|
| `upload:session:{uploadId}` | Hash | 24h | 会话元数据（fileName, fileSize, mimeType, fileMd5, totalChunks, userId） |
| `upload:parts:{uploadId}` | Hash | 24h | 分片 ETag + `__merging` 合并锁标记 |
| `upload:file:{userId}:{fileMd5}` | String | 24h | 反向索引：秒传/续传查找已有 session |

## 安全措施

| 措施 | 说明 |
|------|------|
| multipart 大小限制 | `spring.servlet.multipart.max-request-size=55MB` |
| uploadId 格式校验 | UUID 正则防路径遍历 |
| init 速率限制 | 10 次/分/用户 |
| MIME 校验 | 委托 `DocumentProperties` 配置白名单 |
| X-Chunk-MD5 格式校验 | 32 位 hex 正则 |
| 分片大小校验 | 不超过 `ChunkSizeStrategy` 返回值 |
| 服务端 MD5 校验 | 合并后流式计算总 MD5，不信任前端声明 |
| 合并失败保留状态 | `__merging` 不清除，允许 complete 重试，靠 TTL 自动过期 |

## 线程池

合并操作异步执行，线程池配置：

| 参数 | 值 | 说明 |
|------|-----|------|
| `app.etl.executor.merge.core-pool-size` | 2 | 核心线程数 |
| `app.etl.executor.merge.max-pool-size` | 4 | 最大线程数 |
| `app.etl.executor.merge.queue-capacity` | 20 | 有界队列 |
| `app.etl.executor.merge.keep-alive-seconds` | 120 | 空闲线程存活时间 |
| `app.etl.executor.merge.thread-name-prefix` | `merge-` | 线程名前缀 |

使用 `NamedThreadFactory`（业务前缀 + UncaughtExceptionHandler），`CallerRunsPolicy` 拒绝策略。

## 孤儿清理

`OrphanChunkCleaner` 定时任务：

- 调度间隔：6 小时（初始延迟 5 分钟）
- 孤儿判定：session 创建时间距今 > 48 小时
- **双 bucket 清理**：扫描个人 bucket + 所有团队 bucket（`rag-team-*`）
- 防误删：跳过仍有活跃 session 的用户目录
- 批量删除：使用 `removeObjects` 批量清理 MinIO 临时对象

## 数据库迁移

`V8__chunk_upload.sql`：无需新增表，分片上传的最终文档复用 `rag_document` 表。

## 错误码

| 错误码 | 枚举值 | 说明 |
|--------|--------|------|
| 50009 | `UPLOAD_SESSION_NOT_FOUND` | 上传会话不存在 |
| 50010 | `UPLOAD_FILE_TOO_LARGE` | 文件超出大小限制 |
| 50011 | `UPLOAD_MIME_UNSUPPORTED` | MIME 类型不在白名单 |
| 50012 | `UPLOAD_CHUNK_OUT_OF_RANGE` | 分片序号越界 |
| 50013 | `UPLOAD_MERGE_FAILED` | 合并失败 |
