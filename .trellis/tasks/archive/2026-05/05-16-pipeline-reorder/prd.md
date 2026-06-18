# RAG Pipeline 链路重排 + 粗粒度召回扩大

## 背景

当前链路：`HybridRetriever(10+10) → Rerank(5) → MMR(5) → ParentChild`
问题：先用 Rerank 精排再做 MMR 去冗余，导致 Rerank 浪费算力在语义重复的文档上。

## 方案

### 新链路
```
HybridRetriever(30+30) → MMR(10) → Rerank(5) → ParentChild
```

### 改动清单

#### Phase 1: 配置调整 (`application-dev.yml`)
- `vector-top-k`: 10 → 30
- `bm25-top-k`: 10 → 30
- `mmr-top-k`: 5 → 10

#### Phase 2: 链路顺序调整

**`RagAdvisorFactory.java`** — `buildPostProcessors()` 中 MMR 和 Rerank 的 add 顺序互换：
```
原来: Rerank → MMR → ParentChild
改为: MMR → Rerank → ParentChild
```

**`EvaluationRunner.java`** — 步骤 4/5 顺序互换：
```
原来: step4=Rerank, step5=MMR
改为: step4=MMR, step5=Rerank
```
capture 标签名对应调整。

#### Phase 3: 注释更新
**`RagConfig.java`** — Pipeline 注释中 Rerank 和 MMR 顺序更新。

## 影响分析

| 阶段 | 原来 | 现在 | 变化 |
|------|------|------|------|
| Embedding API | 1次(1 query) | 1次(1 query) | 不变 |
| BM25 SQL | LIMIT 10 | LIMIT 30 | 更重但 PG 快 |
| MMR cosine | 5×4/2=10对 | 30×29/2=435对 | 增加但 PG 层快 |
| Rerank API | 5条 → 5条 | 10条 → 5条 | 稍多但 API 支持 |

## 文件清单
1. `src/main/resources/application-dev.yml` — 配置值
2. `src/main/java/com/demo/chat/rag/config/RagAdvisorFactory.java` — 顺序互换
3. `src/main/java/com/demo/chat/rag/evaluation/runner/EvaluationRunner.java` — 顺序互换
4. `src/main/java/com/demo/chat/rag/config/RagConfig.java` — 注释
