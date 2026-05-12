# 分片上传与断点续传 — 技术设计文档

> 版本：v5 | 日期：2026-05-13
> 状态：设计中，待确认
> 变更：v5 — 补充 Trellis spec 合规章节（日志规范、OCP 验证、Redis 常量、异常映射）

---

## 1. 需求概述

为 RAG 模块的文档上传功能增加大文件分片上传与断点续传能力，同时支持秒传。

**核心要求**：
- 前端计算文件 MD5，后端校验实现秒传
- 文件按策略分片，逐片上传，支持断点续传
- 双重 MD5 校验（分片级 + 文件级），**服务端增量计算文件总 MD5，不信任前端声明**
- Redis Hash 统一存储分片状态与 ETag
- **Redis Lua 脚本保证自动合并的原子性**
- MinIO 原生 Multipart Upload 存储分片，服务端合并
- 合并触发条件：所有分片上传完成（自动，Lua 原子触发）或用户主动请求（手动）
- 合并完成后清理分片文件与 Redis 数据

---

## 2. 整体流程

```
前端                                          后端                        MinIO           Redis
 │                                             │                           │               │
 │  ① 计算文件 MD5                               │                           │               │
 │── POST /multipart {md5,name,size,mime} ────→│                           │               │
 │                                             │── 查 DB file_md5 是否命中  │               │
 │                                             │   命中 → 返回秒传结果     │               │
 │                                             │   未命中 ↓                │               │
 │                                             │── GET upload:file:{uid}:{md5} ─────────────→│
 │                                             │   有 → 返回续传 + 已传列表│               │
 │                                             │   无 ↓                    │               │
 │                                             │── createMultipartUpload ─→│               │
 │                                             │── HSET session ─────────────────────────→  │
 │                                             │── SET 反向索引 ─────────────────────────→  │
 │←── 201 { uploadId, chunkSize, total, [] } ──│                           │               │
 │                                             │                           │               │
 │  ② 按策略逐片上传（跳过已传的）                  │                           │               │
 │── PUT /multipart/{uid}/chunks/{idx} ───────→│                           │               │
 │                                             │── 校验分片 MD5             │               │
 │                                             │── uploadPart(part=index+1)→│               │
 │                                             │── Lua 脚本原子操作 ──────────────────────→ │
 │                                             │   HSET parts + 累加MD5 + 检查完成          │
 │                                             │   全部完成 → 设 __merging 标记             │
 │                                             │   触发异步合并 ↓          │               │
 │←── 200 { ok, readyToMerge: true } ─────────│                           │               │
 │                                             │                           │               │
 │  ③ 异步合并（Lua 触发）                        │                           │               │
 │                                             │── 比对增量 MD5 vs 声明    │               │
 │                                             │── completeMultipartUpload →│               │
 │                                             │── 分片自动清理             │               │
 │                                             │── DEL Redis ────────────────────────────→ │
 │                                             │── 写 DB(含 file_md5) + 触发 ETL           │
 │                                             │                           │               │
 │  或前端主动查询/合并                             │                           │               │
 │── POST /multipart/{uid}/complete ──────────→│                           │               │
 │←── { id, status: "PROCESSING" } ───────────│                           │               │
```

---

## 3. 秒传机制

### 3.1 原理

前端计算文件 MD5，init 时传给后端。后端查 `rag_document` 表是否已存在相同 MD5 的已完成/处理中文档。

### 3.2 判定逻辑

```sql
SELECT id, file_name, status
FROM rag_document
WHERE file_md5 = ? AND user_id = ? AND status IN ('COMPLETED', 'UPLOADED', 'PROCESSING') AND deleted = 0
LIMIT 1
```

- **命中且 COMPLETED**：直接返回文档信息，跳过上传（秒传）
- **命中且 UPLOADED/PROCESSING**：返回文档信息（文件已存在，ETL 进行中或待执行）
- **未命中**：继续分片上传流程

### 3.3 file_md5 的可信度

`rag_document.file_md5` 由服务端在合并时**独立计算**（见 §7），不依赖前端声明。因此秒传查询基于可信数据。

### 3.4 限制

秒传仅限**同一用户**。不同用户不共享（数据隔离）。

---

## 4. 分片策略

后端拥有最终决定权，前端可建议 `chunkSize` 但后端按以下策略覆盖：

| 文件大小 | 分片大小 | 说明 |
|----------|---------|------|
| < 5 MB | 不分片（单片直传） | 走原有上传接口 |
| 5 MB ~ 100 MB | 5 MB | |
| 100 MB ~ 500 MB | 10 MB | |
| > 500 MB | 20 MB | |

**约束**：
- `totalChunks` 上限 10000（S3 Multipart Upload 限制）
- 分片大小必须是 1 MB 的整数倍
- 最后一个分片可以小于 chunkSize

### 4.1 chunkIndex 与 partNumber 的转换

**chunkIndex**（前端/Redis 使用）：0-based，`∈ [0, totalChunks)`
**partNumber**（MinIO API 使用）：1-based，`∈ [1, totalChunks]`

```
partNumber = chunkIndex + 1
```

所有 MinIO API 调用（uploadPart / completeMultipartUpload）必须使用 partNumber。Redis 和前端始终使用 chunkIndex。

---

## 5. Redis 存储模型

### 5.1 上传会话元数据

```
Key:    upload:session:{uploadId}
Type:   Hash
TTL:    24 小时
```

| Field | 类型 | 说明 |
|-------|------|------|
| fileMd5 | String | 前端声明的文件总 MD5（待合并时与实际 MD5 比对） |
| fileName | String | 原始文件名 |
| fileSize | String | 文件总大小 (bytes) |
| mimeType | String | MIME 类型 |
| chunkSize | String | 实际分片大小（后端确定） |
| totalChunks | String | 总分片数 |
| userId | String | 上传者 ID |
| bucket | String | MinIO bucket |
| minioUploadId | String | MinIO 返回的 multipart upload ID |
| objectName | String | MinIO 目标对象路径 |
| createdAt | String | 创建时间戳 |
| runningMd5 | String | 服务端增量 MD5（Base64 编码的 MessageDigest 序列化） |

`runningMd5` 字段说明：每次上传分片时，服务端用 `MessageDigest.update(chunkBytes)` 增量累加。由于 `MessageDigest` 不可序列化，实际存储方式为 **Hex 编码的中间 digest 值**（见 §7.1）。

### 5.2 分片状态与 ETag

```
Key:    upload:parts:{uploadId}
Type:   Hash
Field:  chunkIndex (String, "0", "1", "2", ...)
Value:  ETag (String, MinIO uploadPart 返回)
TTL:    24 小时
```

**特殊字段**：

| Field | 用途 |
|-------|------|
| `__merging` | 自动合并锁标记，值为 `"1"` 表示合并已触发，防止重复触发 |

### 5.3 反向索引

```
Key:    upload:file:{userId}:{fileMd5}
Type:   String
Value:  uploadId
TTL:    24 小时
```

**用途**：init 时 O(1) 查找该用户是否有同一文件的未完成上传会话（续传）。

**生命周期**：init 时创建，complete/abort 时删除。Redis TTL 过期后自动清理。

### 5.4 Redis Key 总览

| Key | 类型 | 用途 |
|-----|------|------|
| `upload:session:{uploadId}` | Hash | 会话元数据 + 增量 MD5 |
| `upload:parts:{uploadId}` | Hash | 分片 ETag + 状态 + 合并锁 |
| `upload:file:{userId}:{fileMd5}` | String | 反向索引（续传查找） |

---

## 6. API 接口设计

### 6.1 创建上传会话 / 秒传检查

```
POST /api/documents/multipart
Content-Type: application/json
Authorization: Bearer {token}
```

**Request**：
```json
{
  "fileMd5": "d41d8cd98f00b204e9800998ecf8427e",
  "fileName": "设计文档.pdf",
  "fileSize": 52428800,
  "mimeType": "application/pdf",
  "chunkSize": 5242880
}
```

**秒传响应**（200）：
```json
{
  "code": 0,
  "data": {
    "uploaded": true,
    "documentId": 123,
    "fileName": "设计文档.pdf"
  }
}
```

**新建上传会话响应**（201 + Location header）：
```
Location: /api/documents/multipart/{uploadId}
```
```json
{
  "code": 0,
  "data": {
    "uploaded": false,
    "uploadId": "019e1c84-7a16-11bb-47dd-b9aa3581abcd",
    "chunkSize": 5242880,
    "totalChunks": 10,
    "uploadedChunks": []
  }
}
```

**续传响应**（200）：
```json
{
  "code": 0,
  "data": {
    "uploaded": false,
    "uploadId": "019e1c84-7a16-11bb-47dd-b9aa3581abcd",
    "chunkSize": 5242880,
    "totalChunks": 10,
    "uploadedChunks": [0, 1, 3, 5, 7]
  }
}
```

**处理逻辑**：
1. 校验请求：`fileMd5` 格式（32位hex）、`fileName` 非空、`fileSize > 0`、`mimeType` 在白名单中、`fileSize` 不超过上限（调用现有 `DocumentValidator`）
2. 查 `rag_document.file_md5` 是否命中 → 命中返回秒传
3. `GET upload:file:{userId}:{fileMd5}` 查是否有未完成会话 → 有返回续传
4. 创建新会话：MinIO `createMultipartUpload` + Redis HSET session + SET 反向索引
5. 返回 201 + Location header

### 6.2 上传/替换单个分片

```
PUT /api/documents/multipart/{uploadId}/chunks/{chunkIndex}
Content-Type: application/octet-stream
X-Chunk-MD5: a1b2c3d4e5f6...
Authorization: Bearer {token}
Body: (binary chunk data)
```

**响应**（200）：
```json
{
  "code": 0,
  "data": {
    "uploadId": "019e1c84-...",
    "chunkIndex": 5,
    "completed": false
  }
}
```

**最后一个分片上传后自动触发合并的响应**（200）：
```json
{
  "code": 0,
  "data": {
    "uploadId": "019e1c84-...",
    "chunkIndex": 9,
    "completed": true,
    "merging": true
  }
}
```

**校验流程**：
1. 读 Redis session，校验 uploadId 存在 + userId 匹配
2. 校验 `chunkIndex ∈ [0, totalChunks)`
3. `HEXISTS upload:parts:{uploadId} "{chunkIndex}"` 检查是否已上传（幂等）
4. 对收到的字节计算 MD5，与 `X-Chunk-MD5` header 比对 → 不匹配返回 400
5. MinIO `uploadPart(partNumber = chunkIndex + 1)` → 获得 ETag
6. **Lua 脚本原子执行**：HSET parts + 增量 MD5 + 检查完成 + 设合并锁
7. Lua 返回"需合并" → 异步提交合并任务到线程池
8. 立即返回响应（不等待合并完成）

### 6.3 查询上传状态

```
GET /api/documents/multipart/{uploadId}
Authorization: Bearer {token}
```

**响应**：
```json
{
  "code": 0,
  "data": {
    "uploadId": "019e1c84-...",
    "fileName": "设计文档.pdf",
    "totalChunks": 10,
    "uploadedChunks": [0, 1, 3, 5, 7],
    "completed": false,
    "merging": false,
    "documentId": null
  }
}
```

**合并中/已完成响应**：
```json
{
  "code": 0,
  "data": {
    "uploadId": "019e1c84-...",
    "fileName": "设计文档.pdf",
    "totalChunks": 10,
    "uploadedChunks": [0, 1, 2, 3, 4, 5, 6, 7, 8, 9],
    "completed": true,
    "merging": true,
    "documentId": null
  }
}
```

**实现**：`HKEYS upload:parts:{uploadId}` + `HEXISTS upload:parts:{uploadId} __merging`。

### 6.4 手动合并

```
POST /api/documents/multipart/{uploadId}/complete
Content-Type: application/json
Authorization: Bearer {token}
```

**Request**：
```json
{
  "fileMd5": "d41d8cd98f00b204e9800998ecf8427e"
}
```

**响应**：
```json
{
  "code": 0,
  "data": {
    "documentId": 456,
    "fileName": "设计文档.pdf",
    "status": "PROCESSING"
  }
}
```

**用途**：自动合并失败或前端不确定是否成功时，手动触发。幂等。

**校验流程**：
1. 读 Redis session，校验 userId
2. 检查 session 是否已不存在（说明自动合并已成功完成）→ 直接查 DB 返回
3. 校验所有分片已上传
4. 执行合并（见 §8）
5. 返回文档信息

### 6.5 取消上传

```
DELETE /api/documents/multipart/{uploadId}
Authorization: Bearer {token}
```

**响应**（200）：
```json
{
  "code": 0,
  "message": "上传已取消"
}
```

**处理**：MinIO `abortMultipartUpload` + Redis DEL session / parts / 反向索引。

---

## 7. 双重 MD5 校验

### 7.1 分片级 MD5（防传输损坏）

| 项目 | 说明 |
|------|------|
| 触发时机 | 每次上传 chunk |
| 前端 | 计算 chunkMd5，通过 `X-Chunk-MD5` header 传入 |
| 后端 | 对收到字节算 MD5，与 header 比对 |
| 失败处理 | 返回 400，要求重传该分片 |

### 7.2 文件总 MD5（防篡改/选错文件）

| 项目 | 说明 |
|------|------|
| 触发时机 | 每次 uploadPart 后增量累加，complete 时最终校验 |
| 前端 | init 时声明 fileMd5 |
| 后端 | **独立增量计算，不信任前端** |

**增量计算方案**：

`MessageDigest` 不可序列化到 Redis。实际存储中间状态的方式：

```java
// 每次上传分片时
MessageDigest md = MessageDigest.getInstance("MD5");
md.update(chunkBytes);
byte[] intermediateDigest = md.digest();  // 取中间摘要
String hexDigest = HexFormat.of().formatHex(intermediateDigest);
// 存入 upload:parts Hash 的 __md5State 字段（由 Lua 脚本管理）
```

**但这样每片独立 digest 再拼接是错误的**。正确方案：

**将 MD5 中间状态编码为 Hex 字符串存入 Redis session**：

```java
// 初始化时（init）
// session 中 runningMd5 字段为空字符串

// 每次上传分片时（uploadChunk）
// 从 session 取出 runningMd5，解码恢复 MessageDigest 状态
// 但 MessageDigest 不支持状态恢复...
```

**最终方案：累加 Hex 摘要列表，complete 时重新计算**

由于 `MessageDigest` 无法序列化/恢复中间状态，采用以下方案：

```
Key:    upload:md5chunks:{uploadId}
Type:   List
Value:  每个分片的 MD5 hex（按 chunkIndex 顺序追加）
TTL:    24 小时
```

```
每次上传分片：
  1. 后端计算该分片 MD5 → hex
  2. 校验与 X-Chunk-MD5 一致
  3. RPUSH upload:md5chunks:{uploadId} {chunkMd5Hex}
  4. 同时 HSET upload:parts:{uploadId} {chunkIndex} {etag}
```

```
complete 时：
  1. 从 MinIO 合并后的对象不可直接算 MD5（需要下载）
  2. 改为：验证所有分片 MD5 的拼接与声明一致
```

**更优方案：合并后从 MinIO 流式读取计算 MD5**

```java
// 合并完成后，从 MinIO 下载并流式计算 MD5
MessageDigest md = MessageDigest.getInstance("MD5");
try (InputStream is = minioClient.getObject(GetObjectArgs.builder()
        .bucket(bucket).object(objectName).build())) {
    byte[] buffer = new byte[8192];
    int read;
    while ((read = is.read(buffer)) != -1) {
        md.update(buffer, 0, read);
    }
}
String actualMd5 = HexFormat.of().formatHex(md.digest());

// 与前端声明比对
if (!actualMd5.equalsIgnoreCase(session.get("fileMd5"))) {
    // 删除合并后的文件，标记失败
    minioClient.removeObject(...);
    throw new BusinessException(ErrorCode.UPLOAD_FILE_MD5_MISMATCH);
}
```

**选择此方案的理由**：
- MinIO `getObject` 返回 InputStream，流式计算不占内存
- 合并后的文件就是最终文件，MD5 最准确
- 一次 I/O 操作，简单可靠
- 大文件（500MB+）流式读取只需几秒

### 7.3 校验总结

| 校验 | 何时 | 谁算 | 比对 | 失败处理 |
|------|------|------|------|---------|
| 分片 MD5 | 每次 uploadChunk | 后端独立算 | vs X-Chunk-MD5 header | 400，重传该分片 |
| 文件总 MD5 | 合并后 | 后端独立算（MinIO 流式读取） | vs session 中前端声明的 fileMd5 | 删除合并文件 + 标记失败 |

---

## 8. 合并流程（自动 + 手动）

### 8.1 Lua 脚本：原子检查 + 设锁

```lua
-- atomic_chunk_upload.lua
-- KEYS[1] = upload:parts:{uploadId}
-- ARGV[1] = chunkIndex
-- ARGV[2] = etag
-- ARGV[3] = totalChunks

-- 1. 记录分片 ETag
redis.call('HSET', KEYS[1], ARGV[1], ARGV[2])

-- 2. 检查是否已标记合并中
local merging = redis.call('HEXISTS', KEYS[1], '__merging')
if tonumber(merging) == 1 then
    return {0, -1}  -- 已有其他线程在合并
end

-- 3. 检查是否所有分片已上传
local len = redis.call('HLEN', KEYS[1])
-- HLEN 包含 __merging 字段但此时它还不存在（或已存在但 merging==0）
-- 减去元数据字段数量
local metaFields = 0
if redis.call('HEXISTS', KEYS[1], '__merging') == 1 then
    metaFields = metaFields + 1
end

local uploadedCount = len - metaFields

if tonumber(uploadedCount) == tonumber(ARGV[3]) then
    -- 4. 原子设置合并锁
    redis.call('HSET', KEYS[1], '__merging', '1')
    return {1, uploadedCount}  -- 触发合并
end

return {0, uploadedCount}  -- 未完成
```

**Java 调用**：

```java
// ChunkUploadServiceImpl.uploadChunk() 中
Long result = stringRedisTemplate.execute(
    atomicChunkUploadScript,
    List.of("upload:parts:" + uploadId),
    String.valueOf(chunkIndex), etag, String.valueOf(totalChunks)
);
// result[0] == 1 → 触发异步合并
```

### 8.2 自动合并流程

Lua 脚本返回 `{1, N}` 时，**异步**提交合并任务：

```java
if (shouldMerge) {
    mergeExecutor.execute(() -> {
        try {
            performMerge(uploadId);
        } catch (Exception e) {
            log.error("Auto-merge failed: uploadId={}", uploadId, e);
            // 清除 __merging 标记，允许手动重试
            redisTemplate.opsForHash().delete("upload:parts:" + uploadId, "__merging");
        }
    });
}
```

**performMerge 流程**：

1. 从 Redis 读 session 元数据
2. `HGETALL upload:parts:{uploadId}` → 过滤掉 `__merging` → 按 chunkIndex 排序 → 构建 Part 列表（`partNumber = chunkIndex + 1`）
3. MinIO `completeMultipartUpload`
4. **从 MinIO 流式读取合并后文件，计算实际 MD5**
5. 与 session 中 `fileMd5` 比对 → 不匹配则删除文件 + 标记失败 + 返回
6. 持久化 `rag_document`（`file_md5` = 服务端计算的实际 MD5）
7. DEL Redis（session + parts + 反向索引 + md5chunks）
8. `dispatchAsync` 触发 ETL

### 8.3 手动合并

前端调用 `POST /complete`。幂等处理：

1. Redis session 不存在 → 查 DB 返回已有文档（已合并成功）
2. `__merging` 标记存在但 MinIO 未完成 → 等待或重试
3. 正常流程 → 执行 performMerge

### 8.4 异步合并线程池

```java
@Bean("mergeExecutor")
public ThreadPoolTaskExecutor mergeExecutor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(2);
    executor.setMaxPoolSize(4);
    executor.setQueueCapacity(20);
    executor.setThreadNamePrefix("merge-");
    executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
    executor.setWaitForTasksToCompleteOnShutdown(true);
    executor.setAwaitTerminationSeconds(120);
    executor.initialize();
    return executor;
}
```

---

## 9. 合并后清理

| 清理对象 | 方式 | 说明 |
|---------|------|------|
| MinIO 分片 | `completeMultipartUpload` 自动清理 | 无需手动删除 |
| Redis session | `DEL upload:session:{uploadId}` | |
| Redis parts | `DEL upload:parts:{uploadId}` | |
| Redis 反向索引 | `DEL upload:file:{userId}:{fileMd5}` | |
| Redis md5chunks | `DEL upload:md5chunks:{uploadId}` | （如果使用此方案） |

**孤儿清理定时任务**：

```java
@Scheduled(cron = "0 0 4 * * ?")  // 每天凌晨 4 点
public void cleanOrphanMultipartUploads() {
    // 1. MinIO listMultipartUploads 获取所有未完成的 upload
    // 2. 对比 Redis 中是否存在对应 session
    // 3. 不存在则 abortMultipartUpload
}
```

---

## 10. 数据库变更

```sql
-- V8__rag_document_file_md5.sql

ALTER TABLE rag_document ADD COLUMN IF NOT EXISTS file_md5 VARCHAR(32);

COMMENT ON COLUMN rag_document.file_md5 IS '文件 MD5（服务端合并时计算），用于秒传校验';

CREATE INDEX IF NOT EXISTS idx_rag_document_file_md5
    ON rag_document (file_md5)
    WHERE file_md5 IS NOT NULL AND deleted = 0;
```

`RagDocument` 实体类新增 `fileMd5` 字段。

---

## 11. 类设计

```
com.demo.chat.rag.upload/
├── ChunkUploadController.java          — REST 接口（5 个端点）
├── ChunkUploadService.java             — 业务接口
├── ChunkUploadServiceImpl.java         — 实现（Redis Lua + MinIO Multipart）
├── ChunkUploadInitRequest.java         — 初始化请求 record
├── ChunkUploadResult.java              — 初始化响应 record（秒传/新建/续传统一）
├── ChunkUploadResponse.java            — 分片上传响应 record
├── ChunkUploadStatusResponse.java      — 状态查询响应 record
├── ChunkUploadCompleteRequest.java     — 合并请求 record
└── ChunkSizeStrategy.java              — 分片大小策略工具类
```

**新增 ErrorCode**：

```java
UPLOAD_FAILED(50009, "上传失败"),
UPLOAD_CHUNK_MD5_MISMATCH(50010, "分片校验失败，请重传"),
UPLOAD_SESSION_NOT_FOUND(50011, "上传会话不存在或已过期"),
UPLOAD_FILE_MD5_MISMATCH(50012, "文件校验失败"),
UPLOAD_INCOMPLETE(50013, "文件未上传完整"),
```

**DTO 校验注解**：

```java
public record ChunkUploadInitRequest(
    @NotBlank @Pattern(regexp = "^[0-9a-fA-F]{32}$") String fileMd5,
    @NotBlank @Size(max = 500) String fileName,
    @NotNull @Min(1) Long fileSize,
    @NotBlank String mimeType,
    @Min(1048576) @Max(52428800) Integer chunkSize
) {}

public record ChunkUploadCompleteRequest(
    @NotBlank @Pattern(regexp = "^[0-9a-fA-F]{32}$") String fileMd5
) {}
```

---

## 12. 安全设计

| 措施 | 说明 |
|------|------|
| Owner 校验 | 所有操作读 Redis session 中的 userId，与当前用户比对 |
| uploadId 不可预测 | UUID v4 |
| chunkIndex 范围校验 | 严格 `∈ [0, totalChunks)` |
| 分片大小上限 | 单 chunk ≤ 50MB |
| **分片 MD5 后端独立计算** | 防传输损坏 |
| **文件总 MD5 后端独立计算** | 合并后从 MinIO 流式读取计算，防篡改 |
| **init 阶段调用 DocumentValidator** | 复用现有 MIME 白名单 + 文件大小限制 |
| 速率限制 | init 端点每用户每分钟最多 10 次（Redis 计数器） |
| Redis TTL | 24 小时自动过期 |
| 用户隔离 | 秒传仅查自己的文档 |
| **Lua 脚本原子操作** | 消除竞态条件 |

---

## 13. 异常场景处理

| 场景 | 处理 |
|------|------|
| 同一 chunkIndex 重复上传 | 幂等：HEXISTS 检查已传，直接返回 ok |
| 分片 MD5 不匹配 | 返回 400，前端重传该分片 |
| **文件总 MD5 不匹配** | **删除合并文件 + 标记失败 + 清除 __merging 允许重试** |
| 上传中断 | Redis 保留进度，前端恢复后查 status 续传 |
| **自动合并失败** | **清除 __merging 标记，前端可手动调 /complete 重试** |
| **部分成功**（MinIO merged 但 DB 未写） | **/complete 幂等：session 不存在 → 查 DB 已有文档** |
| Redis 过期（24h） | 会话失效，需重新 init |
| MinIO 不可用 | uploadPart 失败返回 500，前端重试 |
| **并发上传同一 chunkIndex** | **MinIO 同 partNumber 覆盖 + HSET 幂等 + Lua 原子** |
| **MinIO create 成功但 Redis 写失败** | **孤儿 upload 由定时任务清理** |
| **自动合并 + 手动合并并发** | **Lua __merging 锁保证只有一个执行** |

---

## 14. 后续扩展（本次不实现）

- [ ] 前端 SDK（JS/TS 分片上传组件）
- [ ] 上传进度 WebSocket/SSE 实时推送
- [ ] 管理端查看进行中的上传任务
- [ ] 上传限流（按用户/按文件）

---

## 15. OCP 验证 — 新增同类功能的步骤

本章节验证设计符合开闭原则：新增同类功能只需新增类，不修改现有代码。

### 15.1 新增存储后端（如阿里云 OSS）

| 步骤 | 操作 | 修改现有文件？ |
|------|------|--------------|
| 1 | 新增 `OssChunkUploadServiceImpl` 实现 `ChunkUploadService` 接口 | ❌ |
| 2 | 新增 `OssChunkUploadConfiguration` 配置类 | ❌ |
| 3 | 新增 `OssProperties` 配置类（`@ConfigurationProperties`） | ❌ |
| 4 | 通过 `@ConditionalOnProperty("upload.storage-type")` 切换 Bean | ❌ |
| 5 | Controller 零改动（依赖 `ChunkUploadService` 接口） | ❌ |

### 15.2 新增分片策略

| 步骤 | 操作 | 修改现有文件？ |
|------|------|--------------|
| 1 | 新增策略类（如 `ProgressiveChunkSizeStrategy`） | ❌ |
| 2 | 通过 `@ConditionalOnProperty` 或 `@Primary` 切换 | ❌ |
| 3 | `ChunkUploadServiceImpl` 构造器注入 `ChunkSizeStrategy` 接口 | ❌ |

> 注：`ChunkSizeStrategy` 当前设计为工具类（静态方法），实现时应改为接口 + 默认实现，以支持策略替换。

### 15.3 新增文件校验算法（如 SHA-256）

| 步骤 | 操作 | 修改现有文件？ |
|------|------|--------------|
| 1 | 新增 `Sha256ChecksumVerifier` 实现 `ChecksumVerifier` 接口 | ❌ |
| 2 | 修改 DTO（新增 `fileSha256` 字段）→ DTO 是新增的，不改旧 DTO | ❌ |
| 3 | `ChunkUploadServiceImpl` 注入 `ChecksumVerifier` | ❌ |

### 15.4 设计建议

为确保 OCP，实现时应将 `ChunkSizeStrategy` 从工具类改为接口：

```java
public interface ChunkSizeStrategy {
    int calculateChunkSize(long fileSize);
}

@Component
public class DefaultChunkSizeStrategy implements ChunkSizeStrategy {
    @Override
    public int calculateChunkSize(long fileSize) {
        if (fileSize <= 5_242_880) return (int) fileSize;      // 不分片
        if (fileSize <= 104_857_600) return 5_242_880;        // 5MB
        if (fileSize <= 524_288_000) return 10_485_760;       // 10MB
        return 20_971_520;                                     // 20MB
    }
}
```

---

## 16. 日志规范

所有日志使用 SLF4J 参数化格式，不拼接字符串。

### 16.1 日志级别与场景

| 操作 | 级别 | 示例 |
|------|------|------|
| 初始化上传会话 | INFO | `log.info("Chunk upload init: uploadId={}, file={}, size={}, user={}", uid, name, size, userId)` |
| 秒传命中 | INFO | `log.info("Quick upload hit: fileMd5={}, userId={}, docId={}", md5, userId, docId)` |
| 分片上传成功 | DEBUG | `log.debug("Chunk uploaded: uploadId={}, index={}, etag={}", uid, idx, etag)` |
| 分片 MD5 校验失败 | WARN | `log.warn("Chunk MD5 mismatch: uploadId={}, index={}, expected={}, actual={}", uid, idx, expected, actual)` |
| Owner 校验失败 | WARN | `log.warn("Upload owner mismatch: uploadId={}, expected={}, actual={}", uid, owner, caller)` |
| 文件总 MD5 校验失败 | WARN | `log.warn("File MD5 mismatch: uploadId={}, expected={}, actual={}", uid, expected, actual)` |
| 合并完成 | INFO | `log.info("Chunk upload merged: uploadId={}, docId={}, md5={}", uid, docId, md5)` |
| 合并失败 | ERROR | `log.error("Auto-merge failed: uploadId={}", uid, e)` |
| 上传取消 | INFO | `log.info("Chunk upload aborted: uploadId={}, user={}", uid, userId)` |
| 孤儿清理 | INFO | `log.info("Orphan upload cleaned: minioUploadId={}, bucket={}", mUid, bucket)` |
| Redis session 不存在 | WARN | `log.warn("Upload session not found: uploadId={}", uid)` |

### 16.2 禁止事项

- ❌ 不在日志中记录分片二进制内容
- ❌ 不用 `e.printStackTrace()`
- ❌ 不在循环中打 INFO 以上级别（分片上传循环中用 DEBUG）
- ❌ 不记录用户 Token 原文

### 16.3 Profile 差异

| Profile | 包日志级别 | 说明 |
|---------|-----------|------|
| `dev` | `com.demo.chat.rag.upload: DEBUG` | 开发调试，可见每个分片上传细节 |
| `stable` | `com.demo.chat.rag.upload: INFO` | 测试环境，关键节点 |
| `prod` | `com.demo.chat.rag.upload: WARN` | 生产最小化，仅异常和警告 |

---

## 17. Redis 常量类

所有 Redis key 前缀和 TTL 集中定义，禁止散落在业务代码中硬编码。

```java
package com.demo.chat.rag.upload;

import java.time.Duration;

/**
 * 分片上传模块 Redis 常量。
 * 单一数据源，所有 key 前缀和 TTL 统一在此定义。
 */
public final class UploadRedisConstants {

    private UploadRedisConstants() {}

    // ---- Key 前缀 ----

    /** 上传会话元数据 Hash */
    public static final String SESSION_PREFIX = "upload:session:";

    /** 分片状态 + ETag Hash */
    public static final String PARTS_PREFIX = "upload:parts:";

    /** 反向索引（续传查找）*/
    public static final String FILE_PREFIX = "upload:file:";

    // ---- TTL ----

    /** 所有上传相关 key 的统一 TTL */
    public static final Duration SESSION_TTL = Duration.ofHours(24);

    // ---- 速率限制 ----

    /** init 端点限流 key 前缀 */
    public static final String RATE_PREFIX = "rate:upload:init:";

    /** init 端点限流窗口 */
    public static final Duration RATE_WINDOW = Duration.ofMinutes(1);

    /** init 端点限流上限 */
    public static final int RATE_LIMIT = 10;

    // ---- 辅助方法 ----

    public static String sessionKey(String uploadId) {
        return SESSION_PREFIX + uploadId;
    }

    public static String partsKey(String uploadId) {
        return PARTS_PREFIX + uploadId;
    }

    public static String fileKey(Long userId, String fileMd5) {
        return FILE_PREFIX + userId + ":" + fileMd5;
    }

    public static String rateKey(Long userId) {
        return RATE_PREFIX + userId;
    }
}
```

**使用示例**：

```java
// ✅ 正确：使用常量
stringRedisTemplate.opsForValue().set(
    UploadRedisConstants.fileKey(userId, fileMd5),
    uploadId,
    UploadRedisConstants.SESSION_TTL
);

// ❌ 错误：硬编码
stringRedisTemplate.opsForValue().set(
    "upload:file:" + userId + ":" + fileMd5,
    uploadId,
    Duration.ofHours(24)
);
```

---

## 18. MinIO 异常映射

MinIO SDK 抛出的异常需转换为 `BusinessException`，避免底层异常泄漏到 API 层。

### 18.1 异常映射表

| MinIO 异常 | 转换为 | HTTP 状态 | 说明 |
|-----------|--------|----------|------|
| `ErrorResponseException` (503) | `UPLOAD_FAILED(50009)` | 500 | MinIO 服务不可用 |
| `ErrorResponseException` (NoSuchUpload) | `UPLOAD_SESSION_NOT_FOUND(50011)` | 404 | uploadId 对应的 Multipart Upload 不存在（已过期或已取消） |
| `ErrorResponseException` (EntityTooSmall) | `UPLOAD_FAILED(50009)` | 400 | 分片太小 |
| `InsufficientDataException` | `UPLOAD_CHUNK_MD5_MISMATCH(50010)` | 400 | 传输数据不完整 |
| `IOException` | `UPLOAD_FAILED(50009)` | 500 | 网络异常 |
| `ServerException` | `UPLOAD_FAILED(50009)` | 500 | MinIO 内部错误 |

### 18.2 封装方式

在 `ChunkUploadServiceImpl` 中封装 MinIO 操作，统一 try-catch：

```java
private Part uploadPartToMinio(String bucket, String objectName,
        String minioUploadId, int partNumber, byte[] chunkBytes) {
    try {
        return minioClient.uploadPart(
            UploadPartArgs.builder()
                .bucket(bucket)
                .object(objectName)
                .uploadId(minioUploadId)
                .partNumber(partNumber)
                .partSize((long) chunkBytes.length)
                .stream(new ByteArrayInputStream(chunkBytes), chunkBytes.length, -1)
                .build()
        );
    } catch (ErrorResponseException e) {
        log.error("MinIO uploadPart error: bucket={}, object={}, part={}, code={}",
            bucket, objectName, partNumber, e.errorResponse().code(), e);
        throw new BusinessException(ErrorCode.UPLOAD_FAILED, "存储服务异常");
    } catch (InsufficientDataException e) {
        log.warn("MinIO insufficient data: bucket={}, object={}, part={}",
            bucket, objectName, partNumber);
        throw new BusinessException(ErrorCode.UPLOAD_CHUNK_MD5_MISMATCH, "分片数据不完整，请重传");
    } catch (Exception e) {
        log.error("MinIO uploadPart unexpected error: bucket={}, object={}, part={}",
            bucket, objectName, partNumber, e);
        throw new BusinessException(ErrorCode.UPLOAD_FAILED, "存储服务异常");
    }
}
```

### 18.3 全局异常处理器补充

现有 `GlobalExceptionHandler` 已处理 `BusinessException`，无需修改。
MinIO 异常在 Service 层已转换为 `BusinessException`，不会泄漏到 Controller 层。

---

## 19. SecurityConfig 路径注册

新增的 `/api/documents/multipart/**` 路径需要在 Spring Security 配置中注册。

```java
// 在现有 SecurityConfig 中追加（仅新增一行，不修改现有规则）
http.authorizeHttpRequests(auth -> auth
    // ... 现有规则 ...
    .requestMatchers("/api/documents/multipart/**").authenticated()
    // ...
);
```

**注意**：此路径仅需 `authenticated()`，不需要 `permitAll()`。Owner 校验在 Service 层通过 Redis session userId 比对实现。

---

## 附录 A：变更记录

### v4 → v5（Trellis Spec 合规补充）

| # | 问题来源 | 变更内容 |
|---|---------|---------|
| 1 | Quality Guidelines — OCP 验证 Checklist | §15 新增 OCP 验证章节（4 个扩展场景 + ChunkSizeStrategy 接口化建议） |
| 2 | Logging Guidelines | §16 新增日志规范章节（11 个场景 + 禁止事项 + Profile 差异） |
| 3 | Code Reuse Guide | §17 新增 Redis 常量类 UploadRedisConstants |
| 4 | Cross-Layer Guide + Error Handling | §18 新增 MinIO 异常映射（6 种异常 + 封装方法） |
| 5 | Quality Guidelines — 安全检查清单 | §19 新增 SecurityConfig 路径注册说明 |

### v3 → v4（两轮架构审核整合）

| # | 问题来源 | 变更内容 |
|---|---------|---------|
| 1 | 审核一 P0 + 审核二 P0 | ErrorCode 避开 50006，从 50009 开始分配 |
| 2 | 审核一 P0 + 审核二 P0 | §4.1 明确 partNumber = chunkIndex + 1 转换规则 |
| 3 | 审核二 P0 | §7 服务端增量计算文件总 MD5（MinIO 流式读取），不信任前端 |
| 4 | 审核一 P1 + 审核二 P0 | §5.3 新增 `upload:file:{userId}:{md5}` 反向索引 |
| 5 | 审核一 P1 | §8.1 Lua 脚本原子操作消除自动合并竞态 |
| 6 | 审核一 P1 | §8.2 自动合并改为异步（mergeExecutor 线程池），不阻塞请求线程 |
| 7 | 审核二 P1 | §6 API URL 重构为 RESTful 风格（名词 + 标准 HTTP 方法） |
| 8 | 审核二 P1 | §6.1 init 返回 201 Created + Location header |
| 9 | 审核二 P1 | §11 DTO 显式 record + @Valid + Bean Validation 注解 |
| 10 | 审核一 P1 | §6.1 init 阶段调用现有 DocumentValidator 校验 MIME 和大小 |
| 11 | 审核一 P1 | §10 迁移版本号修正为 V8 |
| 12 | 审核一 P1 | §9 新增孤儿 Multipart Upload 定时清理任务 |
| 13 | 审核一 P2 | §3.2 秒传扩大到 COMPLETED + UPLOADED + PROCESSING 状态 |
| 14 | 审核一 P2 | §12 新增 init 端点速率限制（每用户每分钟 10 次） |

| # | 问题来源 | 变更内容 |
|---|---------|---------|
| 1 | 审核一 P0 + 审核二 P0 | ErrorCode 避开 50006，从 50009 开始分配 |
| 2 | 审核一 P0 + 审核二 P0 | §4.1 明确 partNumber = chunkIndex + 1 转换规则 |
| 3 | 审核二 P0 | §7 服务端增量计算文件总 MD5（MinIO 流式读取），不信任前端 |
| 4 | 审核一 P1 + 审核二 P0 | §5.3 新增 `upload:file:{userId}:{md5}` 反向索引 |
| 5 | 审核一 P1 | §8.1 Lua 脚本原子操作消除自动合并竞态 |
| 6 | 审核一 P1 | §8.2 自动合并改为异步（mergeExecutor 线程池），不阻塞请求线程 |
| 7 | 审核二 P1 | §6 API URL 重构为 RESTful 风格（名词 + 标准 HTTP 方法） |
| 8 | 审核二 P1 | §6.1 init 返回 201 Created + Location header |
| 9 | 审核二 P1 | §11 DTO 显式 record + @Valid + Bean Validation 注解 |
| 10 | 审核一 P1 | §6.1 init 阶段调用现有 DocumentValidator 校验 MIME 和大小 |
| 11 | 审核一 P1 | §10 迁移版本号修正为 V8 |
| 12 | 审核一 P1 | §9 新增孤儿 Multipart Upload 定时清理任务 |
| 13 | 审核一 P2 | §3.2 秒传扩大到 COMPLETED + UPLOADED + PROCESSING 状态 |
| 14 | 审核一 P2 | §12 新增 init 端点速率限制（每用户每分钟 10 次） |
