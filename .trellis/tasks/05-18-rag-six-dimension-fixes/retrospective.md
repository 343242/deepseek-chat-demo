# Retrospective: RAG 模块六维审查修复

**日期**: 2026-05-18
**分支**: eval-rag-dev
**Commits**: 8a1ad5b → 765ec41 → a5ad269

## 做了什么

对 rag 模块 113 个 Java 源文件进行六维深度代码审查（资源泄漏/边界条件/并发安全/性能陷阱/异常处理/内存泄漏），发现 66 个问题，按严重级别分 3 个 Phase 修复。

### Phase 1 — BLOCKER (4项)
- **B-1**: MinioFileStorageService.download() 改为流式 MinioStreamResource，避免 readAllBytes OOM
- **B-2**: ChunkUploadServiceImpl.complete() 增加 __merging 标记检查，防止双重合并
- **B-3**: EncodingDetector 删除重复 import
- **B-4**: ParentDocumentPostProcessor rescoring 排序键从 doc.getId() 修正为 metadata.parentId

### Phase 2 — HIGH (5项)
- **H-4**: MinioProperties 去掉凭证默认值
- **H-5**: PlainTextDocumentParser 增加 50MB 上限
- **H-6**: EncodingDetector UTF-8 兼容文件不拷贝
- **H-8**: vectorSearch 添加 try-catch 降级
- **H-12**: validateFileSize 改用统一配置

### Phase 3 — MEDIUM (6项)
- 三个 ChunkStrategy null 检查
- Rerank 空结果降级
- RRF fusion docId null 防御
- FastTrackStrategy 异常消息增强

## 核对教训

子代理审查报告需要人工核对：
- **H-1 升级为 BLOCKER**: 报告说"排序键错误"，实际追踪 PgVectorStore 源码后发现 batchFetchParents 用 metadata 查询是正确的，但 rescoring 排序键确实错误
- **H-7 降级为 LOW**: 报告说"Resource 未关闭"，但 ByteArrayResource 无需关闭
- **H-14 误报**: WebClient + bodyToMono 自动管理连接，不存在资源泄漏
- **batchFetchParents 用 WHERE metadata->>'parentId'**: 不是 WHERE id IN，父文档回查是可以工作的

## 正确做法

1. 审查报告必须逐个读源码核对，不能信任子代理 100%
2. 修复 BLOCKER 后立即编译+测试，避免累积错误
3. 测试失败时先分析是否是修复的预期行为（如 rescoring 排序改变导致测试断言需更新）
4. 追踪 ID 生命周期要读到框架源码（PgVectorStore.doAdd 确认了用 document.getId()）
