# PRD — RAG Code Review 修复（eval-rag-dev 分支 2026-05-16 改动）

> 来源：对 2026-05-16 全天 16 个 commit 的 code review（1 BLOCKER / 3 HIGH / 5 MEDIUM / 4 LOW）

## 背景

今天对 chat-demo RAG 模块进行了大量优化：Pipeline 四项优化、链路重排、record 化重构、Flyway V13、evaluation @Profile 隔离、OpenDataLoader PDF 解析器集成。审查发现 13 个问题需要修复。

## 修复项

### Phase 1: BLOCKER 修复（1 项）

| ID | 文件 | 问题 | 修复方案 |
|----|------|------|----------|
| B1 | `OpenDataLoaderPdfParser.java:58` | `resource.getInputStream().transferTo(Files.newOutputStream(...))` 两个流未关闭，异常时泄漏 | 改为 try-with-resources 包裹 InputStream 和 OutputStream |

### Phase 2: HIGH 修复（3 项）

| ID | 文件 | 问题 | 修复方案 |
|----|------|------|----------|
| H1 | `BailianRerankPostProcessor.java:~159` | `isRetryable()` 用 `e.getMessage().contains("429")` 字符串匹配判断 HTTP 状态码，脆弱且不可靠 | 类型检查 `instanceof WebClientResponseException`，用 `getStatusCode()` 精确判断 429/503 |
| H2 | `RagAdvisorFactory.java:52,139` | `cachedPostProcessors` volatile + check-then-act 竞态，多线程可能重复创建 WebClient | 改为 double-checked locking（synchronized + 二次 null 检查） |
| H3 | `VectorStoreMapper.java:178` | `pairwiseCosineDistance()` 无上限防御，100 个文档产生 9900 个 SQL 参数 | 入口加 `maxDocs` 参数（默认 50），超过时截断并 warn 日志 |

### Phase 3: MEDIUM 修复（5 项）

| ID | 文件 | 问题 | 修复方案 |
|----|------|------|----------|
| M1 | `RagAdvisorFactory.java` | MMR 在 Rerank 前执行时，文档 metadata 无 rerankScore，fallback 到 0.5 时无日志 | 添加 warn 日志说明 fallback 情况 |
| M2 | `application-dev.yml` | API Key 默认值为真实密钥 | 改为空字符串或 `${ENV_VAR:}` 占位符 |
| M3 | `V13__hnsw_tuning.sql` | `ALTER DATABASE` 修改数据库级参数，影响超出当前 schema | 添加注释说明影响范围和回滚方式 |
| M4 | `RagRetrievalProperties.java` | `rerankApiKey` 无 null 校验，rerankEnabled=true 但 key 为空时运行时才报错 | 在 `RagConfig` 或 `BailianRerankPostProcessor` 构造时校验，fail-fast |
| M5 | `BailianRerankPostProcessor.java` | Rerank 全部失败返回原始文档，但 metadata 无 fallback 标记，下游无法感知 | 失败时给每个文档加 `rerankFallback: true` metadata |

### Phase 4: LOW 修复（4 项，可选）

| ID | 文件 | 问题 | 修复方案 |
|----|------|------|----------|
| L1 | `HybridDocumentRetriever.java` | `sanitizeQuery()` 未覆盖 Unicode 引号（`""`、`«»`） | 补充 Unicode 引号 → ASCII 引号的 normalize |
| L2 | `OpenDataLoaderPdfParser.java` | 整个 PDF 输出为单个 Document，丢失分页信息 | 在 metadata 中附加 `pageCount`（从 Markdown 或 JSON 获取） |
| L3 | `pom.xml` | vera-dev 仓库为小众仓库，有可用性风险 | 添加注释说明备用方案（本地缓存 / Maven Central 同步） |
| L4 | `MmrDocumentPostProcessor.java` | 每次请求查 DB 算距离矩阵，热门文档可缓存 | 评估后决定：当前文档量小，暂不缓存，记 TODO |

## 约束

- 每个 Phase 独立 commit
- 编译通过后才能提交
- 不 push，用户手动 push
