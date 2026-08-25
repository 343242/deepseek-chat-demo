# Presigned URL 浏览器直传对象存储设计文档

> 模块：`com.smart.rag.rag.upload`（已实施阶段 1，见文末「实施记录」）
> 分支：`agentic-rag-dev`
> 状态：**阶段 1 已实施**（原设计稿经评审后落地；实施期发现的与设计的偏差全部记录在文末实施记录）

## 概述

将上传数据面从「浏览器 → 后端 → MinIO」双跳代理改为「浏览器 → MinIO」直传：
后端只保留**控制面**（会话、签名、校验、落库、ETL 触发），上传字节流不再经过应用后端。
（成本注记：commit 校验仍需后端完整读一遍对象做 Tika 探测与 SHA-256 复核——相对旧路径后端流量减半而非归零，详见「安全校验后移」。）
对齐 S3 生态主流直传模式（Uppy `@uppy/aws-s3`、各云厂商 JS SDK 的标准玩法）。

已确认的决策输入：

| 决策点 | 结论 |
|--------|------|
| MinIO Java SDK multipart API | SDK 9.0.3 **公开全套 MPU 原语**（`BaseS3Client`：`createMultipartUpload`/`uploadPart`/`completeMultipartUpload`/`abortMultipartUpload`，自 8.5.15 起 public、9.0 线延续），presigned URL 走 `getPresignedObjectUrl(method(PUT), extraQueryParams(partNumber, uploadId))`——**全部基于 SDK 公开 API，不手写 SigV4、不引入 AWS SDK**（已实测端到端验证，见「SDK 承载」节）。已知缺陷：`listParts` 构建器无法设置 object，规避见「断点续传」 |
| 大文件（>5MB） | 原生 S3 Multipart：后端 Create → 浏览器 presigned UploadPart 直传 → 后端 Complete |
| 小文件（≤5MB） | 单次 presigned PUT 直传（S3 单 PUT 上限 5GB，50MB 上限内无压力） |
| 断点续传 | 前端本地记录已传分片（SDK `listParts` 在 9.0.3 有构建器缺陷，服务端差集不可用）；MPU 已消亡返回 `UPLOAD_GONE` 重新 init |
| 预览/下载 | **不动**，维持后端流式代理（CSP 沙箱 + Range + Cookie 鉴权，见 `document-original-file-preview-download.md`） |
| 交付节奏 | 设计先行；实施按「迁移路径」三阶段灰度 |

## 背景与动机

现行三条上传数据面全部经后端代理：

| 路径 | 现状 | 代价 |
|------|------|------|
| 单文件直传（≤5MB） | multipart 表单进后端 → 后端 `putObject` | 双跳带宽，后端内存缓冲 |
| 批量（≤10 个小文件） | 单请求携带全部文件 → 后端循环 `putObject` | 同上；体积受多层限制约束：业务单文件 50MB（`DocumentProperties`）→ spring `max-file-size: 55MB`（单文件余量）→ `max-request-size: 205MB`（批量请求上限，业务批量 200MB + 边界开销）→ nginx `client_max_body_size 60m`（外层放行） |
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
  ├─ 3. POST .../commit ─────→ Part 列表校验（前端回传       │
  │     {parts:[{n,etag}]}     number/etag/size 对拍声明）    │
  │                          │  CompleteMultipartUpload ───→│ (multipart，落 pending key；S3 校验 ETag)
  │                          │  statObject + Tika MIME      │
  │                          │  整对象 SHA-256 复核          │
  │                          │  copyObject → 最终 key + 删 pending
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
| POST | `/api/documents/direct-uploads/{sessionId}/part-urls` | 批量签发分片 presigned URL（body: `{partNumbers:[...]}`，**单批 ≤20 片**，超出按参数错误拒绝） | 200 |
| GET | `/api/documents/direct-uploads/{sessionId}` | 状态查询（会话元数据：uploadId/chunkSize/totalChunks；MPU 已消亡时返回 `UPLOAD_GONE` 引导重新 init） | 200 |
| POST | `/api/documents/direct-uploads/{sessionId}/commit` | 确认：校验 + 合并/复核 + 落库 + ETL 投递 | 200 |
| POST | `/api/documents/direct-uploads/{sessionId}/abort` | 取消：AbortMultipartUpload / 删除 pending 对象 + 会话清理 | 204 |

所有会话端点（part-urls / status / commit / abort）入口第一步统一校验：会话存在 → `session.userId == 当前用户`（对齐现有 `ChunkUploadServiceImpl.validateOwner` 惯例，防会话劫持）→ 团队端点请求 `teamId` 与会话 `teamId` 一致。part-urls 另校验 `partNumbers` 非空、去重、∈ [1, totalChunks] 且**单批 ≤20 片**，越界/超量按参数错误拒绝。init 请求的 `fileChecksum` 复用现有 `@Pattern("^[0-9a-fA-F]{64}$")` 校验（`ChunkUploadCompleteRequest` 同款），防反向索引 Redis key 注入畸形串。

### init 请求/响应

```jsonc
// 请求（fileChecksum 为前端流式 SHA-256，现有 computeChecksum 能力）
{ "fileName": "report.pdf", "fileSize": 12345678, "mimeType": "application/pdf",
  "fileChecksum": "9f86d081...", "teamId": null, "replaceDocumentId": null }

// 响应（三选一）
{ "mode": "instant",    "documentId": 42 }                          // 秒传命中
{ "mode": "single",     "sessionId": "uuid", "uploadUrl": "https://minio...?X-Amz-Signature=...",
  "expiresAt": 1787622000000, "headers": { "Content-Type": "application/pdf" } }
{ "mode": "multipart",  "sessionId": "uuid", "uploadId": "obj-uuid", "chunkSize": 5242880,
  "totalChunks": 3, "expiresAt": 1787622000000 }                    // 分片 URL 另行批量签发
```

init 处理顺序：鉴权 → `DocumentValidator` 白名单预检（扩展名 + 大小上限）→ 团队额度（`verifyUploadQuota`）→ 秒传判定（复用 `DocumentDedupService` BloomFilter + `findExistingForQuickUpload`，teamId 隔离规则不变）→ 创建 Redis 会话 → 签发。

## SDK 承载：MinioAsyncClient + presign（不手写 SigV4）

**修正记录**：初版设计基于「SDK（含 9.0.3）不公开 multipart API」的前提计划手写 SigV4 签名器——**该前提有误**（`ChunkUploadServiceImpl` 中"9.0.0 不暴露 MPU API"的过时 javadoc 被误继承）。实测（javap 9.0.3 jar + 真实容器端到端）：自 8.5.15（PR #1607）起底层 MPU API 已转 public，9.0.3 中位于 `BaseS3Client`（`MinioAsyncClient extends BaseS3Client`）。实施时顺带修正上述过时注释。

新增 `com.smart.rag.rag.upload.s3` 包，单个组件 `S3MultipartGateway`，全部基于 SDK 公开 API：

| 能力 | SDK 调用 | 用途 |
|------|----------|------|
| CreateMultipartUpload | `asyncClient.createMultipartUpload(...)` | init（multipart 模式） |
| presign 分片 URL | `presignClient.getPresignedObjectUrl(method(PUT), extraQueryParams({partNumber, uploadId}))` | part-urls 签发 |
| presign 单文件 URL | `presignClient.getPresignedObjectUrl(method(PUT))` | single 模式直传 |
| CompleteMultipartUpload | `asyncClient.completeMultipartUpload(...parts(Part[]))` | commit（ETag 前端回传，S3 侧校验） |
| AbortMultipartUpload | `asyncClient.abortMultipartUpload(...)` | abort / 孤儿清理（实测幂等：重复 abort 无异常） |
| ListMultipartUploads | `asyncClient.listMultipartUploads(prefix)` | cleaner 扫未 Complete MPU（其 Builder 完整可用，与 `listParts` 的构建器缺陷不同） |
| copyObject / statObject / removeObject | `MinioClient`（同步） | commit 复核与 pending 清理 |

- **双 client**：`MinioClient(endpoint)` 服务端自用（内网）+ `MinioClient(external-endpoint)` 专责 presign——presigned URL 必须以浏览器可达主机名签发（Host 参与签名），SDK 按 client 的 endpoint 处理，签名/编码细节全部由 SDK 承担。region 配置化（缺省 `us-east-1`）两 client 一致。
- **同步封装**：`BaseS3Client` 方法返回 `CompletableFuture`，网关内 `.get()` 同步化即可（内部 okhttp 异步执行）。
- **已知缺陷（实测确认）**：9.0.3 的 `ListPartsArgs$Builder` 只继承 `BucketArgs$Builder`、无 `.object()`，且 `ListPartsArgs` 无带参构造器——`listParts` 公开 API **无法构造可用参数**。规避：ETag 改由前端从 PUT 响应头回传（实测：篡改 ETag 的 Complete 被 S3 以 InvalidPart 拒绝，无完整性风险，最终由 commit 整对象 SHA-256 兜底）；断点续传差集改由前端本地记录（见「断点续传与并发」）；SDK 后续版本修复后可切回服务端 ListParts。
- **composeObject 坑（升级实测发现）**：SDK 会原地修改 `sources` 列表（内部 `List.set` 回填 offset/length），必须传可变列表——与现有 `ChunkMergeService` 的 ArrayList 惯用法一致。
- Complete 的「200 但 body 内嵌 `<Error>`」坑由 SDK 解析层兜底（9.0.2 起 `completeMultipartUpload` 自带重试）。

## 目标 Key 与两种模式的数据面差异

| | single（≤5MB） | multipart（>5MB） |
|---|---|---|
| 目标 key | `uploads/pending/{userId}/{sessionId}/{shortId}_{name}` | MPU 目标同上（未 Complete 前不可见，Complete 后为 pending 可见对象）。key 含 sessionId 段：cleaner 判定 O(1)（提取 sessionId → EXISTS 会话键），并消除同名歧义 |
| 校验通过后 | commit 校验通过后服务端 `copyObject` 到最终 key `documents/{userId}/{shortId}_{name}` + 删 pending | 同左：Complete 落 pending → 校验 → copy 到最终 key + 删 pending |
| 孤儿形态 | pending 目录下的可见对象（cleaner 可扫） | 未 Complete 为隐藏分片存储（cleaner 经 listMultipartUploads 主动 abort——ILM 本镜像实测不可用）；Complete 后未 commit 同为 pending 可见对象（cleaner 可扫） |
| 分片约束 | 无 | 除末片外每片 ≥5MB。注：现行 `DefaultChunkSizeStrategy` 为 5/10/20MB 动态分片，固定 5MB 是直传路径的**新约定**（非复用现有策略），50MB 上限最多 10 片 |

两种模式统一经 pending 中转：`documents/` 下只出现**通过 commit 校验**的对象，保持「有对象必有 DB 记录」不变式，孤儿清理简单；同时消除「multipart 先 Complete 到最终 key、校验失败再删」的崩溃窗口——校验失败只污染 pending（cleaner 24h 兜底），最终 key 永不出现未校验内容。copy 成功但 persist 失败时回滚删除最终对象（对齐现有 `PersonalUploadStrategy` 的 R1-H3 回滚语义）。

key 构建显式复用现有设施：文件名经 `StorageKeys.sanitizeFilename`（其 javadoc 已注明威胁模型：保留 `..`、keyspace 扁平），`uploads/pending/` 前缀登记进 `UploadObjectKeys`（单一数据源、禁止硬编码），不另写一套编码实现。

## Redis 会话

复用 `ChunkSessionStore` 的模式，新前缀避免与旧分片会话冲突：

| Key 模式 | 类型 | TTL | 用途 |
|----------|------|-----|------|
| `direct:session:{sessionId}` | Hash | 24h | status（ACTIVE/COMMITTING/COMMITTED/ABORTED，COMMITTING 另带短租约子键）/documentId（COMMITTED 前写入，幂等回查用）/mode/fileChecksum/fileName/fileSize/mime/bucket/objectKey/uploadId/chunkSize/totalChunks/userId/teamId/replaceDocumentId/createdAt |
| `direct:file:{userId}:{fileChecksum}` | String | 24h | 反向索引：续传查找既有会话（对齐现有 `upload:file:` 语义） |
| `rate:upload:direct-init:{userId}` | String | 60s | direct init **独立限流桶** 30 次/分/用户（现有 `rate:upload:init:` 10 次/分面向低频大文件 chunk 会话；direct 化后每文件一次 init，前端批量 10 文件单批即打满旧配额必然 429，故独立配置；前端批量 init 遇 429 指数退避） |

commit（含幂等回查返回）与 abort 成功后同时删除 `direct:file:{userId}:{fileChecksum}` 反向索引，避免 TTL 窗口内续传/status 指向已终结会话。

**会话 TTL 固定 24h，不随访问续期**（status/part-urls 读取不刷新 TTL）——这是与 cleaner 两个 24h 阈值（pending 可见对象、MPU 发起时间）三者的对齐前提；若未来引入按活跃度续期，必须同步改为「会话过期时间 vs MPU 发起时间」的相对判定，否则活跃会话的 MPU 会被 cleaner 误 abort（用户吃 `UPLOAD_GONE` 重新 init，有兜底但违背「活跃会话不动」预期）。

## 安全校验后移

presigned URL 无法强制 `Content-Length`（浏览器禁改该头），所有「事实校验」移到 commit：

| 校验项 | init（声明预检） | commit（服务端复核） |
|--------|----------------|---------------------|
| 扩展名/MIME 白名单 | ✅ `DocumentValidator` 预检 | ✅ Tika 魔数探测（对最终对象流式读头部，复用 `DocumentMimePolicy` 归一化） |
| 文件大小 ≤50MB | ✅ 声明值 | ✅ single/multipart 统一 statObject 总尺寸复核（超限复用 `UPLOAD_FILE_TOO_LARGE` 104003） |
| 文件校验和 | —（信任声明） | ✅ 整对象流式 SHA-256 对拍声明值，不符则删对象 + 拒绝 |
| 分片完整性 | — | ✅ Complete 侧 S3 ETag 校验（ETag 前端回传，伪造即 InvalidPart 失败，实测验证）+ 整对象 SHA-256 兜底；序号连续/非末片 ≥5MB 由前端切片规则保证，声明尺寸对拍复核 |
| 团队权限/额度 | ✅ `TeamAccessGate` + quota | ✅ commit 复核（防止 init 后额度被并发占满） |
| per-user 限流 | ✅ 30 次/分（direct 独立桶，见「Redis 会话」） | — |

- **per-part 校验和取消**：S3 presign 无法附带 SHA-256 条件；完整性由「Complete 侧 S3 ETag 校验 + commit 整对象 SHA-256 复核」兜底（ETag 前端回传，伪造即 Complete 失败），现有逐片 `X-Chunk-Checksum` 头在新路径不再需要。
- **presign 有效期 10 分钟**：过期重走 part-urls 签发，无状态成本；URL 即短期 bearer 能力，key 含随机 shortId，泄露影响面可控。
- **single 模式重放竞态与 ETag 条件闭环（TOCTOU）**：presigned PUT URL 在有效期内**可重复使用**（S3 presign 无一次性约束，同 URL 覆盖同 key）。若 commit 的「校验读」与 `copyObject` 之间对象被覆盖（两次独立读取），未校验内容将进入 `documents/`。闭环链条：① `statObject` 取 ETag + size；② 流式读校验时同时计算 SHA-256 与 MD5，末尾以 **MD5 对拍 ① 的 ETag——仅限 single 模式**（单次 PUT 的对象 ETag == 内容 MD5）；③ `copyObject` 携带 `x-amz-copy-source-if-match: {ETag}`——两种模式统一传 ① 的 ETag（if-match 匹配对象当前 ETag，与其计算方式无关）。任一环节失配 → 删除 pending + 拒绝。
  **multipart 模式不做 MD5 对拍**：S3/MinIO 语义下 MPU Complete 后的对象 ETag 为 `MD5(各分片 ETag 拼接)-N` 形式，**不等于**整对象 MD5——若照搬 ② 会把所有 >5MB 直传误判失配全量拒绝。multipart 亦无需对拍：Complete 原子定稿后 uploadId 消亡、PUT 入口关闭，且 key 含 sessionId 无其他写入方，不存在重放窗口。
  ③ 的 SDK 入口：`CopyObjectArgs.builder().headers(Map.of("x-amz-copy-source-if-match", etag, "Content-Type", normalizedMime))`——`headers()` 公开声明于父类 `ObjectWriteArgs$Builder`（继承可用；祖类 `BaseArgs$Builder.extraHeaders(Map)` 为等效备选），勿因 `CopyObjectArgs$Builder` 本类无此方法而误判缺失。copy 同时以 Tika 归一化结果覆盖对象 `Content-Type` 元数据（防浏览器伪造值随 copy 保留）。
- **数据面滥用面与兜底**：presigned PUT 无法约束体积（`Content-Length` 为浏览器禁改头，presign 也无法附带长度条件），直暴露 9000 的部署形态下单会话理论可写入最大 5GB（分片 part 同理）。兜底链条：init 限流 30 次/分 + key 固定（同 key 覆盖，存储上限 = 每会话一对象）+ commit 后验删除 + cleaner/主动 abort；生产 external-endpoint 应统一经设置 `client_max_body_size 60m` 的反代封堵入口体积（见「部署与网络」）。曾评估 POST Policy（`content-length-range` 可在数据面强约束 single 模式体积，SDK `getPresignedPostFormData` 现成可用），因需将 single 模式改为 FormData 数据面且不适用于 multipart，维持 presigned PUT + 网关限额方案。
- **commit 读放大**：Tika 头部探测 + 整对象 SHA-256 复核需后端完整读一遍对象（≤50MB/文件，MinIO → 后端内网流量）。相对旧路径（上传双跳 = 两份全量流量）后端流量减半而非归零，换取数据面零代理；批量 commit 时为 N × 50MB 的内网读，属可接受成本。
- **presigned URL 日志卫生**：日志与异常上报中的 presigned URL 须打码 `X-Amz-Signature`/`X-Amz-Credential` 查询参数（URL 在 10 分钟有效期内可重放覆盖 pending key）；nginx 对 files 子域的 access log 建议关闭 query string 记录或做等价脱敏。

## commit 落库与 ETL

复用现有上传策略的落库语义（`persistDocument` + `DocumentCreatedEvent` + `dispatchAsync`）。
实施时从 `PersonalUploadStrategy` / `TeamUploadStrategy` 抽取共享 persist 组件（两处现存重复的 insert/事件/dedup 登记逻辑收敛为一点），直传 commit 与代理上传两条路径共用：

- 个人空间：persist（DB 状态初值 UPLOADED、响应用 PROCESSING，对齐现状 `PersonalUploadStrategy`；共享组件抽取以现状为准）→ dedup 登记 → 事件 → `dispatchAsync`（outbox 异步，同个人批量的改造后语义）
- 团队空间：对齐 `TeamUploadStrategy` —— auto-approve 直接 dispatch；普通成员 PENDING_APPROVAL + 审批记录，**不**触发 ETL
- `replaceDocumentId` 走 `DocumentCreatedEvent` 现有 supersede 流程，直传路径天然支持（现批量端点不支持 replace 的缺口顺带补齐）；边界定义：**秒传命中的既有文档 == replace 目标自身**（同 checksum）时直接幂等返回成功、不触发 supersede（现有代理路径同样存在该边界，instant 作为一等响应模式放大了此路径，故显式定义）
- **commit 幂等与并发**：会话 `status` 状态机 ACTIVE → COMMITTING → COMMITTED（或回退 ACTIVE / ABORTED），吸收现有 `ChunkUploadServiceImpl.deferWhileMerging` 的 `__merging` 租约模式：
  - CAS 抢占 ACTIVE → COMMITTING 时写入**短租约**（如 60s TTL 子键）。抢占失败者按对端状态区分：**COMMITTED** → 读会话 `documentId` 确定性幂等返回原 `DocumentUploadResponse`；**COMMITTING 且租约未过期** → 返回冲突让前端短暂等待重试（**绝不按已提交假成功**）；**COMMITTING 且租约已过期** → 视为进程崩溃残留，**接管起点三分支判定**（multipart，先 `statObject` 探测 pending 对象）：**(a) pending 存在** → Complete 已发生（uploadId 消亡是预期而非异常），从「复核 + copy + persist」续走，**不得从 Complete 重试**；(b) **pending 缺失且 uploadId 仍存活**（崩溃发生在 Complete 之前）→ **从 Complete 重试**（commit 请求自带 parts 列表，安全幂等）后走 (a) 路径；(c) **pending 缺失且 uploadId 已死** → 才是真正 `UPLOAD_GONE`。single 模式简化为两分支：pending 存在 → 复核续走；pending 缺失 → 回退 ACTIVE 让前端重传
  - 成功路径**先写 `documentId` 进会话、再翻转 COMMITTED**（任何时序下幂等回查都有确定结果）；中途失败回退 ACTIVE 允许重试；崩溃滞留 COMMITTING 由租约超时自愈，杜绝「回查扑空的假成功」
  - Complete/UploadPart 遇 `NoSuchUpload`（cleaner 主动 abort、并发 commit 已 Complete、uploadId 消亡）→ **不走已提交回查**，映射 `DIRECT_UPLOAD_UPLOAD_GONE`（204016）引导前端重新 init（COMMITTING 接管路径例外，见上）

## 批量语义与个人/团队链路统一

- **「批量」在 direct 模式下消解**：每文件一次独立的 init → 直传 → commit 会话，前端批量上传即 N 个会话并发（受 init 限流 30 次/分约束，超限 429 退避）。个人/团队共用同一条链路（同一套会话存储/presign/commit 服务），`teamId` 仅是会话字段，commit 按其走个人/团队落库语义分支；HTTP 层保留双端点（个人 `/api/documents/direct-uploads`、团队 `/api/teams/{teamId}/documents/direct-uploads`）是对齐现有 chunk 路由与权限模型的惯例（teamId 入路径便于权限中间件与会话 owner 校验），**非链路分裂**。现状代理路径则是「入口统一（`/api/documents/upload/batch` + teamId 参数）、实现分裂（`PersonalUploadStrategy`/`TeamUploadStrategy` 各持一份 persist/事件/dedup 逻辑）」——本方案经共享 persist 组件收敛后，两条空间路径在服务层合为一点。
- **配额校验粒度变化**：现状团队表单批量为整批一次 `verifyUploadQuota(totalSize)` 预检；direct 模式改为**逐文件 init 预检 + commit 复核**——更严格，且不存在「整批部分文件占了配额、其余失败回滚」的中间态；「init 后额度被并发占满」的竞态已由 commit 复核覆盖。代价：极端并发下同批文件可能部分成功、部分被拒，各文件独立重试即可。
- **前端按文件粒度呈现批量结果**：每文件独立成功/失败/审批状态。个人批量在逐文件异步 ETL 改造后已是该语义，团队侧对齐即可（团队普通成员每文件独立 PENDING_APPROVAL 审批记录，现状 `TeamUploadStrategy.uploadBatch` 亦为逐文件建审批，语义无缝）。

## 断点续传与并发

- **续传（前端差集模式）**：SDK 9.0.3 的 `listParts` 因构建器缺陷不可用（见「SDK 承载」），服务端权威差集能力移除；改为**前端本地记录已传分片**（PUT 成功即记 `{number, etag, size}`，localStorage 键 **`direct-upload:parts:{userId}:{sessionId}`**——必须含 sessionId 维度，防同用户同 checksum 文件间 ETag 串档导致 commit InvalidPart），重入时 GET status 返回会话元数据（uploadId/chunkSize/totalChunks 仍有效），前端对本地未记录的分片重新签发上传。换设备/清缓存后退化为全量重传（同 key 同 partNumber 覆盖，无正确性问题，仅体验损失）；SDK 修复 `listParts` 后可切回服务端差集。
- **生命周期边界（防死循环）**：status/commit 遇 `NoSuchUpload`（cleaner 主动 abort 与会话 TTL 的同窗竞态、上游已消亡）一律返回 `DIRECT_UPLOAD_UPLOAD_GONE`（204016）引导重新 init，**绝不返回"全部缺失"**——否则前端会对死 uploadId 逐片 PUT 吃 404 死循环；abort 对已消亡 uploadId 幂等（实测：SDK 重复 abort 无异常）；pending 对象被 cleaner 清除后会话仍可能存活，commit 时 statObject 不存在同样映射会话失效；MPU 回收阈值（24h）与会话 TTL 同刻到期，跨天续传必然触发重新 init，前端需将该错误作为正常路径处理。
- **并发**：part-urls 支持批量签发（避免 N 次往返）；前端以 `concurrentChunks: 4`（常量已定义未用）并发 PUT 分片，单片失败单独重试；**PUT 响应的 ETag 必须随分片记录留存**，commit 时回传 Part 列表。
- 取消：abort → 未 Complete 的会话走 AbortMultipartUpload（幂等）；已有 pending 可见对象（single PUT 或已 Complete 未 commit）则删除该对象；最后统一会话清理 + 反向索引清理。**接受与迟到 PUT 的竞态窗口**：abort 删对象后迟到的 single PUT 会重建同 key 孤儿对象，由 cleaner（无会话 + 24h）兜底回收，不做额外防护。

## 孤儿清理

三层防线：

1. **Redis 会话 TTL 24h**：会话自动过期；
2. **`DirectUploadOrphanCleaner` 定时任务**（对齐现有 `OrphanChunkCleaner` 模式，6h 间隔），两条扫描线：
   - **可见对象**：扫 `uploads/pending/` 下**无活跃会话且创建超 24h** 的对象删除——key 含 sessionId 段（`uploads/pending/{userId}/{sessionId}/{name}`），判定 O(1)：从 key 提取 sessionId → `EXISTS direct:session:{sessionId}`；
   - **未 Complete MPU（隐藏分片，LIST 不可见）**：`listMultipartUploads`（SDK 公开 API，已实测可用）按 `uploads/pending/` 前缀过滤，对发起超 24h 的 uploadId 主动 `abortMultipartUpload`（幂等）——这是**进程内兜底，不依赖 ILM**；
3. **MinIO ILM 兜底：实测不可用，降级为可选**。本镜像（pgsty/minio:latest）实测：mc（RELEASE.2026-04）的 `ilm rule add` **无任何 abort-incomplete flag**；以 SigV4 原始 `PutBucketLifecycle` API 提交 `AbortIncompleteMultipartUpload` XML 规则被 schema 拒绝（400，Filter/Prefix 写法均拒；另见 minio/minio#19115 部分版本规则不持久化）。故 ILM 不计入兜底链；未来镜像支持时可作为第三层锦上添花，配置后须回验持久化。

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

### MinIO CORS（直传必需）——`MINIO_API_CORS_ALLOW_ORIGIN` 环境变量（实测定案）

**实测（2026-08，pgsty/minio:latest）**：bucket 级 CORS 不可用——SigV4 签名的 `PUT /{bucket}?cors` 返回 `501 NotImplemented`（PutBucketCors 为 AIStor 付费能力，社区版未实现，`pgsty/minio` fork 遵循"不加新特性"原则同样没有；`mc cors set` 对应报 `decoding xml: EOF`）。初版设计的 `scripts/minio-cors.sh` + `minio-init`（`minio/mc`）方案**不适用**（脚本未进入分支，且该 API 在本镜像不可用），改用服务级全局环境变量：

```yaml
# docker-compose.yml → minio 服务 environment 新增
MINIO_API_CORS_ALLOW_ORIGIN: ${MINIO_API_CORS_ALLOW_ORIGIN:-*}
# dev 默认 * 直传开箱即用；生产 **fail-closed 由部署层承担**：prod compose 变体不提供 `:-*`
# 缺省（白名单缺失 = MinIO 容器起不来或部署清单检查失败）。注意该变量是 MinIO 进程的
# 环境变量，后端应用启动期无法感知其取值——应用侧可校验的仅是自身 external-endpoint
# 与部署清单中白名单的一致性，不承诺校验 MinIO 侧配置；
# 白名单与后端 CORS 清单同源维护（.env.example 增加该变量并注释说明）
```

实测证据（本地 pgsty/minio:latest 容器验证）：

- 默认（未设 env）：预检 `OPTIONS` 204，`Access-Control-Allow-Origin` 回显请求 Origin，`Allow-Methods`/`Allow-Headers` 按预检请求反射（PUT/GET/HEAD 均放行）——浏览器直传开箱即用；
- 设白名单 `http://localhost:5173,https://app.example.com` 后：清单内 origin 正常回显，清单外 origin 响应**无** `Access-Control-Allow-Origin` 头（浏览器阻断）——收紧生效；
- 手写 SigV4 presigned PUT（UNSIGNED-PAYLOAD、`SignedHeaders=host`、10 分钟有效期）带 `Origin` 请求：200 + ACAO 回显；篡改签名 403——预签链路端到端可用，签名被真实校验；
- `Access-Control-Expose-Headers`（含 `ETag`、`X-Amz*` 等）由 MinIO 全局返回，无需单独配置。

限制与备注：origin 清单为**服务级全局**（不分 bucket/路径），方法/请求头按预检反射、无需枚举；未观察到 `Access-Control-Max-Age`，预检不做浏览器缓存，每请求多一次 OPTIONS 往返（内网延迟可忽略，需要优化时由 nginx 反代形态补充）。若部署要求按路径区分 CORS 或隐藏 MinIO 原始响应头，退回 nginx 反代注入 CORS 头（该形态下 nginx 从「可选」升为必选），Host 透传与 `client_max_body_size` 要求不变。

### nginx 可选反代（不暴露 9000 的部署形态）

子域 `files.example.com` → `proxy_pass http://minio:9000`，**必须 `proxy_set_header Host $host`**（签名含 Host，透传浏览器所见主机名才匹配）；`client_max_body_size 60m` 为**必设**项——presigned PUT 无法自带体积约束，入口限额是数据面滥用的唯一前置封堵；取 60m 而非 50m 是为业务 50MB 上限留 HTTP 头/编码余量（对齐现有 `/api` 侧 60m 先例，业务恰好 50MB 的合法文件在 50m 限额下必 413）。「直接暴露 9000」形态无入口体积上限，仅适用于 dev/可信内网。dev（vite proxy）无需任何改动——直传不经过 `/api`。

### 实施前置：镜像 pin 与部署承载物

- `pgsty/minio:latest` 浮动 tag 需 **pin 到具体 RELEASE tag/digest**——本方案的行为前提（bucket CORS 501、`MINIO_API_CORS_ALLOW_ORIGIN` 语义、ILM abort 规则不可配置）均与镜像版本强绑定，升级需随 release note 人工评估；
- 补充 **prod compose 变体**（CORS 白名单注入、external-endpoint、files 反代形态），当前仓库仅有 dev compose，部署假设尚无承载物；
- **多实例 external-endpoint 一致性检查**入部署清单：各实例配置不一致无正确性问题（签名各自有效）但同会话分片 URL 指向不同 host，体验下降；
- 上游 minio/minio 已归档，升级渠道仅 `pgsty/minio` fork 的 CVE 修复版。

## 可观测性

数据面搬到 MinIO 后，后端失去对上传字节流的天然可见性，需补齐控制面指标与前端上报（落地前完成指标清单接入 Micrometer）：

- **控制面指标**：init/commit 成功率与耗时、instant 命中占比、single/multipart 模式分布、part-urls 签发量与 presign 过期重签率、commit 失败按错误码分布（`UPLOAD_GONE`/`CHECKSUM_MISMATCH`/`PARTS_INCOMPLETE` 等）、commit 状态机异常转移次数（COMMITTING 租约接管）、cleaner 清理对象数、**MPU 泄漏扫描数与主动 abort 数**（ILM 不可用后的唯一 MPU 回收通道，需告警）、commit 读放大（Tika + SHA-256 全量读）耗时；
- **MinIO 侧**：4xx/5xx 比例（`NoSuchUpload`/`InvalidPart` 异常率是 cleaner abort 竞态与前端切片错误的信号）；
- **前端上报**：PUT 失败率/重试次数、降级代理路径触发率（阶段 2 灰度的关键监控指标）。

## 前端改造（`upload-button.tsx` + `api/documents.ts`）

```
1. computeChecksum(file)                          // 现有流式 SHA-256
2. directUploadInit({...}) → instant 直接完成
3. single:  XHR PUT uploadUrl（onuploadprogress 字节级进度）→ commit
   multipart: 循环 { 本地差集 → part-urls 批量签发 → 4 并发 XHR PUT }
              → 每片 PUT 成功记录 {number, etag, size}（localStorage 持久化，续传与 commit 依赖）
              → 全部到位 → commit（回传 parts 列表；UPLOAD_GONE → 重新 init）
4. SSE 照旧驱动文档状态（/documents/events 不变）
```

- 进度改用 XHR（fetch 无上传进度事件）；single 模式获得真正的字节级进度（现批量端点只有 0→100 跳变，顺带修复）；
- 上传中断重入：init 秒传判定之外，`direct:file:` 反向索引命中既有未完成会话 → status 取会话元数据 + 本地已传分片记录做差集续传；`UPLOAD_GONE` / 会话失效则全新 init。

## 迁移路径（三阶段）

| 阶段 | 开关 | 说明 |
|------|------|------|
| 1 并存 | `app.upload.direct.enabled=false`（默认） | 新端点上线，前端 flag 关闭，代理路径不动；内部灰度验证 |
| 2 默认直传 | flag=true | 前端直传为主（**全局开关，无 per-user 灰度**）；直传网络失败（CORS 预检失败/断网/413）时自动降级现有代理路径 |
| 3 退役 | 移除 flag | 下线 `/multipart` 代理端点与 `ChunkUploadServiceImpl` 数据面（`ChunkSessionStore`/Lua/`OrphanChunkCleaner` 一并退役）；`composeObject` 相关代码随 `document-module-extraction` 的收敛计划处置 |

## 与现有设计文档的关系

| 文档 | 关系 |
|------|------|
| `chunk-upload.md` | 本方案实施后其数据面（putObject+composeObject 代理分片）被取代；阶段 3 前仍为现行实现 |
| `document-original-file-preview-download.md` | 「不向前端返回对象存储地址」约束**仅在下载侧维持**；上传侧由本方案修订（presigned URL 短期受限能力，非持久地址） |
| `document-module-extraction.md` | persist 逻辑抽取（三处 insert 收敛）与本方案 commit 复用点一致，可同批实施 |

## 错误码（新增）

新增错误码归入 B 类服务端错误的 204 段（RAG，`ServiceErrorCode`），与现有上传会话域错误 `UPLOAD_SESSION_NOT_FOUND`(204005) 同段集中：

| 错误码 | 枚举值 | 说明 |
|--------|--------|------|
| 204010 | `DIRECT_UPLOAD_MODE_INVALID` | 会话模式与请求不匹配（single 请求 part-urls 等） |
| 204011 | `DIRECT_UPLOAD_PARTS_INCOMPLETE` | commit 时分片列表不连续/缺失 |
| 204012 | `DIRECT_UPLOAD_SIZE_MISMATCH` | 实际尺寸与声明不符（含分片尺寸违规；业务上限内超 50MB 复用 104003） |
| 204013 | `DIRECT_UPLOAD_CHECKSUM_MISMATCH` | 整对象 SHA-256 复核失败 |
| 204014 | `DIRECT_UPLOAD_COMPLETE_FAILED` | CompleteMultipartUpload 失败（ETag 校验不符等，不含 copy 阶段） |
| 204015 | `DIRECT_UPLOAD_COPY_FAILED` | commit 校验通过后的 copyObject（pending → 最终 key）失败 |
| 204016 | `DIRECT_UPLOAD_UPLOAD_GONE` | uploadId 已消亡（cleaner 主动 abort/并发已 Complete/不存在）——引导重新 init，防续传死循环 |

复用现有枚举（注意实际值）：`UPLOAD_SESSION_NOT_FOUND`（**204005**，B 类）、`UPLOAD_FILE_TOO_LARGE`（104003，A 类）、`UPLOAD_MIME_UNSUPPORTED`（104004，A 类）、`RATE_LIMITED`（100005，A 类）。

## 测试策略

| 层 | 内容 |
|----|------|
| `S3MultipartGatewayIT` | Testcontainers 起真实 MinIO：SDK 公开 API 全流程 Create → presign UploadPart PUT → Complete → Abort；**篡改 ETag 的 Complete 必被拒**；abort 重复调用幂等；presign 过期后 PUT 403；Complete 内嵌 Error 解析（SDK 层）；**S1 回归：presigned URL 重放覆盖 pending 后，条件 copy（if-match）必 412 拒绝**；**N1 回归：multipart 对象（ETag 为 `-N` 形式）不做 MD5 对拍、commit 正常通过** |
| `DirectUploadServiceTest` | init 分支矩阵（instant/single/multipart/限流/超限/团队额度/replace 自命中）、commit 失败矩阵（ETag 不符/尺寸/MIME/校验和/`UPLOAD_GONE`）、团队审批分支、commit 状态机（COMMITTING 租约过期接管、**H1 回归：Complete 后崩溃 → 接管者按三分支判定（pending 存在续复核 / Complete 前崩溃从 Complete 幂等重试 / 双亡才 UPLOAD_GONE）**、COMMITTED 幂等回查 documentId、并发双 commit 冲突语义） |
| `DirectUploadOrphanCleanerTest` | pending 无会话超 24h 删除（sessionId 段 O(1) 判定）、活跃会话不删、Complete 后未 commit 残留清理、listMultipartUploads 前缀过滤 + 超 24h 主动 abort、abort 迟到 PUT 重建对象回收 |
| 部署 e2e（**暂缓，上线后补**） | CORS/nginx 反代形态（白名单 origin 放行/越界拒绝）、多实例 external-endpoint 不一致时各签 URL 各自有效 |
| 前端 vitest | `api/documents.ts` 直传封装、upload-button 流程（mock XHR，含 4 并发、ETag 记录留存、`UPLOAD_GONE` 重入、localStorage 键含 sessionId、本地差集续传、批量 init 429 退避） |

## 已定决策（原开放问题，评审后关闭）

1. **presign 签发频率**：part-urls 批量签发**单批 ≤20 片**（已写入 API 校验）。现规格 50MB/5MB 最多 10 片，单批即可签全；上限为未来规格提升预留，届时前端分批拉取 + 4 并发 PUT；
2. **多实例 external-endpoint 一致性**：多实例部署必须保证 `external-endpoint` 配置一致（不一致时同会话分片 URL 指向不同 host——签名各自有效，无正确性问题，仅体验下降），已列入「实施前置」部署检查清单；
3. **聊天附件场景**：**TODO（暂不设计）**——当前无附件上传；未来接入时评估复用 direct-uploads 会话（teamId=null 个人会话天然预留此能力），当前不预建任何设施。

## 实施记录（阶段 1，2026-08-25）

阶段 1（并存）已落地：后端控制面全量上线 + 前端灰度接入（`app.upload.direct.enabled` 默认 false，前端经 `GET /api/documents/direct-uploads/config` 拉取开关，关闭时行为与改造前完全一致）。验证：后端 `mvn test` 1866 全绿（含 `S3MultipartGatewayIT` 7/7、`DirectUploadServiceTest` 28/28、`DirectUploadOrphanCleanerTest` 4/4），前端 vitest 129 全绿 + tsc 通过。

### 实测推翻的设计前提（Testcontainers pgsty/minio + SDK 9.0.3 端到端验证）

1. **SDK `copyObject` 不透传条件头**：`headers(Map)` / `extraHeaders(Map)`（含 `x-amz-copy-source-if-match`）在 copyObject 上被静默丢弃——stale ETag 的条件复制照样成功；而 MinIO 服务端本身完整执行条件 copy（手工签名直测：stale ETag → 412，当前 ETag → 200）。
   **落地方式**：条件 copy 改为「SDK presign 内部 PUT URL（60s 有效期，签名仍由 SDK 承担）+ okhttp 携带未签名的 `x-amz-copy-source-if-match` / `x-amz-metadata-directive: REPLACE` / `Content-Type` 头」。TOCTOU 闭环保持成立（IT 的 S1 回归实测 412 拒绝 + N1 multipart ETag 放行）。
2. **ListMultipartUploads 双重不可用**（本文「SDK 承载」表中「已实测可用」的断言被推翻）：
   ① 镜像端 prefix 参数损坏——带 prefix 一律返回空清单（手工 SigV4 直测，无 prefix 正常返回 Upload 元素）；
   ② SDK 端 XML 解析要求 `StorageClass` 非空（simpleframework `required=true`），而 MinIO 返回空元素 → `XmlParserException`。
   **落地方式**：MPU 泄漏回收改由 **Redis 出生登记簿**驱动（`direct:mpu` ZSET，member=JSON{bucket,objectKey,uploadId}，score=发起时刻）：createMultipartUpload 登记、complete/abort 注销、cleaner 按 24h 阈值取超龄项主动 abort（幂等已实测）。登记簿整体 TTL 48h > 24h 阈值 + 6h 扫描间隔，条目不会先于 cleaner 处理而丢失。比 API 扫描更精确且不依赖镜像行为。
3. **status 端点不做 MPU 存活性探测**：无可用 API（listParts 构建器缺陷 + 上述 ListMultipartUploads 不可用）。commit 是权威 `UPLOAD_GONE` 判定点（Complete/statObject 的 NoSuchUpload/NoSuchKey）；分片 PUT 遇 404 由前端按网络层错误处理（降级/重新 init），死循环风险由「绝不返回全部缺失」语义继续覆盖。

### 其他实施级决策（与设计的等价简化）

- init single 响应以平铺 `contentType` 字段替代示例中的 `headers: {Content-Type}` map（信息等价，前端消费更直接）；
- commit 幂等回查所需的响应状态存为会话字段 `resultStatus`（markCommitted 与 documentId 原子同写）；
- 团队额度/审批能力经 `TeamAccessGate` 端口扩展（`verifyUploadQuota` / `createUploadApproval`，team 侧 `TeamAccessGateAdapter` 实现），rag 模块不反向依赖 team 的实现保持不变；
- 共享 persist 组件为 `UploadDocumentPersistence`（insert/registerDedup/publishCreated/dispatchEtl 四个入口），PersonalUploadStrategy 与 TeamUploadStrategy 已等价重构委托（既有 R1-H3 原子性测试全绿守护）；
- commit 状态机 Lua：`resources/scripts/direct_commit_acquire.lua`（五分支 CAS + 60s 租约，H1 三分支接管在 `DirectUploadServiceImpl#completeMultipart`）。
