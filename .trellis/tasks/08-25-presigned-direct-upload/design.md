# Design：Presigned 直传落地（实施级决策）

权威技术方案：`docs/design/presigned-direct-upload.md`（本文件只记录实施层决策与差异，冲突以设计文档为准）。

## SDK API 事实（javap 核实 minio-9.0.3.jar）

- `BaseS3Client`（`MinioAsyncClient` 继承）公开：`createMultipartUpload` / `uploadPart` / `completeMultipartUpload` / `abortMultipartUpload` / `listMultipartUploads`，返回 `CompletableFuture`。
- presign：`MinioClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs)`；`extraQueryParams(Map<String,String>)` 继承自 `BaseArgs$Builder`（可传 partNumber/uploadId）；`method(Http.Method.PUT)`、`expiry(int, TimeUnit)`。
- `CompleteMultipartUploadArgs.Builder.uploadId(String).parts(Part[])`；`new Part(int partNumber, String etag)`。
- `ListMultipartUploadsArgs.Builder.prefix(String)` 完整可用；`ListMultipartUploadsResult.uploads()` → `ListMultipartUploadsResult$Upload{objectName(), uploadId(), initiated()}`。
- `CopyObjectArgs.Builder.headers(Map)` 继承自 `ObjectWriteArgs$Builder`（`x-amz-copy-source-if-match` 走这里，同时覆盖 `Content-Type`）。
- `StatObjectResponse.etag()/size()/contentType()` 继承自 `HeadObjectResponse`。
- 已知缺陷（设计文档记录）：`ListPartsArgs` 构建器无 `.object()`，服务端 listParts 不可用 → ETag 由前端回传。

## 类布局（全部新增，除注明外）

```
com.smart.rag.rag.upload
├── s3/S3MultipartGateway              @Component：MPU 原语 + presign + stat/copy/remove（同步封装 .get()）
├── DirectUploadProperties             @ConfigurationProperties("app.upload.direct")：enabled=false 默认
├── DirectUploadRedisConstants         direct:session: / direct:file: / rate:upload:direct-init: + TTL 常量
├── DirectUploadSessionStore           非 bean（对齐 ChunkSessionStore 手动组装惯例），Hash 存储状态机
├── DirectUploadService / Impl         控制面核心：init/partUrls/status/commit/abort
├── DirectUploadController             /api/documents/direct-uploads + GET config（flag 下发）
├── DirectUploadOrphanCleaner          @Scheduled 6h：pending 对象 + 泄漏 MPU 双扫描线
├── dto/DirectUploadInitRequest|Result / PartUrlsRequest|Result / StatusResponse / CommitRequest|Part
└── UploadDocumentPersistence          共享 persist 组件（自 PersonalUploadStrategy/TeamUploadStrategy 抽取）
com.smart.rag.team.controller.TeamDirectUploadController   团队镜像（对齐 TeamChunkUploadController）
resources/scripts/direct_commit_acquire.lua               commit 状态机 CAS + 租约
src/test/.../S3MultipartGatewayIT                         Testcontainers MinIO 端到端
```

修改的既有文件：`MinioProperties`（+externalEndpoint/region）、`MinioConfig`（+minioAsyncClient / presignMinioClient bean）、`ServiceErrorCode`（+204010-204016）、`UploadObjectKeys`（+PENDING_PREFIX）、`PersonalUploadStrategy`/`TeamUploadStrategy`（persist 收敛到 UploadDocumentPersistence，等价重构）、`ChunkUploadServiceImpl`（过时 javadoc 修正）、`application.yml`（+app.upload.direct 段）、`.env.example` + dev compose（+MINIO_API_CORS_ALLOW_ORIGIN）。

## 关键实施决策

1. **Bean 装配**：现有 `MinioConfig#minioClient` 保持不动（继续 @Primary 供既有注入点）；新增 `minioAsyncClient`（MinioAsyncClient，内部 endpoint，region 一致）与 `presignMinioClient`（MinioClient，external-endpoint 缺省回退 endpoint）两个 bean，均 lazy 可选——由 `S3MultipartGateway` 独占使用。
2. **commit 状态机**：Lua `direct_commit_acquire.lua` 原子完成「读 status → ACTIVE 抢占/COMMITTED 回查/COMMITTING 租约冲突或接管/ABORTED 拒绝」五分支（单实例 Redis，session hash 与 lease 子键同脚本操作）。成功路径先写 documentId 再翻 COMMITTED；中途失败回退 ACTIVE；Complete/UploadPart 遇 NoSuchUpload 映射 UPLOAD_GONE（接管三分支例外，按设计文档）。
3. **共享 persist 组件 `UploadDocumentPersistence`**：单一入口 `persist(PersistCommand)`：insert RagDocument（status 由调用方给定：个人 UPLOADED / 团队 auto-approve PROCESSING、普通成员 PENDING_APPROVAL）→ dedup 登记 → DocumentCreatedEvent → 条件 dispatchAsync。团队审批记录（TeamUploadApproval insert）留在团队语义分支调用侧（个人策略/团队策略/直传团队分支各自调用 persist 后按需补审批记录），保持与现状严格等价。
4. **flag 下发**：`GET /api/documents/direct-uploads/config` → `{enabled:boolean}`（读 DirectUploadProperties）；flag=false 时 init/part-urls/commit 返回 `ClientException(NOT_FOUND 类)`（前端 config 请求失败也按 false 处理）。前端 react-query staleTime=Infinity 拉一次。
5. **single 模式 TOCTOU 闭环**：statObject 取 etag+size → 流式读算 SHA-256+MD5（single 模式 MD5 对拍 etag）→ copyObject 带 `x-amz-copy-source-if-match` + Tika 归一化 Content-Type。multipart 不做 MD5 对拍（ETag 为 `-N` 形式）。
6. **pending key**：`UploadObjectKeys.PENDING_PREFIX = "uploads/pending/"`，key = `uploads/pending/{userId}/{sessionId}/{shortId}_{sanitizedName}`；cleaner 从第三段提取 sessionId 做 O(1) EXISTS。
7. **前端编排抽离**：`frontend/src/lib/direct-upload.ts` 纯逻辑模块（注入 `putFn` 便于测试），`api/documents.ts` 增 direct 系列；upload-button 在 handleFiles 入口按 config 分流，失败降级走原 uploadDirect/uploadViaChunks 路径。
8. **surefire/IT**：沿用仓库现状（无 failsafe），IT 命名 `*IT` 需确认 surefire include 配置；若默认不跑则补 include 或改名 `*IT`→保持与 RedisStreamMessageBusIT 相同处置。

## 测试策略

- `S3MultipartGatewayIT`：GenericContainer 起 MinIO（镜像对齐 dev compose），真 presign PUT/Complete/Abort/篡改 ETag/重放 if-match/过期 403（用 okhttp 直接打 presigned URL；过期场景签 1s 短期 URL + sleep）。
- `DirectUploadServiceTest`：Mockito 单测分支矩阵（设计文档测试策略表）；Lua 状态机经 mocked StringRedisTemplate.execute 返回值驱动。
- 前端：`documents-direct-upload.test.ts`（api 封装）+ `direct-upload.test.ts`（编排：mock putFn，覆盖并发/ETag/localStorage/差集/429/UPLOAD_GONE/降级）。
