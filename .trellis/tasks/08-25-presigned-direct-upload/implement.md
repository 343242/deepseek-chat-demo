# Implement：执行清单

按里程碑顺序执行；每个里程碑结束跑对应验证命令。GitNexus 规约：修改既有符号前先 `impact({target, direction:"upstream"})`；提交前 `detect_changes()`。

## M1 基础设施：配置 + 错误码 + key 常量
- [ ] `MinioProperties` +externalEndpoint/region（impact 分析）；`MinioConfig` +`minioAsyncClient`/`presignMinioClient` bean
- [ ] `ServiceErrorCode` +204010–204016（impact 分析）
- [ ] `UploadObjectKeys` +`PENDING_PREFIX`（impact 分析）
- [ ] `DirectUploadProperties`（app.upload.direct：enabled=false、presignExpiry=10m、rateLimit=30/min、sessionTtl=24h、leaseTtl=60s、maxPartsPerBatch=20、chunkSize=5MB、multipartThreshold=5MB）
- [ ] `DirectUploadRedisConstants`
- [ ] `application.yml` app 段 + `.env.example`/dev compose `MINIO_API_CORS_ALLOW_ORIGIN`
- 验证：`mvn -q compile`

## M2 S3MultipartGateway + IT
- [ ] `rag/upload/s3/S3MultipartGateway`：createMultipartUpload/presignPutObjectUrl(±part)/completeMultipartUpload/abortMultipartUpload/listMultipartUploads/statObject/copyObject(if-match+contentType)/removeObject；NoSuchUpload→UploadGone 语义异常；presigned URL 日志脱敏工具
- [ ] `S3MultipartGatewayIT`（Testcontainers MinIO）：全流程/篡改 ETag/重复 abort/过期 403/S1 if-match 412/N1 multipart 不对拍
- 验证：`mvn -q test -Dtest=S3MultipartGatewayIT`

## M3 Redis 会话 + 状态机
- [ ] `DirectUploadSessionStore`（字段/常量对齐设计「Redis 会话」表；TTL 24h 固定）
- [ ] `scripts/direct_commit_acquire.lua`：五分支 CAS + 60s 租约
- 验证：随 M5 单测

## M4 共享 persist 抽取（等价重构，风险最高）
- [ ] impact 分析 PersonalUploadStrategy/TeamUploadStrategy
- [ ] 新建 `UploadDocumentPersistence`，两策略改委托；既有测试全绿证明等价
- 验证：`mvn -q test -Dtest='PersonalUploadStrategyTest,TeamUploadStrategy*Test,ChunkUploadServiceImplTest'`

## M5 DirectUploadService：init / part-urls / status
- [ ] DTOs；init 顺序：flag→鉴权→白名单预检→限流→团队 gate/quota→秒传→续传反向索引→建会话→签发
- [ ] part-urls 校验（非空/去重/范围/≤20）；status（UPLOAD_GONE 判定）
- 验证：`mvn -q test -Dtest=DirectUploadServiceTest`（init 分支矩阵）

## M6 commit：校验 + 状态机 + 落库
- [ ] 状态机（Lua 接管/回查/冲突）+ Complete + statObject/Tika/SHA-256/MD5 对拍(single) + if-match copy + 共享 persist + ETL/审批分支 + 回退/回滚
- [ ] H1 接管三分支、COMMITTED 幂等回查、并发冲突（单测矩阵）
- 验证：`mvn -q test -Dtest=DirectUploadServiceTest`

## M7 abort + OrphanCleaner
- [ ] abort（幂等 Abort + 删 pending + 会话/反向索引清理）
- [ ] `DirectUploadOrphanCleaner`（pending 对象 sessionId O(1) 判定 + MPU 前缀扫描）+ 单测
- 验证：`mvn -q test -Dtest='DirectUploadOrphanCleanerTest'`

## M8 Controller 层 + flag 下发
- [ ] `DirectUploadController`（个人 + config 端点）、`TeamDirectUploadController`（团队镜像，validateOwner + teamId 一致性）
- [ ] flag=false 时的端点拒绝行为
- 验证：`mvn -q test`（全量后端）

## M9 前端
- [ ] types/document.ts 增 direct 类型；api/documents.ts 增 direct 系列 + fetchDirectUploadConfig
- [ ] lib/direct-upload.ts 编排（single XHR 进度 / multipart 4 并发 + ETag localStorage + 差集 + 429 退避 + UPLOAD_GONE 重 init + 降级代理）
- [ ] upload-button.tsx 接入（config 分流，默认关闭不变）
- [ ] vitest：api 封装 + 编排测试（mock putFn/XHR）
- 验证：`cd frontend && bun run check || npx tsc --noEmit`；`bunx vitest run`

## M10 收尾
- [ ] 全量 `mvn -q test` + 前端 tsc/vitest；detect_changes() 复核影响面
- [ ] `ChunkUploadServiceImpl` 过时 javadoc 修正；设计文档状态行更新（已实施阶段1）
- [ ] spec update（trellis-update-spec 判断）→ 提交计划（Phase 3.4）

## 回滚点

- M1–M3 纯新增，可整体 revert；
- M4 若等价性存疑，可保留两策略原状、直传独立实现 persist（次选，仅在抽取受阻时）；
- M9 前端独立目录，revert 不影响后端。
