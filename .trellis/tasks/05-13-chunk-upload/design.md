# 分片上传 + 断点续传方案 v2

## 核心流程

```
前端                                      后端                           MinIO              Redis
 │                                         │                              │                  │
 │  ① 计算文件 MD5                          │                              │                  │
 │── POST /init {md5,fileName,size,mime} ─→│                              │                  │
 │                                         │── 查 rag_document MD5 是否存在│                  │
 │                                         │   存在 → 秒传，直接返回文档 ID │                  │
 │                                         │   不存在 ↓                    │                  │
 │                                         │── 查 Redis 是否有未完成会话    │                  │
 │                                         │   有 → 返回已有 uploadId + 已传分片位图        │
 │                                         │   无 ↓                        │                  │
 │                                         │── createMultipartUpload ─────→│                  │
 │                                         │── SETBIT 初始化 BitSet ─────────────────────→  │
 │←─ { uploadId, chunkSize, totalChunks,   │                              │                  │
 │     uploadedBits: "00010011..." } ──────│                              │                  │
 │                                         │                              │                  │
 │  ② 按策略逐片上传（跳过已传的）              │                              │                  │
 │── PUT /upload {uploadId,idx,chunk,md5} →│                              │                  │
 │                                         │── 校验分片 MD5                │                  │
 │                                         │── uploadPart ────────────────→│                  │
 │                                         │── SETBIT 1 ──────────────────────────────────→  │
 │←─ { ok } ──────────────────────────────│                              │                  │
 │                                         │                              │                  │
 │  ③ 全部传完                              │                              │                  │
 │── POST /complete {uploadId, fileMd5} ──→│                              │                  │
 │                                         │── 校验文件总 MD5              │                  │
 │                                         │── 检查 BitSet 是否全部为 1    │                  │
 │                                         │── completeMultipartUpload ───→│                  │
 │                                         │── 删除分片文件 ───────────────→│                  │
 │                                         │── DEL Redis ─────────────────────────────────→  │
 │                                         │── persistDocument + dispatchAsync               │
 │←─ { id, status: "PROCESSING" } ────────│                              │                  │
```

## 秒传

```
前端传 fileMd5 → 后端查 rag_document 表是否已有相同 MD5 的已完成文档
  ├── 存在 → 直接返回文档信息（秒传，不重复上传）
  └── 不存在 → 走分片上传流程
```

rag_document 表需新增 `file_md5` 字段。

## Redis 存储模型

### BitSet — 分片上传状态

```
Key:  upload:bitmap:{uploadId}
Type: String（Redis BITSET 操作，SETBIT/GETBIT/BITCOUNT/BITFIELD）
语义: 第 i 位 = 1 表示第 i 个分片已上传
TTL:  24h

操作:
  初始化:  SETBIT upload:bitmap:{uid} {totalChunks-1} 0  (预分配)
  上传完成: SETBIT upload:bitmap:{uid} {chunkIndex} 1
  查询状态: GETBIT upload:bitmap:{uid} {chunkIndex}  (单个)
            BITFIELD upload:bitmap:{uid} GET u{totalChunks} 0  (全部，返回无符号整数)
  统计已传: BITCOUNT upload:bitmap:{uid}
  判断完成: BITCOUNT == totalChunks
```

BitSet 优势：1000 个分片只占 125 字节（1000 bits），远小于 Set 的内存开销。
返回前端时转成 01 字符串或 base64。

### Session 元数据

```
Key:  upload:session:{uploadId}
Type: Hash
Fields:
  fileMd5         — 文件总 MD5
  fileName        — 原始文件名
  fileSize        — 文件总大小 (bytes)
  mimeType        — MIME 类型
  chunkSize       — 每片大小 (bytes)
  totalChunks     — 总片数
  userId          — 上传者 ID
  bucket          — MinIO bucket
  minioUploadId   — MinIO multipart upload ID
  objectName      — MinIO 目标对象路径
  createdAt       — 创建时间戳
TTL:  24h

Part ETag 存储:
Key:  upload:etags:{uploadId}
Type: Hash
Field: chunkIndex → ETag
TTL:  24h
```

## API 设计

### 1. 初始化 / 秒传检查

```
POST /api/documents/multipart/init
Body: {
  fileMd5,       // 文件总 MD5（前端计算）
  fileName,
  fileSize,
  mimeType,
  chunkSize      // 前端期望的分片大小，后端可覆盖
}

Response 200 — 秒传命中:
{
  "code": 0,
  "data": {
    "uploaded": true,
    "documentId": 123,
    "fileName": "xxx.pdf"
  }
}

Response 201 — 需要上传:
{
  "code": 0,
  "data": {
    "uploaded": false,
    "uploadId": "019e1c84...",
    "chunkSize": 5242880,       // 后端确定的实际分片大小
    "totalChunks": 20,
    "uploadedBits": "00000000..." // 已传分片位图（续传场景有值）
  }
}
```

### 2. 上传单个分片

```
PUT /api/documents/multipart/upload
Form: {
  uploadId,
  chunkIndex,    // 0-based
  chunkMd5,      // 分片 MD5（前端计算）
  chunk          // 文件二进制
}

Response:
{
  "code": 0,
  "data": {
    "uploadId": "019e1c84...",
    "chunkIndex": 5
  }
}
```

校验流程：
1. 读 Redis session，校验 uploadId 存在 + userId 匹配
2. 校验 chunkIndex ∈ [0, totalChunks)
3. GETBIT 检查是否已上传（幂等，已传直接返回 ok）
4. 校验分片 MD5（接收到的字节计算 MD5 vs 前端传的 chunkMd5）
5. MinIO uploadPart → 获得 ETag
6. Redis SETBIT chunkIndex = 1
7. Redis HSET etags:{uploadId} chunkIndex ETag

### 3. 查询上传状态

```
GET /api/documents/multipart/{uploadId}/status

Response:
{
  "code": 0,
  "data": {
    "uploadId": "019e1c84...",
    "fileName": "xxx.pdf",
    "totalChunks": 20,
    "uploadedChunks": [0, 1, 3, 5, 7],  // 已传分片索引列表
    "uploadedBits": "10101010...",        // 位图字符串（前端可直接用）
    "completed": false
  }
}
```

### 4. 合并（所有分片完成时）

```
POST /api/documents/multipart/{uploadId}/complete
Body: {
  fileMd5    // 前端再次传文件总 MD5，服务端校验
}

Response:
{
  "code": 0,
  "data": {
    "id": 456,
    "fileName": "xxx.pdf",
    "status": "PROCESSING"
  }
}
```

校验流程：
1. 读 Redis session，校验 owner
2. 校验 fileMd5 与 session 中记录的一致
3. BITCOUNT 检查所有分片已上传
4. HGETALL etags:{uploadId}，按 chunkIndex 排序构建 Part 列表
5. MinIO completeMultipartUpload
6. 清理：删除分片临时对象 → 删除 Redis keys → 写 DB → 触发 ETL

### 5. 取消上传

```
POST /api/documents/multipart/{uploadId}/abort

Response:
{
  "code": 0,
  "message": "上传已取消"
}
```

## 双重 MD5 校验

| 校验点 | 何时 | 谁算 | 怎么比对 |
|--------|------|------|---------|
| **分片 MD5** | 每次上传 chunk | 前端算 chunkMd5 传过来，后端对收到的字节算 MD5 | 不一致拒绝，要求重传该分片 |
| **文件总 MD5** | init + complete | 前端算 fileMd5 | init 时记录到 Redis，complete 时比对，不一致拒绝合并 |

分片 MD5 防止网络传输损坏；文件总 MD5 防止前端篡改/选错文件。

## 分片大小策略

后端有最终决定权：

```
fileSize < 5MB   → chunkSize = fileSize（单片）
fileSize ≤ 100MB → chunkSize = 5MB
fileSize ≤ 500MB → chunkSize = 10MB
fileSize > 500MB → chunkSize = 20MB

totalChunks 上限 = 10000（S3 限制）
```

前端可以建议 chunkSize，后端按策略覆盖。

## 合并触发条件

**条件一：自动合并** — 上传最后一个分片时，SETBIT 后检查 BITCOUNT == totalChunks，自动触发合并（无需前端再调 /complete）

**条件二：手动合并** — 前端调 /complete 主动合并（适用于前端知道自己传完了但自动合并失败的情况）

两个条件都需要 fileMd5 校验。自动合并时 fileMd5 从 Redis session 取。

## 合并后清理

```
completeMultipartUpload 成功后:
  1. 删除分片临时对象（MinIO multipart complete 后分片自动清理，无需手动删除）
  2. DEL upload:session:{uploadId}
  3. DEL upload:bitmap:{uploadId}
  4. DEL upload:etags:{uploadId}
```

注：MinIO 的 completeMultipartUpload 成功后会自动清理 part 对象，不需要额外 removeObject。

## DB 变更

rag_document 表新增字段：

```sql
ALTER TABLE rag_document ADD COLUMN file_md5 VARCHAR(32);
CREATE INDEX idx_rag_document_file_md5 ON rag_document(file_md5);
```

用于秒传查询：`SELECT id FROM rag_document WHERE file_md5 = ? AND user_id = ? AND status = 'COMPLETED' AND deleted = 0`

## 类设计

```
upload/
├── ChunkUploadController.java          — REST 接口（5 个端点）
├── ChunkUploadService.java             — 业务接口
├── ChunkUploadServiceImpl.java         — 实现
├── ChunkUploadInitRequest.java         — 初始化请求 DTO
├── ChunkUploadResult.java              — 初始化响应 DTO（秒传/分片统一）
├── ChunkUploadResponse.java            — 分片上传响应 DTO
├── ChunkUploadStatusResponse.java      — 状态查询响应 DTO
└── ChunkSizeStrategy.java              — 分片大小策略
```

## 安全

- 所有操作校验 userId owner
- uploadId UUID 不可预测
- chunkIndex ∈ [0, totalChunks) 严格校验
- 单 chunk 大小上限 50MB
- 分片 MD5 不匹配拒绝上传
- 文件 MD5 不匹配拒绝合并
- Redis TTL 24h 自动过期
