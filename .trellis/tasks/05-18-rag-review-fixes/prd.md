# PRD: RAG 模块六维代码审查修复

**来源**: `docs/reviews/2026-05-18-rag-module-review.md`
**分支**: eval-rag-dev
**状态**: ✅ 全部完成

---

## 完成清单

### Phase 1 — BLOCKER 修复（2/2 ✅）
- [x] **B1**: `DocumentValidator.detectMimeType()` InputStream 未关闭 → try-with-resources (`ba25847`)
- [x] **B2**: `EncodingDetector.detectAndTranscode()` InputStream 未关闭 + 流消费后返回新 Resource (`ba25847`)

### Phase 2 — MEDIUM 修复（3/3 ✅）
- [x] **M1**: `PlainTextDocumentParser` 大文件内存峰值日志 (`f402040`)
- [x] **M2**: `HybridDocumentRetriever` BM25 失败日志 warn→error (`f402040`)
- [x] **M7**: `StandardStrategy`/`FastTrackStrategy` joinAll 加 orTimeout(5min) (`f402040`)

### Phase 3 — BailianRerankPostProcessor 高并发改造（✅）
- [x] **H4+L1+L2**: 内部异步化 + responseTimeout(15s) + index>=0 校验 (`5df79cb`)
  - 内部 `ExecutorService rerankExecutor`（全 7 参数 + NamedThreadFactory + CallerRunsPolicy）
  - `Future.get(15s)` 替代 `block()`
  - HttpClient 增加 `responseTimeout(15s)`
  - index >= 0 防御性校验
  - 11 个测试全绿

### Phase 4 — LOW 清理（✅）
- [x] **L5**: VectorStoreLoader.deleteByDocumentId 已有日志，无需修改

### 不修复（已降级/驳回）
- ~~M5~~ → LOW: 框架约定，直接修改 metadata 是 Spring AI 设计模式
- ~~M6~~ → 不修复: 48h 阈值足够宽，串行扫描低优先
- ~~H1~~ → 驳回: 已有 MAX_PAIRWISE_DOCS 防御
- ~~H2~~ → LOW: pendingSupersede 影响极小
- ~~H3~~ → LOW: whenComplete 兜底
- ~~L3~~ → 记录 TODO: 分片全量读入内存
- ~~L4~~ → 记录 TODO: 每次创建新 retriever

---

## Commits

| Phase | Commit | 内容 |
|-------|--------|------|
| 1 | `ba25847` | B1 DocumentValidator + B2 EncodingDetector InputStream 修复 |
| 2 | `f402040` | M1 大文件日志 + M2 BM25日志升级 + M7 orTimeout |
| 3 | `5df79cb` | BailianRerankPostProcessor 内部异步化 + L1 responseTimeout + L2 index>=0 |

## 验收结果

- 编译通过 ✅
- 469 测试全绿 ✅
- Spec 合规（OCP/封装/DIP）✅
