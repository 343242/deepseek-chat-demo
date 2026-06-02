# P1: 基础设施

> 父任务：05-13-chunk-upload-v2
> 设计文档：`docs/design/chunk-upload.md` v5

## 目标

为分片上传功能搭建基础设施，不涉及业务逻辑。

## 交付物

### 1. 数据库迁移 `V8__rag_document_file_md5.sql`

路径：`src/main/resources/db/migration/V8__rag_document_file_md5.sql`

```sql
ALTER TABLE rag_document ADD COLUMN IF NOT EXISTS file_md5 VARCHAR(32);
COMMENT ON COLUMN rag_document.file_md5 IS '文件 MD5（服务端合并时计算），用于秒传校验';
CREATE INDEX IF NOT EXISTS idx_rag_document_file_md5
    ON rag_document (file_md5)
    WHERE file_md5 IS NOT NULL AND deleted = 0;
```

### 2. RagDocument 实体新增 fileMd5 字段

路径：`com.demo.chat.rag.entity.RagDocument`（确认实际路径）

新增：
```java
private String fileMd5;
```

### 3. ErrorCode 扩展

路径：`com.demo.chat.common.errorcode.ErrorCode`

在现有 ErrorCode 枚举中追加（避开已用的 50006）：
```java
UPLOAD_FAILED(50009, "上传失败"),
UPLOAD_CHUNK_MD5_MISMATCH(50010, "分片校验失败，请重传"),
UPLOAD_SESSION_NOT_FOUND(50011, "上传会话不存在或已过期"),
UPLOAD_FILE_MD5_MISMATCH(50012, "文件校验失败"),
UPLOAD_INCOMPLETE(50013, "文件未上传完整"),
```

### 4. Redis 常量类 `UploadRedisConstants`

路径：`com.demo.chat.rag.upload.UploadRedisConstants`

- SESSION_PREFIX = "upload:session:"
- PARTS_PREFIX = "upload:parts:"
- FILE_PREFIX = "upload:file:"
- RATE_PREFIX = "rate:upload:init:"
- SESSION_TTL = 24h
- RATE_WINDOW = 1min, RATE_LIMIT = 10
- 辅助方法：sessionKey(), partsKey(), fileKey(), rateKey()

### 5. ChunkSizeStrategy 接口 + 默认实现

路径：`com.demo.chat.rag.upload.ChunkSizeStrategy`（接口）
路径：`com.demo.chat.rag.upload.DefaultChunkSizeStrategy`（@Component 实现）

策略表：
| 文件大小 | 分片大小 |
|----------|---------|
| < 5MB | 不分片（返回 fileSize） |
| 5MB~100MB | 5MB |
| 100MB~500MB | 10MB |
| > 500MB | 20MB |

约束：totalChunks ≤ 10000，分片大小是 1MB 整数倍。

### 6. DTO record 类（5 个）

路径：`com.demo.chat.rag.upload` 包下

```java
// ChunkUploadInitRequest
public record ChunkUploadInitRequest(
    @NotBlank @Pattern(regexp = "^[0-9a-fA-F]{32}$") String fileMd5,
    @NotBlank @Size(max = 500) String fileName,
    @NotNull @Min(1) Long fileSize,
    @NotBlank String mimeType,
    @Min(1048576) @Max(52428800) Integer chunkSize
) {}

// ChunkUploadCompleteRequest
public record ChunkUploadCompleteRequest(
    @NotBlank @Pattern(regexp = "^[0-9a-fA-F]{32}$") String fileMd5
) {}

// ChunkUploadResult (init 响应：秒传/新建/续传统一)
public record ChunkUploadResult(
    boolean uploaded,
    String uploadId,
    Integer chunkSize,
    Integer totalChunks,
    List<Integer> uploadedChunks,
    Long documentId,
    String fileName
) {}

// ChunkUploadResponse (分片上传响应)
public record ChunkUploadResponse(
    String uploadId,
    int chunkIndex,
    boolean completed,
    Boolean merging
) {}

// ChunkUploadStatusResponse (状态查询响应)
public record ChunkUploadStatusResponse(
    String uploadId,
    String fileName,
    int totalChunks,
    List<Integer> uploadedChunks,
    boolean completed,
    boolean merging,
    Long documentId
) {}
```

## 验收标准

- [ ] V8 迁移脚本存在且幂等
- [ ] RagDocument 有 fileMd5 字段
- [ ] ErrorCode 有 5 个新枚举值（50009-50013）
- [ ] UploadRedisConstants 编译通过
- [ ] ChunkSizeStrategy 接口 + DefaultChunkSizeStrategy 实现编译通过
- [ ] 5 个 DTO record 编译通过
- [ ] 现有 214 个测试全部通过
