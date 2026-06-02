# PRD: PostgreSQL 集成 pg_jieba 中文分词

## 1. 背景

当前 RAG 模块的 BM25 全文检索使用 PostgreSQL `simple` 文本搜索配置。`simple` 对中文采用逐字切分，将"自然语言处理"拆成"自""然""语""言""处""理"6个单字，导致：
- 精确词组匹配失效（搜"自然语言"无法命中包含"自然语言处理"的文档）
- 索引膨胀（每个汉字单独建索引条目）
- 检索召回率低

## 2. 目标

集成 pg_jieba 扩展，将 BM25 全文检索从逐字切分升级为结巴中文分词，提升 RAG 检索质量。

## 3. 功能需求

### 3.1 自定义 PostgreSQL Docker 镜像

- 基于 `postgres:18-bookworm` 编译安装 pg_jieba 扩展
- Dockerfile 放在 `docker/postgres/` 目录
- docker-compose.yml 改用 `build` 替代 `image`

### 3.2 数据库迁移

- 新增 Flyway 迁移脚本，启用 pg_jieba 扩展
- 更新 vector_store 的触发器函数：`to_tsvector('simple', ...)` → `to_tsvector('jiebacfg', ...)`
- 回填已有数据的 content_tsv 列
- 更新 GIN 索引

### 3.3 Java 代码适配

- HybridDocumentRetriever 中 BM25 查询的 `plainto_tsquery('simple', ...)` → `plainto_tsquery('jiebacfg', ...)`
- 文本搜索配置从硬编码 `'simple'` 改为可配置项（RagRetrievalProperties 新增 `ftsConfig` 字段）
- SQL 中的配置名使用参数注入，不硬编码

### 3.4 配置项

| 配置项 | 说明 | 默认值 | 环境变量 |
|--------|------|--------|---------|
| `app.rag.fts-config` | PostgreSQL 全文检索配置名 | `jiebacfg` | `RAG_FTS_CONFIG` |

当 `jiebacfg` 不可用时，用户可改回 `simple` 作为降级方案。

## 4. 非功能需求

- pg_jieba 编译不影响 PostgreSQL 启动速度（编译只在镜像构建阶段）
- 迁移脚本幂等（IF NOT EXISTS / CREATE OR REPLACE）
- 现有向量检索、RRF 融合、Rerank 不受影响
- 回填大数据量时不锁表（小批量 UPDATE 或使用 WHERE 分页）

## 5. 改动范围

| 文件 | 改动类型 | 说明 |
|------|---------|------|
| `docker/postgres/Dockerfile` | 新增 | 自定义镜像，编译 pg_jieba |
| `docker-compose.yml` | 修改 | postgres 服务改用 build |
| `src/main/resources/db/migration/V4__pg_jieba_chinese_fts.sql` | 新增 | 启用扩展 + 更新分词配置 |
| `src/main/java/com/demo/chat/rag/config/RagRetrievalProperties.java` | 修改 | 新增 ftsConfig 字段 |
| `src/main/java/com/demo/chat/rag/retrieval/HybridDocumentRetriever.java` | 修改 | 使用可配置的 ftsConfig |
| `src/main/resources/application.yml` | 修改 | 新增 fts-config 配置 |

## 6. 不做的事

- 不做自定义词典（使用 pg_jieba 默认词典，后续可扩展）
- 不做多租户分词配置（全局统一 jiebacfg）
- 不修改向量检索逻辑（只改 BM25 全文检索部分）

## 7. 验收标准

- [ ] `docker compose build postgres` 成功构建包含 pg_jieba 的镜像
- [ ] `CREATE EXTENSION pg_jieba` 在数据库中成功执行
- [ ] `SELECT to_tsvector('jiebacfg', '自然语言处理')` 输出"自然/语言/处理"（而非单字切分）
- [ ] vector_store 的 content_tsv 列使用 jiebacfg 分词
- [ ] HybridDocumentRetriever 的 BM25 查询使用可配置的 ftsConfig
- [ ] 通过环境变量可回退到 simple 配置
- [ ] 编译通过，无新增编译错误

## 8. OCP 验证

更换分词引擎（如从 pg_jieba 切换到 zhparser）需要：
1. 修改 Dockerfile（安装新扩展）
2. 修改 SQL 迁移脚本
3. **零修改** Java 代码（只需改配置项 `app.rag.fts-config`）

## 9. 新增同类功能 Checklist

如需切换到其他中文分词插件：
1. 修改 Dockerfile 安装新扩展
2. 新增/修改 SQL 迁移脚本启用扩展
3. 修改配置 `app.rag.fts-config` 为新配置名
4. **不修改** HybridDocumentRetriever / RagRetrievalProperties
