# PRD：Presigned URL 浏览器直传 MinIO（阶段 1）

上游设计文档：`docs/design/presigned-direct-upload.md`（已评审，本任务为其落地实施）。

## Goal

将上传数据面从「浏览器 → 后端 → MinIO」双跳代理改为「浏览器 → MinIO」直传（后端仅保留控制面）。本任务交付**迁移路径的阶段 1（并存）**：新直传端点全量上线、前端灰度开关默认关闭、现有代理路径行为不变。

## Requirements

### 功能需求

1. **后端直传控制面**（`com.smart.rag.rag.upload`，团队镜像 controller 按 TeamChunkUploadController 惯例）：
   - `POST /api/documents/direct-uploads`（个人）与 `POST /api/teams/{teamId}/documents/direct-uploads`（团队）：init，返回 instant（秒传）/ single（≤5MB presigned PUT）/ multipart（>5MB MPU）三态；
   - `POST .../{sessionId}/part-urls`：批量签发分片 presigned URL，单批 ≤20 片；
   - `GET .../{sessionId}`：status，MPU 消亡返回 `UPLOAD_GONE`(204016)；
   - `POST .../{sessionId}/commit`：Complete（multipart）→ 校验（尺寸/Tika MIME/整对象 SHA-256/single MD5 对拍）→ if-match copyObject → 共享 persist → 事件 → ETL；COMMITTING 状态机含短租约、幂等回查、崩溃接管三分支；
   - `POST .../{sessionId}/abort`：AbortMultipartUpload / 删 pending / 会话清理，204。
2. **S3MultipartGateway**（`rag.upload.s3`）：全部基于 MinIO SDK 9.0.3 公开 API，不手写 SigV4；双 client（内部 endpoint 服务端调用 + external-endpoint presign）。
3. **Redis 会话**：`direct:session:` / `direct:file:` / `rate:upload:direct-init:`（30 次/分独立限流桶），TTL 24h 固定不续期。
4. **共享 persist 组件**：从 PersonalUploadStrategy / TeamUploadStrategy 收敛 insert/事件/dedup 登记为一点，直传 commit 复用；个人/团队语义（auto-approve、PENDING_APPROVAL、审批记录）不回退。
5. **DirectUploadOrphanCleaner**：6h 定时，扫 pending 可见对象（无会话 + >24h 删）与 listMultipartUploads（前缀过滤 + >24h 主动 abort）。
6. **错误码**：ServiceErrorCode 新增 204010–204016（见设计文档错误码表）。
7. **配置**：`spring.minio.external-endpoint` / `spring.minio.region`；`app.upload.direct.enabled=false`（默认）。
8. **前端**：direct upload API 封装 + XHR 直传编排（single 字节级进度 / multipart 4 并发 + ETag 留存 localStorage + 差集续传 + UPLOAD_GONE 重入 + 批量 init 429 指数退避），upload-button 经运行时配置开关接入，**默认关闭走原路径**；直传网络失败自动降级代理路径（阶段 2 行为就绪）。
9. **顺带修正**：`ChunkUploadServiceImpl` 中「SDK 不暴露 MPU API」的过时 javadoc。

### 非目标（明确排除）

- 阶段 2/3：flag 默认开启、代理路径下线（`/multipart` 端点与 ChunkUploadServiceImpl 数据面不动）。
- 部署 e2e（CORS/nginx 反代验证，设计文档标注「上线后补」）。
- 聊天附件场景（设计已定 TODO 不设计）。
- 预览/下载路径不动。

## Acceptance Criteria

- [ ] `mvn test` 全绿（含新增 DirectUploadServiceTest / DirectUploadOrphanCleanerTest；共享 persist 抽取后 PersonalUploadStrategyTest 等既有测试不回退）。
- [ ] `S3MultipartGatewayIT`（Testcontainers 真实 MinIO）覆盖：Create→presign UploadPart PUT→Complete→Abort 全流程；篡改 ETag 必被拒；重复 abort 幂等；presign 过期 PUT 403；S1 回归（重放覆盖后 if-match copy 412）；N1 回归（multipart 对象不做 MD5 对拍、commit 正常）。
- [ ] 前端 `vitest run` + `tsc` 全绿：直传封装与编排测试（mock XHR）覆盖 4 并发、ETag 留存、UPLOAD_GONE 重入、localStorage 键含 sessionId、本地差集续传、429 退避。
- [ ] 后端默认配置（flag=false）下现有上传行为完全不变；前端默认仍走代理路径。
- [ ] 现有代理端点（upload/batch、multipart）无行为改动（除 javadoc 注释与共享 persist 抽取的等价重构）。
