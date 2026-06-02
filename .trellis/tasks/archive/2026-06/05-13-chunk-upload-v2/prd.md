# 分片上传与断点续传

> 任务 ID：05-13-chunk-upload-v2
> 设计文档：`docs/design/chunk-upload.md` v5
> 状态：planning → 待拆分为子任务执行

---

## 目标

为 RAG 模块的文档上传功能增加大文件分片上传与断点续传能力，同时支持秒传。

## 需求来源

设计文档 `docs/design/chunk-upload.md` v5，经过两轮架构审核 + Trellis spec 合规审查。

## 核心功能

1. **秒传**：前端计算文件 MD5，后端查 DB 命中即返回（不传文件）
2. **分片上传**：文件按策略分片（5/10/20MB），逐片上传，支持断点续传
3. **双重 MD5 校验**：分片 MD5 防传输损坏 + 文件总 MD5 服务端独立计算（合并后 MinIO 流式读取）
4. **Redis Hash + Lua 脚本**：单一 Hash 管理分片状态+ETag，Lua 保证自动合并原子性
5. **MinIO 原生 Multipart Upload**：partNumber = chunkIndex + 1
6. **自动合并 + 手动合并**：Lua 原子触发异步合并，前端也可手动 /complete

## 实现范围（子任务划分）

### P1: 基础设施
- 数据库迁移（V8__rag_document_file_md5.sql）
- Redis 常量类（UploadRedisConstants）
- ErrorCode 扩展（50009-50013）
- ChunkSizeStrategy 接口 + 默认实现
- DTO record 类（5 个）

### P2: Redis Lua 脚本 + 核心服务
- Lua 脚本（atomic_chunk_upload.lua）
- ChunkUploadService 接口
- ChunkUploadServiceImpl（init / uploadChunk / status / complete / abort）

### P3: Controller + 异常映射
- ChunkUploadController（5 个 REST 端点）
- MinIO 异常封装方法
- SecurityConfig 路径注册

### P4: 合并后处理 + 定时清理
- performMerge 流程（MinIO complete + 流式 MD5 + DB 持久化 + ETL 触发）
- 孤儿 Multipart Upload 定时清理
- 合并后 Redis 清理

### P5: 集成测试 + 验证
- 编译验证
- 端到端测试（init → upload → auto-merge → verify）
- 秒传测试
- 断点续传测试

## 约束

- 不引入消息队列
- 复用现有 DocumentValidator、SecurityUtils、MinioClient、StringRedisTemplate
- 所有 Redis key 前缀和 TTL 使用 UploadRedisConstants 常量
- DTO 用 record + @Valid
- 异常统一 BusinessException + ErrorCode
- 编程式事务（本场景不需要 TransactionTemplate）
- 日志按 §16 规范

## 验收标准

- [ ] 5 个 API 端点全部可调通
- [ ] 秒传：相同 MD5 文件不重复上传
- [ ] 分片上传：大文件可分片上传并合并
- [ ] 断点续传：中断后可恢复
- [ ] 自动合并：最后分片上传后自动触发
- [ ] 文件总 MD5 服务端独立计算
- [ ] 并发安全：Lua 脚本保证原子性
- [ ] 214 个现有测试全部通过
- [ ] 编译无错误
