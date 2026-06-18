# PRD: RAG 模块六维审查修复

**来源**: docs/reviews/2026-05-18-rag-module-review.md + 2026-05-18-rag-review-verification.md
**分支**: eval-rag-dev

## 修复计划

### Phase 1: BLOCKER 修复（4项）

| # | 问题 | 文件 | 修复方案 |
|---|------|------|---------|
| B-1 | MinIO download 全量加载 OOM | MinioFileStorageService.java | 改为 InputStreamResource 流式返回 |
| B-2 | 分片上传 complete 与 autoMerge 竞态 | ChunkUploadServiceImpl.java | complete() 开头检查 __merging 标记 |
| B-3 | EncodingDetector 重复 import | EncodingDetector.java | 删除重复行 |
| B-4 | Parent-Child parentId 不匹配导致回查失败 | ParentChildChunkStrategy.java | parentId 改用 parent.getId() |

### Phase 2: HIGH 修复（5项）

| # | 问题 | 文件 | 修复方案 |
|---|------|------|---------|
| H-4 | MinioProperties 硬编码默认凭证 | MinioProperties.java | 去掉默认值 |
| H-5 | PlainTextDocumentParser 全量加载 | PlainTextDocumentParser.java | 增加文件大小上限检查 |
| H-6 | EncodingDetector UTF-8 不必要拷贝 | EncodingDetector.java | UTF-8 兼容文件直接返回原始 Resource |
| H-8 | vectorSearch 无异常降级 | HybridDocumentRetriever.java | 添加 try-catch 降级为空列表 |
| H-12 | 硬编码 50MB 文件大小限制 | ChunkUploadServiceImpl.java | 注入 DocumentProperties 统一配置 |

### Phase 3: MEDIUM 精选修复

| # | 问题 | 文件 |
|---|------|------|
| M-4/5/6 | chunk 策略 null documents | ParentChild/StructureAware/TokenChunkStrategy |
| M-8 | Rerank 空结果未降级 | BailianRerankPostProcessor.java |
| M-9 | RRF fusion doc.getId() 可能为 null | HybridDocumentRetriever.java |
| M-15 | PostProcessor 缓存 volatile 竞态 | RagAdvisorFactory.java |
| M-26 | FastTrackStrategy 丢失原始堆栈 | FastTrackStrategy.java |

## 验证标准

每个 Phase 完成后：
1. `mvn compile -pl . -am` 编译通过
2. `mvn test -pl . -Dtest="com.demo.chat.rag.**"` 全部通过
3. `git add -A && git commit -m "fix(rag): Phase N 描述"`
