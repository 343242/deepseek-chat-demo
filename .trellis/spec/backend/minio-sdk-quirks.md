# MinIO SDK / 镜像实测坑（9.0.3 + pgsty/minio）

> 来源：presigned 直传落地（2026-08-25，任务 08-25-presigned-direct-upload），全部经
> Testcontainers 真实容器端到端验证（`S3MultipartGatewayIT` + 手工 SigV4 直测）。
> 改动 `S3MultipartGateway` / MinIO 交互 / cleaner 回收逻辑前必读——这些坑不会从报错里
> 直观暴露（表现为「静默成功」或「空结果」）。

## 1. copyObject 静默丢弃条件头（TOCTOU 陷阱）

SDK 9.0.3 的 `copyObject` 对 `headers(Map)` / `extraHeaders(Map)` **不透传**：
`x-amz-copy-source-if-match` 等条件头被丢弃，stale ETag 的条件复制照样成功（不抛 412）。
MinIO 服务端本身完整执行条件 copy（手工签名直测：stale → 412，当前 → 200）。

**正确姿势**（见 `S3MultipartGateway#copyObjectIfMatch`）：SDK presign 一个内部 PUT URL
（签名由 SDK 承担）+ okhttp 手工携带未签名的 copy-source 条件头。presigned URL 只在
服务端内存中存在、内网、短有效期，未签名头无暴露面。

**教训**：用 SDK「能编译通过」的 builder 方法 ≠ 头真的发出去；条件语义必须在真实
容器里用「应拒绝的值」反向验证。

## 2. ListMultipartUploads 双重不可用

- **镜像端**：`prefix` 参数损坏——带 prefix 一律返回空清单（无 prefix 正常返回 Upload 元素）；
- **SDK 端**：simpleframework XML 解析要求 `StorageClass` 非空（`required=true`），MinIO
  返回空元素 → `XmlParserException`。

**替代方案**：MPU 生命周期跟踪用 **Redis 出生登记簿**（`direct:mpu` ZSET：create 登记 /
complete+abort 注销 / cleaner 按阈值取超龄项 abort——abort 幂等实测无异常）。
不要尝试「绕过 prefix」或「拦截器改参」（query 参与签名，改参即 SignatureDoesNotMatch）。

## 3. ListParts 构建器缺陷（已知）

`ListPartsArgs$Builder` 只继承 `BucketArgs$Builder`、无 `.object()`，公开 API 无法构造
可用参数。服务端权威分片差集不可用 → 前端本地记录分片（localStorage 键含 sessionId）。

## 4. 其他确认可用的事实

- MPU 原语（create/complete/abort）自 8.5.15 起 public，9.0.3 位于 `BaseS3Client`
  （`MinioAsyncClient` 继承）——`ChunkUploadServiceImpl` 旧注释「SDK 不暴露 MPU API」已过时；
- Complete 的分片 ETag 校验真实执行（篡改 ETag → InvalidPart）；
- abort 对已消亡 uploadId 幂等（无异常）；
- presigned PUT 有效期内可重放覆盖同 key（`UNSIGNED-PAYLOAD`、10 分钟）；过期后 PUT 403；
- `SourceObject.builder().bucket().object()` 是 9.x 构建方式（无 srcBucket/srcObject），
  composeObject 路径可用（`ChunkMergeService` 在用）。

## 验证基建

`S3MultipartGatewayIT`（Testcontainers pgsty/minio）已覆盖上述全部行为，改 MinIO 交互
代码后运行：`mvn test -Dtest=S3MultipartGatewayIT`。
