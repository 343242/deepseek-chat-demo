# RAG Pipeline 四项优化

## 概述

排除 QueryRewrite 条件触发（用户暂不做），对其余四项进行优化：
1. MMR 用 embedding cosine 替代 Jaccard
2. Rerank API 添加三次重试 + 指数退避
3. BM25 query 用 pg_jieba 分词
4. vectorSearch 保留相似度分数用于 RRF 融合

---

## 优化 1：MMR 用 embedding cosine 替代 Jaccard（P0）

### 现状
`MmrDocumentPostProcessor` 用 bigram + Jaccard 相似度衡量文档间冗余。
中文没有空格分词，bigram 对中文效果很差。

### 方案
- 注入 `EmbeddingModel` 到 `MmrDocumentPostProcessor`
- 批量 embed 候选文档（通常 5-10 条，DashScope 单次最多 10 条，刚好 1 次 API 调用）
- 用 cosine similarity 替代 Jaccard 计算文档间冗余
- 查询-文档相关性：优先 rerankScore > rrfScore > cosine(query, doc)

### 改动文件
- `MmrDocumentPostProcessor.java`：构造函数加 `EmbeddingModel`，替换 tokenize/jaccard 为 cosine
- `RagAdvisorFactory.java`：注入 `EmbeddingModel`，传给 MMR 构造函数

---

## 优化 2：Rerank API 三次重试 + 指数退避（P1）

### 现状
Rerank API 失败直接 fallback 到原始排序，无重试。

### 方案
- 与 `DashScopeEmbeddingModel` 相同的重试模式：MAX_RETRIES=3, INITIAL_BACKOFF_MS=500
- 退避策略：500ms → 1000ms → 2000ms
- 可重试条件：429/503/timeout/网络错误
- 非重试条件：4xx 客户端错误（除 429）

### 改动文件
- `BailianRerankPostProcessor.java`：API 调用逻辑包装重试循环

---

## 优化 3：BM25 query 用 pg_jieba 分词（P1）

### 现状
`HybridDocumentRetriever.sanitizeQuery()` 只去掉 tsquery 特殊字符，对中文长句整句匹配效果差。
`VectorStoreMapper.bm25Search()` 用 `plainto_tsquery('jiebacfg', query)` — pg_jieba 的 `plainto_tsquery` 实际上会调用 jieba 分词。

### 分析
经确认，`plainto_tsquery('jiebacfg', text)` 在 pg_jieba 配置下**已经会做 jieba 分词**。
pg_jieba 的 `jiebacfg` 配置注册了 jieba 分词器，`plainto_tsquery` 会通过该配置自动调用 jieba。

### 结论
**BM25 分词已经是 jieba 的了**，不需要额外改动。
但 `sanitizeQuery` 可以优化：去掉 `&|!()[]{}:*\\` 后可能破坏中文短语。
改为更精确的净化：只去掉 PG tsquery 运算符，保留中文原文让 jieba 处理。

### 改动文件
- `HybridDocumentRetriever.java`：`sanitizeQuery` 改为只去掉 PG tsquery 运算符，保留完整中文文本

---

## 优化 4：vectorSearch 保留相似度分数（P2）

### 现状
`vectorSearchWithScore` 只用排名（i+1），丢弃了 PgVectorStore 返回的 cosine distance。
Spring AI PgVectorStore 的 `DocumentRowMapper` 已将 distance 写入 `metadata["document.distance"]`，
score 写入 `Document.score`（= 1 - distance）。

### 方案
- 从 Document 的 metadata 中提取 `document.distance` 作为向量相似度分数
- RRF 融合时，分数高的文档在相同排名下应得到更高权重
- 但 RRF 本身只用排名，不用分数 — 这是 RRF 算法的设计
- 更好的方案：**RRF 加权变体**，用 `1/(k + rank) * score` 替代纯 `1/(k + rank)`

### 改动文件
- `HybridDocumentRetriever.java`：
  - `vectorSearchWithScore` 从 metadata 提取 distance/score
  - `ScoredDocument` record 加 score 字段
  - `rrfFusion` 加权：`score * 1/(k + rank)` 替代纯 `1/(k + rank)`

---

## 不做的事

- QueryRewrite 条件触发 — 用户暂不做
- Embedding 缓存 — 收益有限
- iterative_scan — V13 已有，等确认

## 执行顺序

1. 优化 4（vectorSearch 分数）→ 影响 RRF，是基础
2. 优化 3（BM25 sanitizeQuery）→ 小改动
3. 优化 2（Rerank 重试）→ 独立改动
4. 优化 1（MMR embedding cosine）→ 需要注入 EmbeddingModel
