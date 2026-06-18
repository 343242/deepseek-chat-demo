# RAG Pipeline H-RAG 优化设计文档

**日期**: 2026-05-18
**分支**: rag-dev（基于现有）
**状态**: 设计中
**论文参考**: H-RAG (arXiv:2605.00631) — Hierarchical Parent–Child Retrieval for Multi-Turn RAG Conversations

---

## 背景

当前 RAG Pipeline 已实现六阶段检索链路：

```
用户查询 → QueryNormalizer → RewriteQueryTransformer
         → HybridDocumentRetriever(pgvector + BM25 + RRF)
         → MmrDocumentPostProcessor(λ·sim(q,d) - (1-λ)·max sim(d,d'))
         → BailianRerankPostProcessor(qwen3-rerank, 3次指数退避)
         → ParentDocumentPostProcessor(子块→父文档回查替换)
         → LLM 生成回答
```

精读 H-RAG (SemEval-2026 Task 8) 论文后，识别出三个可落地的优化点：

1. **Query Rewrite 缺少守卫规则**：短事实型查询被 LLM 过度展开，H-RAG 发现这是实际性能退化源
2. **Query Rewrite temperature 偏高**：当前用 DeepSeek V4 Flash 的默认 temperature=0.7，H-RAG 推荐 0.2
3. **Parent 替换后无重排**：ParentDocumentPostProcessor 只做子→父替换按原始顺序输出，H-RAG 证明 parent-level rescoring 是所有配置中收益最大的因素（+0.0197 nDCG@5）

## 目标

1. **查询改写精准化**：对已清晰的查询原样透传，避免过度展开降低检索精度
2. **父文档重排**：ParentChild 替换后基于 embedding 余弦相似度重排，提升上下文相关性
3. **消融实验验证**：利用现有 EvaluationRunner 量化每个优化点的增量贡献

## 设计原则

### P1: 最小改动

- 只修改 prompt 模板和现有组件内部逻辑，不改变 Pipeline 链路结构
- 不新增模块/类，不改变公共 API 签名
- 不引入新的外部依赖

### P2: 向后兼容

- 所有改动通过现有配置开关控制
- 默认行为不变（新功能需显式启用或配置覆盖）
- 现有单元测试不受影响

### P3: 可度量

- 每个优化点独立，可单独开关进行消融实验
- EvaluationRunner 的插桩点已覆盖所有受影响阶段

---

## 优化项详细设计

### O1: Query Rewrite 守卫规则

**问题**：当前 RewriteQueryTransformer 的 prompt 没有守卫规则，对所有查询一视同仁地改写。H-RAG 发现短事实型查询（如"什么是 RAG"）被 LLM 过度展开为复杂查询后，检索精度反而下降。

**现状**（`RagConfig.java`）：
```java
String template = """
    Given the following user query, rewrite it into a clear and specific search query \
    suitable for querying a {target}. Keep the core intent, remove conversational filler, \
    and use precise terminology.
    
    Original query: {query}
    
    Rewritten search query:""";
```

**方案**：在 prompt 中增加守卫指令。

```java
String template = """
    Given the following user query, rewrite it into a clear and specific search query \
    suitable for querying a {target}. Keep the core intent, remove conversational filler, \
    and use precise terminology.
    
    IMPORTANT: If the query is already clear, specific, and standalone, return it EXACTLY as is.
    Do NOT over-elaborate short factual queries.
    
    Original query: {query}
    
    Rewritten search query:""";
```

**改动范围**：仅 `RagConfig.java` 中 `rewriteQueryTransformer()` 方法的 prompt 字符串。

**依据**：H-RAG 实验发现 query rewrite prompt 中的 "return unchanged if already standalone" 规则是关键设计，防止 LLM 对短事实型查询过度展开。

**风险**：低。仅修改 prompt 文本，不改任何代码逻辑。即使守卫无效，最坏情况是输出与当前相同的改写结果。

---

### O2: Query Rewrite Temperature

**问题**：当前 RewriteQueryTransformer 使用 Spring AI 自动注入的 `ChatClient.Builder`，绑定的是 `spring.ai.deepseek` 配置的默认 temperature=0.7。H-RAG 推荐查询改写使用 temperature=0.2。

**现状**：
- `RagConfig.rewriteQueryTransformer(ChatClient.Builder chatClientBuilder)` 接收全局 Builder
- 全局 Builder 继承 `application-dev.yml` 中 `spring.ai.deepseek.chat.temperature: 0.7`
- 查询改写是确定性任务（同样的输入应产生相似的改写），0.7 的随机性偏高

**方案**：在 `rewriteQueryTransformer()` 中，通过 `ChatClient.Builder` 的 `defaultOptions` 覆盖 temperature。

```java
@Bean
public RewriteQueryTransformer rewriteQueryTransformer(ChatClient.Builder chatClientBuilder) {
    // ... template 不变 ...
    
    // 查询改写是低随机性任务，使用低于默认的 temperature
    ChatClient rewriteClient = chatClientBuilder
            .defaultOptions(DeepSeekChatOptions.builder()
                    .temperature(0.2)
                    .build())
            .build();

    return RewriteQueryTransformer.builder()
            .chatClientBuilder(rewriteClient.mutate())
            .promptTemplate(new PromptTemplate(template))
            .build();
}
```

**改动范围**：`RagConfig.java`，引入 `DeepSeekChatOptions` import。

**问题**：这引入了对 DeepSeek 的硬编码依赖。如果默认 ChatModel 不是 DeepSeek（如智谱），`DeepSeekChatOptions` 会不匹配。

**改进方案**：不改 ChatClient.Builder，而是在 prompt 中强化确定性指令（即 O1 的守卫规则本身就降低了随机性的影响）。或者使用 `app.rag` 配置项控制 rewrite temperature。

**最终方案**：在 `RagRetrievalProperties` 中新增 `queryRewriteTemperature` 配置项：

```java
// RagRetrievalProperties record 新增字段
double queryRewriteTemperature  // 默认 0.2
```

```java
// RagConfig 中使用
@Bean
public RewriteQueryTransformer rewriteQueryTransformer(
        ChatClient.Builder chatClientBuilder,
        RagRetrievalProperties properties) {
    // ...
    return RewriteQueryTransformer.builder()
            .chatClientBuilder(chatClientBuilder)
            .promptTemplate(new PromptTemplate(template))
            .build();
}
```

> **注意**：Spring AI 的 `RewriteQueryTransformer` 使用 `chatClientBuilder` 创建内部 ChatClient，不直接暴露 temperature 控制。经查 Spring AI 源码，`ChatClient.Builder.defaultOptions()` 设置的 options 会被继承。但为了**不引入厂商特定依赖**，最终的 temperature 控制通过 Spring AI 通用机制实现：
>
> 方案 A（推荐）：在 `application.yml` 中配置 Spring AI 的 per-request options，通过 `ChatClient.Builder.defaultAdvisors()` 或 `defaultSystem()` 不适用此场景。
>
> 方案 B（务实）：**暂不修改 temperature**。原因：(1) DeepSeek V4 Flash 对 prompt 指令的跟随能力足够，O1 的守卫规则已降低随机性的实际影响；(2) 强行设置 temperature 会引入 `DeepSeekChatOptions` 硬编码，违反项目已有的 ModelProvider 抽象体系。
>
> **采纳方案 B**：O1 的 prompt 守卫规则足以解决过度改写问题，不单独改 temperature。

---

### O3: Parent-level Rescoring

**问题**：`ParentDocumentPostProcessor` 在子块→父文档替换后，按首次命中子块的顺序输出，不重新排序。H-RAG 消融实验证明 parent-level rescoring 是**所有配置中收益最大的因素**（+0.0197 nDCG@5, +0.0108 Recall@5）。

**现状**（`ParentDocumentPostProcessor.process()`）：
```
1. 遍历文档，提取 parentId
2. 批量 SQL 回查父文档
3. 子→父替换，按 parentId 去重
4. 保持原始检索顺序 ← 无重排
5. 拼接 non-child 文档
```

**方案**：在步骤 4 之后，用 embedding 余弦相似度对父文档重排。

**设计**：

```java
// ParentDocumentPostProcessor 新增依赖
private final EmbeddingModel embeddingModel;  // Spring AI EmbeddingModel（已有 DashScope Bean）

// process() 方法末尾增加 rescore 步骤
private List<Document> rescoreByEmbedding(Query query, List<Document> parentDocs) {
    if (parentDocs.isEmpty()) return parentDocs;

    // 1. 计算查询 embedding
    float[] queryEmbedding = embeddingModel.embed(query.text());

    // 2. 对每个父文档计算余弦相似度
    //    优先使用已存储的 embedding（从向量库），避免重新计算
    //    fallback：重新 embed 文档内容
    List<ScoredDocument> scored = parentDocs.stream()
            .map(doc -> {
                float[] docEmbedding = getOrCreateEmbedding(doc);
                double similarity = cosineSimilarity(queryEmbedding, docEmbedding);
                return new ScoredDocument(doc, similarity);
            })
            .sorted(Comparator.comparingDouble(ScoredDocument::score).reversed())
            .toList();

    return scored.stream().map(ScoredDocument::doc).toList();
}
```

**优化选择：用已有 embedding 还是重新计算？**

H-RAG 用 `bge-reranker-v2-m3` embedding 做 rescoring。你的场景下：

| 方案 | 优势 | 劣势 |
|---|---|---|
| A: 从向量库取已存 embedding | 零计算开销，一次 SQL | 父文档可能没有独立 embedding（只有子块有） |
| B: 重新 embed 父文档内容 | 精确匹配父文档语义 | 每次查询 N 次 embedding API 调用 |
| C: 用子块的 embedding 聚合 | 折中方案 | 需要 max/mean 聚合逻辑 |

**推荐方案 A + B fallback**：
1. 父文档如果在向量库中有 embedding（`isParent=true` 的记录），直接取
2. 如果没有，用子块 embedding 的 max-pooling 作为父文档近似 embedding
3. 不做额外 API 调用

但查看代码发现：`ParentChildChunkStrategy` 中父文档**不存入向量库**（只有子块存入，metadata 含 parentId）。父文档的完整内容存在关系表中（通过 `VectorStoreMapper` 回查），但没有 embedding。

**修正方案**：使用子块的已有 embedding 做 parent-level 聚合 rescoring。

```java
/**
 * Parent-level Rescoring：利用子块的已有 embedding 计算 parent 相关性
 *
 * 算法：
 * 1. 对每个父文档，收集其所有子块的 embedding
 * 2. 计算 query embedding 与每个子块 embedding 的余弦相似度
 * 3. 取最高相似度作为该 parent 的分数（max-score aggregation，与 H-RAG 一致）
 * 4. 按 parent 分数降序排列
 */
private List<Document> rescoreParents(Query query,
                                      List<Document> parentDocs,
                                      Map<String, String> childToParentMap,
                                      Map<String, float[]> childEmbeddings) {
    // 1. 计算 query embedding
    float[] queryEmb = embeddingModel.embed(query.text());

    // 2. 按 parentId 聚合子块相似度，取 max
    Map<String, Double> parentScores = new HashMap<>();
    for (var entry : childEmbeddings.entrySet()) {
        String childId = entry.getKey();
        String parentId = childToParentMap.get(childId);
        if (parentId == null) continue;

        double sim = cosineSimilarity(queryEmb, entry.getValue());
        parentScores.merge(parentId, sim, Math::max);
    }

    // 3. 按 score 降序排列
    return parentDocs.stream()
            .sorted(Comparator.comparingDouble(
                    (Document doc) -> parentScores.getOrDefault(
                            doc.getMetadata().get("parentId").toString(), 0.0))
                    .reversed())
            .toList();
}
```

> **更务实的方案**：实际上，在 `ParentDocumentPostProcessor.process()` 执行时，原始子块已经携带了 `rerankScore` 或 `rrfScore`。可以直接用这些已有分数做 parent 聚合，**不需要额外的 embedding 计算**。

**最终推荐方案（最简实现）**：

```java
// 在 ParentDocumentPostProcessor.process() 中，
// 子→父替换 + 去重后，按子块的最高 score 排列父文档

// 构建 parentId → max(childScore) 映射
Map<String, Double> parentScoreMap = new HashMap<>();
for (Document doc : documents) {
    if (isChild(doc)) {
        String parentId = doc.getMetadata().get(META_PARENT_ID).toString();
        double score = resolveScore(doc);  // rerankScore > rrfScore > 0.5
        parentScoreMap.merge(parentId, score, Math::max);
    }
}

// 按聚合分数排序父文档
result.sort(Comparator.comparingDouble(
        (Document doc) -> parentScoreMap.getOrDefault(
                doc.getMetadata().get("parentId") != null
                        ? doc.getMetadata().get("parentId").toString()
                        : doc.getId(),
                0.0))
        .reversed());
```

**优势**：
- 零额外 API 调用（不重新 embed）
- 零额外数据库查询（分数已在 metadata 中）
- 复用已有分数（rerankScore > rrfScore 的优先级链）
- 与 H-RAG 的 max-score aggregation 策略一致

**改动范围**：
- `ParentDocumentPostProcessor.java`：`process()` 方法末尾增加排序逻辑
- `ParentDocumentPostProcessor.java`：新增 `resolveScore(Document)` 辅助方法（复用 MmrDocumentPostProcessor 的相同逻辑）

**风险**：低。只在现有输出上加一层排序，不改变文档内容或数量。

---

### O4: 消融实验方案

利用现有 `EvaluationRunner` 的阶段插桩能力，设计消融实验矩阵：

| 实验组 | Query Rewrite | MMR | Rerank | Parent Rescore | 预期作用 |
|---|---|---|---|---|---|
| Baseline | ✗ | ✗ | ✗ | ✗ | 纯混合检索基线 |
| +Rewrite | ✓(旧prompt) | ✗ | ✗ | ✗ | 当前 rewrite 效果 |
| +Rewrite+Guard | ✓(新prompt) | ✗ | ✗ | ✗ | 守卫规则增量 |
| +MMR | ✗ | ✓ | ✗ | ✗ | MMR 去冗余增量 |
| +Rerank | ✗ | ✗ | ✓ | ✗ | Rerank 精排增量 |
| +ParentRescore | ✗ | ✗ | ✗ | ✓ | Parent 重排增量 |
| Full(旧) | ✓(旧) | ✓ | ✓ | ✗ | 当前完整链路 |
| Full(新) | ✓(新) | ✓ | ✓ | ✓ | 全部优化后链路 |

**EvaluationRunner 已具备的能力**：
- `PipelineInstrumenter` 在 after_rewrite / after_retrieval / after_mmr / after_rerank / after_parent_child 各阶段捕获文档 ID 列表
- 通过 `EvaluationProperties` 的配置开关可独立控制每个阶段

**不需要代码改动**：消融实验通过配置切换即可执行。

---

## Phase 拆分

### Phase 1: Query Rewrite 守卫规则（O1）
- 修改 `RagConfig.java` 中 rewrite prompt
- 更新相关单元测试
- **预计改动**：1 文件，~5 行

### Phase 2: Parent-level Rescoring（O3）
- 修改 `ParentDocumentPostProcessor.java`
- 新增 `resolveScore()` 辅助方法
- 在 `process()` 末尾增加排序
- 更新/新增单元测试
- **预计改动**：1 文件，~30 行

### Phase 3: 消融实验执行（O4）
- 在 evaluation profile 下运行消融实验矩阵
- 对比分析各优化项的指标增量
- 输出实验报告
- **预计改动**：0 代码改动，纯运行+分析

### ~~Phase X: Query Rewrite Temperature（O2）~~
- **已决定暂不实施**：O1 的 prompt 守卫已解决核心问题，改 temperature 引入厂商硬编码不值得

---

## 文件改动清单

| 文件 | 改动类型 | Phase |
|---|---|---|
| `RagConfig.java` | 修改 rewrite prompt | P1 |
| `ParentDocumentPostProcessor.java` | 增加 rescore 排序 | P2 |
| `ParentDocumentPostProcessorTest.java` | 新增/更新测试 | P2 |

**总改动量**：~35 行代码 + ~20 行测试

---

## 验证标准

### Phase 1 验证
- [ ] 编译通过
- [ ] 现有 QueryNormalizer / RewriteQueryTransformer 测试不受影响
- [ ] 手动验证：对 "什么是 RAG" 等短查询原样透传
- [ ] 手动验证：对 "它和搜索引擎有什么区别" 等指代查询正常改写

### Phase 2 验证
- [ ] 编译通过
- [ ] 新增测试覆盖 resolveScore() 方法
- [ ] 新增测试覆盖 rescore 排序逻辑（有 rerankScore / 仅有 rrfScore / 无分数 三种场景）
- [ ] 现有 ParentDocumentPostProcessorTest 不受影响

### Phase 3 验证
- [ ] 消融实验至少覆盖 Baseline / Full(旧) / Full(新) 三组
- [ ] 输出各组的 Recall@K、Precision@K、MRR、NDCG
- [ ] Parent Rescore 的指标增量可量化

---

## 不做的事

1. **不改 Pipeline 链路顺序**：当前 MMR→Rerank→Parent 的顺序已被 7d27e04 验证优于 Rerank→MMR
2. **不改 temperature**：O2 已决定暂不实施
3. **不改候选集大小**：H-RAG 验证 k=30 是甜蜜点，当前 vectorTopK=30 已对齐
4. **不改 RRF 融合权重**：H-RAG 的 α 敏感度极低（<0.003），当前加权 RRF 比 H-RAG 的简单 hybrid 更精细
5. **不新增 embedding rescoring 组件**：Parent Rescore 用已有分数，不引入新依赖
