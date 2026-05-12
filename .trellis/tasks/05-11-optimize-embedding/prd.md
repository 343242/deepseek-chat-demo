# PRD: 优化 RAG Embedding 模型 — 对接百炼 text-embedding-v4 高级特性

## 背景

当前 `DashScopeEmbeddingModel` 通过 OpenAI 兼容接口调用百炼 text-embedding-v4，仅使用了基础功能（model、input、dimensions）。根据百炼官方文档，text-embedding-v4 支持多个高级参数可显著提升 RAG 检索质量，但当前未启用。

## 目标

在**保留手写实现**的前提下，引入以下百炼高级特性：
- **text_type**：区分 query/document 文本类型，优化检索效果
- **instruct**：自定义任务指令，进一步提升查询精度
- **encoding_format**：显式指定返回格式（float）

**不引入**：sparse 向量（output_type=dense&sparse），保持现有 dense-only 方案。

## 技术设计

### 核心挑战

PgVectorStore 内部调用 `EmbeddingModel` 时**不区分场景**：
- `vectorStore.add(documents)` → 调用 `embed(Document)` → **入库场景**（text_type=document）
- `vectorStore.similaritySearch(query)` → 调用 `embed(String)` → **查询场景**（text_type=query）

### 方案：基于方法重写的自动场景识别

`DashScopeEmbeddingModel` 已覆写了 `embed(Document)` 和 `embed(String)` 两个方法，恰好可以区分场景：

```
embed(Document doc)  → text_type=document （入库，由 VectorStoreLoader → PgVectorStore 调用）
embed(String text)   → text_type=query    （查询，由 similaritySearch → PgVectorStore 调用）
call(EmbeddingRequest) → 根据 EmbeddingOptions 决定（直接调用时使用）
```

### 改动范围

#### 1. `DashScopeEmbeddingProperties` — 新增配置项

```yaml
spring.ai.dashscope.embedding:
  text-type: auto          # auto | query | document | disabled
                            # auto: 自动根据 embed() 方法类型判断
                            # disabled: 不传 text_type
  instruct: ""             # 自定义任务指令（仅 text_type=query 时生效），空字符串=不传
```

#### 2. `DashScopeEmbeddingOptions` — 新增 DTO

新增 `DashScopeEmbeddingOptions` record，实现 Spring AI 的 `EmbeddingOptions` 接口：
- `textType` (TextType enum: QUERY, DOCUMENT, DISABLED)
- `instruct` (String)

用于直接调用 `call(EmbeddingRequest)` 时传入自定义参数。

#### 3. `DashScopeEmbeddingApi.Request` — 新增字段

在 HTTP 请求 DTO 中新增：
- `text_type` (String, 可选) — 仅 DashScope 原生 API 支持
- `instruct` (String, 可选) — 仅 DashScope 原生 API 支持

**重要**：text_type 和 instruct 仅在 DashScope 原生 API 中支持，OpenAI 兼容接口不支持。需要将 API 端点从 `/compatible-mode/v1/embeddings` 迁移到 DashScope 原生 API：`/api/v1/services/embeddings/text-embedding/text-embedding`，请求体格式也需要相应调整。

#### 4. `DashScopeEmbeddingModel` — 核心改造

- 新增 `TextType` 内部枚举 (QUERY, DOCUMENT, DISABLED)
- `embed(Document)` → 传入 text_type=document
- `embed(String)` → 传入 text_type=query + instruct（如果配置了）
- `call(EmbeddingRequest)` → 检查 EmbeddingOptions，如果包含 DashScopeEmbeddingOptions 则使用其参数
- API 调用迁移到 DashScope 原生端点（兼容 instruct 和 text_type 参数）

#### 5. 配置文件更新

`application-dev.yml` 新增：
```yaml
spring.ai.dashscope.embedding:
  text-type: auto
  instruct: "Given a user question, retrieve the most relevant document passages"
```

### API 端点迁移

从 OpenAI 兼容接口迁移到 DashScope 原生 API：

| 项目 | OpenAI 兼容 (当前) | DashScope 原生 (目标) |
|------|-------------------|----------------------|
| Endpoint | `/compatible-mode/v1/embeddings` | `/api/v1/services/embeddings/text-embedding/text-embedding` |
| Base URL | `https://dashscope.aliyuncs.com` | `https://dashscope.aliyuncs.com` |
| Auth Header | `Authorization: Bearer <key>` | `Authorization: Bearer <key>` |
| Request Body | `{ model, input, dimensions }` | `{ model, input: { texts: [...] }, parameters: { dimension, text_type, output_type } }` |
| Response Body | `{ data: [{ embedding, index }] }` | `{ output: { embeddings: [{ embedding, text_index }] } }` |

### 不改动的部分

- sparse 向量（output_type）：保持 dense-only
- Spring AI Alibaba starter：继续使用手写实现
- VectorStore / HybridDocumentRetriever 等上层组件：无需改动
- 维度保持 1024 不变

## 验收标准

1. 入库时 embedding 请求携带 `text_type=document`
2. 查询时 embedding 请求携带 `text_type=query` + 配置的 `instruct`
3. 可通过配置 `text-type: disabled` 关闭 text_type 功能
4. 现有测试全部通过
5. 新增 DashScopeEmbeddingModel 的单元测试覆盖 text_type 场景

## 文件清单

| 文件 | 操作 |
|------|------|
| `rag/embedding/DashScopeEmbeddingProperties.java` | 修改：新增 textType、instruct 配置 |
| `rag/embedding/DashScopeEmbeddingApi.java` | 修改：适配 DashScope 原生 API 请求/响应格式 |
| `rag/embedding/DashScopeEmbeddingModel.java` | 修改：场景识别 + text_type/instruct 传递 + API 端点迁移 |
| `rag/embedding/TextType.java` | 新增：text_type 枚举 |
| `rag/embedding/DashScopeEmbeddingOptions.java` | 新增：EmbeddingOptions 扩展（可选，用于直接调用） |
| `application-dev.yml` | 修改：新增 text-type、instruct 配置 |
