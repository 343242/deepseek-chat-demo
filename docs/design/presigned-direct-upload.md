# Presigned URL 浏览器直传对象存储设计文档

> 模块：`com.smart.rag.rag.upload`（规划，未实施）
> 分支：`agentic-rag-dev`
> 状态：**设计稿，待评审**

## 概述

将上传数据面从「浏览器 → 后端 → MinIO」双跳代理改为「浏览器 → MinIO」直传：
后端只保留**控制面**（会话、签名、校验、落库、ETL 触发），字节流不再经过应用后端。
对齐 S3 生态主流直传模式（Uppy `@uppy/aws-s3`、各云厂商 JS SDK 的标准玩法）。

已确认的决策输入：

| 决策点 | 结论 |
|--------|------|
| MinIO Java SDK multipart API | SDK（含最新 9.0.3）不公开 `CreateMultipartUpload`/`UploadPart`/`CompleteMultipartUpload`，**手写 SigV4 签名**实现，不引入 AWS SDK |
| 大文件（>5MB） | 原生 S3 Multipart：后端 Create → 浏览器 presigned UploadPart 直传 → 后端 Complete |
| 小文件（≤5MB） | 单次 presigned PUT 直传（S3 单 PUT 上限 5GB，50MB 上限内无压力） |
| 断点续传 | 基于 `ListParts`：commit/续传前查询已传分片，客户端只补缺失分片 |
| 预览/下载 | **不动**，维持后端流式代理（CSP 沙箱 + Range + Cookie 鉴权，见 `document-original-file-preview-download.md`） |
| 交付节奏 | 设计先行；实施按「迁移路径」三阶段灰度 |

## 背景与动机

现行三条上传数据面全部经后端代理：

| 路径 | 现状 | 代价 |
|------|------|------|
| 单文件直传（≤5MB） | multipart 表单进后端 → 后端 `putObject` | 双跳带宽，后端内存缓冲 |
| 批量（≤10 个小文件） | 单请求携带全部文件 → 后端循环 `putObject` | 同上，且 55MB 上限受 `client_max_body_size` 约束 |
| 分片（>5MB） | 每片 PUT 后端 → 后端 `putObject` 临时对象 → `composeObject` 合并 | 双跳带宽 ×（1 + 分片数）次搬运 |

## 总体架构

申请-直传-确认（init → PUT → commit）两阶段提交。控制面在后端，数据面浏览器直达 MinIO：

```
浏览器                    后端（控制面）                 MinIO（数据面）
  │                          │                             │
  ├─ 1. POST /direct-uploads ─→ 鉴权/白名单/秒传/额度        │
  │     {fileName,size,mime,  │                             │
  │      fileChecksum,...}    │─ CreateMultipartUpload ────→│ (multipart 模式)
  │←── {mode, sessionId,──────│                             │
  │     uploadUrl | uploadId} │                             │
  │                          │                             │
  ├─ 2. PUT uploadUrl ─────────────────────────────────────────→  单次 PUT
  │     (XHR, 字节级进度)      │                             │
  ├─ 2'. PUT partUrl ×N 并发 ───────────────────────────────────→ UploadPart
  │     (缺失分片由 status 查询)│                             │
  │                          │                             │
  ├─ 3. POST .../commit ─────→ ListParts 校验完整性 ────────→ │
  │                          │  CompleteMultipartUpload ───→│ (multipart)
  │                          │  statObject + Tika MIME      │
  │                          │  整对象 SHA-256 复核          │
  │                          │  persist + DocumentCreatedEvent
  │                          │  dispatchAsync（outbox 异步） │
  │←── DocumentUploadResponse─│                             │
```

小文件（single）与 multipart 模式共用同一会话与 commit 机制，仅第 2 步数据面不同。

## API 设计

挂载在 `DirectUploadController`，个人 `/api/documents/direct-uploads`，团队 `/api/teams/{teamId}/documents/direct-uploads`（teamId 注入请求，与现有 ChunkUploadController 模式一致）。

| 方法 | URL | 说明 | 成功状态码 |
|------|-----|------|------------|
| POST | `/api/documents/direct-uploads` | init：声明文件元数据 → 秒传命中 / 签发 single URL / 创建 MPU | 200 |
| POST | `/api/documents/direct-uploads/{sessionId}/part-urls` | 批量签发分片 presigned URL（body: `{partNumbers:[...]}`） | 200 |
| GET | `/api/documents/direct-uploads/{sessionId}` | 状态查询（ListParts 差集 → 缺失分片列表，断点续传） | 200 |
| POST | `/api/documents/direct-uploads/{sessionId}/commit` | 确认：校验 + 合并/复核 + 落库 + ETL 投递 | 200 |
| POST | `/api/documents/direct-uploads/{sessionId}/abort` | 取消：AbortMultipartUpload / 删除 pending 对象 + 会话清理 | 204 |

### init 请求/响应

```jsonc
// 请求（fileChecksum 为前端流式 SHA-256，现有 computeChecksum 能力）
{ "fileName": "report.pdf", "fileSize": 12345678, "mimeType": "application/pdf",
  "fileChecksum": "9f86d081...", "teamId": null, "replaceDocumentId": null }

// 响应（三选一）
{ "mode": "instant",    "documentId": 42 }                          // 秒传命中
{ "mode": "single",     "sessionId": "uuid", "uploadUrl": "https://minio...?X-Amz-Signature=...",
  "expiresAt": 1699000000000, "headers": { "Content-Type": "application/pdf" } }
{ "mode": "multipart",  "sessionId": "uuid", "uploadId": "obj-uuid", "chunkSize": 5242880,
  "totalChunks": 3, "expiresAt": 1699000000000 }                    // 分片 URL 另行批量签发
```

init 处理顺序：鉴权 → `DocumentValidator` 白名单预检（扩展名 + 大小上限）→ 团队额度（`verifyUploadQuota`）→ 秒传判定（复用 `DocumentDedupService` BloomFilter + `findExistingForQuickUpload`，teamId 隔离规则不变）→ 创建 Redis 会话 → 签发。

## 手写 SigV4 签名器

新增 `com.smart.rag.rag.upload.s3` 包，两个组件，不依赖 AWS SDK：

### `S3SigV4Signer`（纯函数，可单测）

- 服务端调用签名：canonical request（含 payload SHA-256）→ string-to-sign → `HMAC-SHA256` 派生链（`kSigning = HMAC(HMAC(HMAC(HMAC("AWS4"+sk, date/region), "s3"), "aws4_request"), sts)`）→ `Authorization` 头
- presign 变体：查询串签名（`X-Amz-Algorithm/-Credential/-Date/-Expires/-SignedHeaders/-Signature`），payload 固定 `UNSIGNED-PAYLOAD`
- 单元测试用 AWS 官方 SigV4 测试套件向量（get-vanilla / presign 用例）做 golden 断言

### `S3MultipartGateway`（服务端 S3 REST 调用，Spring `RestClient` 承载）

| S3 操作 | HTTP | 用途 |
|---------|------|------|
| CreateMultipartUpload | `POST /{bucket}/{key}?uploads` | init（multipart 模式） |
| UploadPart（仅 presign，不发请求） | `PUT /{bucket}/{key}?partNumber={n}&uploadId={id}` | part-urls 签发 |
| ListParts | `GET /{bucket}/{key}?uploadId={id}` | commit 校验 / 断点续传差集 |
| CompleteMultipartUpload | `POST /{bucket}/{key}?uploadId={id}`（XML body：`<CompleteMultipartUpload><Part><PartNumber/><ETag/></Part>...`，ETag 取自 ListParts 结果，不信任前端） | commit |
| AbortMultipartUpload | `DELETE /{bucket}/{key}?uploadId={id}` | abort / 孤儿清理 |
| PutObject（仅 presign） | `PUT /{bucket}/{key}` | single 模式直传 |

关键细节：

- **Host 参与签名**：presigned URL 必须以浏览器可达的 `external-endpoint` 签发；服务端自用调用走内网 `endpoint` 签名。两个 endpoint 各自匹配即可，互不干扰。
- **region**：MinIO 默认 `us-east-1`，配置化（`spring.minio.region`，缺省 `us-east-1`），签名与服务端一致即可。
- **URL 编码**：object key 含中文/空格，canonical URI 需逐段 RFC 3986 编码（`documents/{userId}/{shortId}_{文件名}`），presign 与服务端调用共用同一编码实现，避免签名不一致。
- Complete 的 XML body 参与 payload SHA-256 签名；MinIO 返回 200 但 body 内嵌 `<Error>` 的情况需解析兜底（S3 已知坑）。

## 目标 Key 与两种模式的数据面差异

| | single（≤5MB） | multipart（>5MB） |
|---|---|---|
| 目标 key | `uploads/pending/{userId}/{shortId}_{name}` | 最终 key `documents/{userId}/{shortId}_{name}`（MPU 未 Complete 前不可见） |
| 校验通过后 | commit 时服务端 `copyObject` 到最终 key + 删 pending | Complete 直接落到最终 key |
| 孤儿形态 | pending 目录下的可见对象（cleaner 可扫） | 未完成 MPU 的隐藏分片存储（ILM 可清） |
| 分片约束 | 无 | 除末片外每片 ≥5MB（复用现有 `chunkSize=5MB` 约定，50MB 上限最多 10 片） |

single 模式经 pending 中转的原因：`documents/` 下只出现**通过 commit 校验**的对象，保持「有对象必有 DB 记录」不变式，孤儿清理简单。

## Redis 会话

复用 `ChunkSessionStore` 的模式，新前缀避免与旧分片会话冲突：

| Key 模式 | 类型 | TTL | 用途 |
|----------|------|-----|------|
| `direct:session:{sessionId}` | Hash | 24h | mode/fileChecksum/fileName/fileSize/mime/bucket/objectKey/uploadId/chunkSize/totalChunks/userId/teamId/replaceDocumentId/createdAt |
| `direct:file:{userId}:{fileChecksum}` | String | 24h | 反向索引：续传查找既有会话（对齐现有 `upload:file:` 语义） |
| `upload:rate:{userId}` | String | 60s | init 限流复用现有 `UploadRedisConstants`（10 次/分/用户） |

## 安全校验后移

presigned URL 无法强制 `Content-Length`（浏览器禁改该头），所有「事实校验」移到 commit：

| 校验项 | init（声明预检） | commit（服务端复核） |
|--------|----------------|---------------------|
| 扩展名/MIME 白名单 | ✅ `DocumentValidator` 预检 | ✅ Tika 魔数探测（对最终对象流式读头部，复用 `DocumentMimePolicy` 归一化） |
| 文件大小 ≤50MB | ✅ 声明值 | ✅ single: statObject；multipart: ListParts 各片尺寸求和 |
| 文件校验和 | —（信任声明） | ✅ 整对象流式 SHA-256 对拍声明值，不符则删对象 + 拒绝 |
| 分片完整性 | — | ✅ ListParts：分片序号连续、除末片 ≥5MB、总尺寸 == 声明 |
| 团队权限/额度 | ✅ `TeamAccessGate` + quota | ✅ commit 复核（防止 init 后额度被并发占满） |
| per-user 限流 | ✅ 10 次/分（复用现有脚本） | — |

- **per-part 校验和取消**：S3 presign 无法附带 SHA-256 条件；完整性由「末片后 ListParts 尺寸校验 + commit 整对象 SHA-256 复核」兜底，现有逐片 `X-Chunk-Checksum` 头在新路径不再需要。
- **presign 有效期 10 分钟**：过期重走 part-urls 签发，无状态成本；URL 即短期 bearer 能力，key 含随机 shortId，泄露影响面可控。

## commit 落库与 ETL

复用现有上传策略的落库语义（`persistDocument` + `DocumentCreatedEvent` + `dispatchAsync`）。
实施时从 `PersonalUploadStrategy` / `TeamUploadStrategy` 抽取共享 persist 组件（两处现存重复的 insert/事件/dedup 登记逻辑收敛为一点），直传 commit 与代理上传两条路径共用：

- 个人空间：persist（status=PROCESSING）→ dedup 登记 → 事件 → `dispatchAsync`（outbox 异步，同个人批量的改造后语义）
- 团队空间：对齐 `TeamUploadStrategy` —— auto-approve 直接 dispatch；普通成员 PENDING_APPROVAL + 审批记录，**不**触发 ETL
- `replaceDocumentId` 走 `DocumentCreatedEvent` 现有 supersede 流程，直传路径天然支持（现批量端点不支持 replace 的缺口顺带补齐）

## 断点续传与并发

- **续传**：GET status → 服务端 ListParts → 返回 `{uploadedChunks, missingChunks}`；前端只对 missing 签发 + 上传。MPU 分片服务端保留直至 abort/ILM，天然可续。
- **并发**：part-urls 支持批量签发（避免 N 次往返）；前端以 `concurrentChunks: 4`（常量已定义未用）并发 PUT 分片，单片失败单独重试。
- 取消：abort → AbortMultipartUpload（释放隐藏分片存储）+ 会话清理；single 模式删 pending 对象。

## 孤儿清理

三层防线：

1. **Redis 会话 TTL 24h**：会话自动过期；
2. **`DirectUploadOrphanCleaner` 定时任务**（对齐现有 `OrphanChunkCleaner` 模式，6h 间隔）：扫 `uploads/pending/` 下创建超 48h 且无活跃会话的对象删除；
3. **MinIO ILM 兜底**：`mc ilm rule add --abort-incomplete-multipart-upload-days 1`（部署时对全部 bucket 设置），过期未 Complete 的 MPU 由 MinIO 自动 abort。

## 部署与网络

### 配置新增（`spring.minio.*`，`MinioProperties` 扩展）

```yaml
spring.minio:
  endpoint: http://minio:9000                  # 服务端调用（内网，现状）
  external-endpoint: https://files.example.com # presign 签名用（浏览器可达）；缺省回退 endpoint（dev 同址）
  region: us-east-1
```

- dev：`http://localhost:9000` 两者同址，零配置可用；
- 容器部署：`external-endpoint` 指向外部可达地址（nginx 子域或直接暴露 9000）。

### MinIO bucket CORS（直传必需）

提供 `scripts/minio-cors.sh`（`mc cors set`，JSON 策略随 `app.cors.allowed-origins` 生成）+ docker-compose 增加 `minio-init` one-shot 服务（`minio/mc` 镜像）自动化：

```jsonc
{ "allowedOrigins": ["https://app.example.com"],   // 与后端 CORS 同源清单
  "allowedMethods": ["PUT", "GET", "HEAD"],        // 浏览器数据面仅 PUT；GET/HEAD 备用
  "allowedHeaders": ["content-type"],
  "exposeHeaders": ["etag"],
  "maxAgeSeconds": 3600 }
```

### nginx 可选反代（不暴露 9000 的部署形态）

子域 `files.example.com` → `proxy_pass http://minio:9000`，**必须 `proxy_set_header Host $host`**（签名含 Host，透传浏览器所见主机名才匹配）；`client_max_body_size` 对齐 50MB。dev（vite proxy）无需任何改动——直传不经过 `/api`。

## 前端改造（`upload-button.tsx` + `api/documents.ts`）

```
1. computeChecksum(file)                          // 现有流式 SHA-256
2. directUploadInit({...}) → instant 直接完成
3. single:  XHR PUT uploadUrl（onuploadprogress 字节级进度）→ commit
   multipart: 循环 { status 查缺失 → part-urls 批量签发 → 4 并发 XHR PUT }
              → 全部到位 → commit（幂等：重复 commit 按既有文档回查语义处理）
4. SSE 照旧驱动文档状态（/documents/events 不变）
```

- 进度改用 XHR（fetch 无上传进度事件）；single 模式获得真正的字节级进度（现批量端点只有 0→100 跳变，顺带修复）；
- 上传中断重入：init 秒传判定之外，`direct:file:` 反向索引命中既有未完成会话 → status 差集续传。

## 迁移路径（三阶段）

| 阶段 | 开关 | 说明 |
|------|------|------|
| 1 并存 | `app.upload.direct.enabled=false`（默认） | 新端点上线，前端 flag 关闭，代理路径不动；内部灰度验证 |
| 2 默认直传 | flag=true | 前端直传为主；init 返回 `direct-disabled` 或直传网络失败（CORS/断网）时自动降级现有代理路径 |
| 3 退役 | 移除 flag | 下线 `/multipart` 代理端点与 `ChunkUploadServiceImpl` 数据面（`ChunkSessionStore`/Lua/`OrphanChunkCleaner` 一并退役）；`composeObject` 相关代码随 `document-module-extraction` 的收敛计划处置 |

## 与现有设计文档的关系

| 文档 | 关系 |
|------|------|
| `chunk-upload.md` | 本方案实施后其数据面（putObject+composeObject 代理分片）被取代；阶段 3 前仍为现行实现 |
| `document-original-file-preview-download.md` | 「不向前端返回对象存储地址」约束**仅在下载侧维持**；上传侧由本方案修订（presigned URL 短期受限能力，非持久地址） |
| `document-module-extraction.md` | persist 逻辑抽取（三处 insert 收敛）与本方案 commit 复用点一致，可同批实施 |

## 错误码（新增）

| 错误码 | 枚举值 | 说明 |
|--------|--------|------|
| 50015 | `DIRECT_UPLOAD_MODE_INVALID` | 会话模式与请求不匹配（single 请求 part-urls 等） |
| 50016 | `DIRECT_UPLOAD_PARTS_INCOMPLETE` | commit 时分片不连续/缺失 |
| 50017 | `DIRECT_UPLOAD_SIZE_MISMATCH` | 实际尺寸与声明不符（含分片尺寸违规） |
| 50018 | `DIRECT_UPLOAD_CHECKSUM_MISMATCH` | 整对象 SHA-256 复核失败 |
| 50019 | `DIRECT_UPLOAD_COMPLETE_FAILED` | CompleteMultipartUpload / copyObject 失败 |

`UPLOAD_SESSION_NOT_FOUND`（50009）、`UPLOAD_FILE_TOO_LARGE`、`UPLOAD_MIME_UNSUPPORTED`、`RATE_LIMITED` 复用现有枚举。

## 测试策略

| 层 | 内容 |
|----|------|
| `S3SigV4SignerTest` | AWS 官方 SigV4 测试向量（golden）；presign 过期/编码边界（中文 key、空格、`+`） |
| `S3MultipartGatewayIT` | Testcontainers 起真实 MinIO，全流程 Create→UploadPart→ListParts→Complete→Abort 走通；Complete 假成功（body 内嵌 Error）解析 |
| `DirectUploadServiceTest` | init 分支矩阵（instant/single/multipart/限流/超限/团队额度）、commit 失败矩阵（缺片/尺寸/MIME/校验和）、团队审批分支、幂等 commit |
| 前端 vitest | `api/documents.ts` 直传封装、upload-button 流程（mock XHR，含 4 并发与续传差集） |

## 开放问题（评审待定）

1. **presign 签发频率**：4 并发 × 大文件时 part-urls 批量大小上限（建议单批 ≤20 片）；
2. **多实例 external-endpoint 一致性**：多实例部署需保证配置一致，否则同会话分片 URL 指向不同 host（签名各自有效，无正确性问题，仅体验）；
3. **聊天附件场景**：当前无附件上传，未来接入时是否复用 direct-uploads 会话（预留 teamId=null 个人会话即可，不预建）。
