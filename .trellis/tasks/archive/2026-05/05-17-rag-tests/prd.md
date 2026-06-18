# PRD — RAG 模块单元测试补充

## 背景

RAG 模块是 chat-demo 项目最核心的业务模块（~75 个 Java 文件），但测试覆盖几乎为零。现有 29 个测试中 RAG 只有 2 个（RetrievalMetricsCalculator、EvaluationRunStatus），retrieval、etl、chunk、parser、embedding 等子模块完全没有测试。

## 目标

对 RAG 模块的核心路径补充单元测试，确保：
- 核心算法逻辑（RRF 融合、MMR 选择、分块策略）正确性
- 关键组件（HybridDocumentRetriever、MmrDocumentPostProcessor、QueryNormalizer）行为可验证
- 回归保护：后续优化/重构不会静默破坏已有行为

## 测试范围与 Phase 划分

### Phase 1: Retrieval 层（核心检索逻辑，最高优先级）

| 测试类 | 测试目标 | 要点 |
|--------|---------|------|
| `HybridDocumentRetrieverTest` | RRF 融合算法、向量+BM25 合并排序 | Mock VectorStore + VectorStoreMapper；验证 vectorScore 加权 RRF；验证纯 BM25 fallback；验证隔离字段选择 |
| `MmrDocumentPostProcessorTest` | MMR 贪心选择、lambda 权衡、cosine distance 使用 | Mock VectorStoreMapper.pairwiseCosineDistance；验证 topK 截断；验证 relevance score fallback (rerankScore→rrfScore→0.5) |
| `BailianRerankPostProcessorTest` | Rerank API 调用、重试逻辑、fallback 标记 | Mock WebClient；验证 429/503 重试；验证全部失败返回原始文档 + rerankFallback metadata |
| `QueryNormalizerTest` | （已有，检查覆盖度） | 补充 Unicode 引号 normalize、全角→半角 |

### Phase 2: Chunk 层（分块策略）

| 测试类 | 测试目标 | 要点 |
|--------|---------|------|
| `TokenChunkStrategyTest` | Token 计数分块 | 验证边界条件：空文档、超长单段、刚好整数块 |
| `StructureAwareChunkStrategyTest` | 按标题层级分块 | 验证 #/##/### 边界识别、无标题文档 fallback |
| `ParentChildChunkStrategyTest` | 父子文档关联 | 验证 parentId 元数据正确性 |
| `ChunkStrategyFactoryTest` | 工厂路由 | 验证按配置选择策略 |

### Phase 3: Parser 层（文档解析）

| 测试类 | 测试目标 | 要点 |
|--------|---------|------|
| `DocumentParserFactoryTest` | MIME 路由 + Tika 兜底 | 验证已知 MIME → 特定 Parser；未知 MIME → Tika |
| `PlainTextDocumentParserTest` | 纯文本解析 | 简单验证 |
| `MarkdownDocumentParserTest` | Markdown 解析 | 验证 metadata 保留 |
| `OpenDataLoaderPdfParserTest` | ODL 集成（Mock OpenDataLoaderPDF） | 验证临时文件清理、空 markdown fallback、异常转换 |

### Phase 4: ETL 层（Pipeline 编排）

| 测试类 | 测试目标 | 要点 |
|--------|---------|------|
| `EtlRouteStrategyFactoryTest` | Standard vs FastTrack 路由 | 验证小文档走 FastTrack |
| `EtlStatusManagerTest` | ETL 状态机 | 验证 PENDING→PROCESSING→COMPLETED/FAILED 转换 |
| `VectorStoreMapperTest` | SQL 拼接正确性 | 验证 BM25 SQL 参数绑定；验证 pairwiseCosineDistance 截断防御 |

## 约束

- 纯单元测试，Mock 外部依赖（VectorStore、JdbcTemplate、WebClient、MinIO）
- 不依赖数据库/Redis/网络
- 每个 Phase 独立 commit
- 编译 + `mvn test -pl . -Dtest="com.demo.chat.rag.**"` 通过

## 预估测试数量

Phase 1: ~15 个测试方法
Phase 2: ~10 个测试方法
Phase 3: ~8 个测试方法
Phase 4: ~6 个测试方法
