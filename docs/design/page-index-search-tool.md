# PageIndex-like 结构化搜索 Tool 设计

> 模块：`com.smart.rag.rag.pageindex`、`com.smart.rag.agent.tool`
> 目标：Java 原生实现类似 PageIndex 的文档结构导航搜索能力，先作为额外 Tool 接入 Agentic RAG，不侵入现有 `hybridSearch` / `bm25Search` / `vectorSearch` 行为。

## 1. 背景

当前 Agentic RAG 已具备多种检索 Tool：

| Tool | 定位 |
|------|------|
| `hybridSearch` | 向量语义 + BM25 融合召回 |
| `vectorSearch` | 纯语义召回 |
| `bm25Search` | 关键词全文召回 |
| `rerank` | 对已召回文档精排 |
| `docDetail` | 按文档 ID 拉取详情 |
| `parentDocLookup` | 将片段扩展到父文档 |

这些 Tool 的共同特点是以 chunk 为主要召回单元。对于长 PDF、制度文档、报告、论文、手册等结构化长文档，用户问题经常需要先定位“在哪一章/哪几页/哪个段落”，再读取正文证据。PageIndex-like 搜索用于补足这类结构导航能力。

## 2. 与知识图谱的区别

PageIndex-like 搜索不是轻量知识图谱。两者的结构对象、问题类型和实现成本不同。

| 维度 | PageIndex-like 搜索 | 知识图谱 |
|------|---------------------|----------|
| 核心结构 | 文档目录树、章节层级、页范围、chunk 绑定 | 实体、关系、属性、三元组 |
| 最小单元 | section、page range、chunk | entity、relation、triple |
| 典型问题 | “这个问题应该去文档哪里看？” | “实体 A 和实体 B 有什么关系？” |
| 数据来源 | 标题、目录、页码、摘要、关键词、chunk | NER、关系抽取、schema、人工标注 |
| 查询方式 | 结构树搜索 + 节点匹配 + 正文下钻 | 实体链接 + 图遍历 + 关系推理 |
| 接入成本 | 较低，可作为 RAG 辅助索引 | 较高，需要图存储和关系抽取链路 |

本设计只实现“文档结构导航索引”，不做实体关系图谱。

## 3. 设计原则

- **额外 Tool 接入**：新增 `pageIndexSearch`，不修改现有搜索 Tool 的语义。
- **Java 原生实现**：不依赖 PageIndex 官方 Python 库或外部服务。
- **先确定性、后智能化**：V1 用数据库/文本匹配完成最小闭环；V2 再引入树下钻和 LLM 判断。
- **隔离优先**：所有查询必须沿用 `userId` / `teamId` 隔离。
- **可回退**：`pageIndexSearch` 找不到明确节点时，Agent 仍可使用现有 `hybridSearch` / `bm25Search`。
- **可观测**：Tool 输出需要包含命中节点、分数、匹配字段和后续建议动作。

## 4. 总体架构

```
文档上传 / ETL
      │
      ▼
文档切分与页码/章节信息
      │
      ▼
PageIndexBuildService
      │
      ▼
rag_page_index_node
      │
      ▼
PageIndexSearcher
      │
      ▼
PageIndexSearchTool
      │
      ▼
Agent ToolWorkspace + ToolResult
```

建议包结构：

```
src/main/java/com/smart/rag/rag/pageindex/
  PageIndexNode.java
  PageIndexSearchResult.java
  PageIndexRepository.java
  PageIndexSearcher.java
  PageIndexBuildService.java

src/main/java/com/smart/rag/agent/tool/
  PageIndexSearchTool.java

src/main/java/com/smart/rag/agent/tool/dto/
  PageIndexSearchRequest.java
```

## 5. V1：最小可用版本

### 5.1 目标

V1 目标是提供一个不侵入现有检索链路的结构化搜索 Tool：

```
pageIndexSearch(queryText)
```

它根据文档结构节点的 `title`、`summary`、`keywords` 匹配用户问题，返回最相关的章节/页范围，并可将关联 chunk 写入 `ToolWorkspace`，供后续 `docDetail` / `rerank` 使用。

### 5.2 非目标

V1 不做以下事情：

- 不替换 `hybridSearch`、`vectorSearch`、`bm25Search`。
- 不引入 PageIndex 官方库。
- 不做完整 LLM tree reasoning。
- 不自动从任意 PDF 版面恢复复杂目录。
- 不做实体抽取、关系抽取或知识图谱。
- 不强制改造现有上传/ETL 流程；可先支持手动或批处理写入索引节点。

### 5.3 数据模型

核心节点模型：

```java
public record PageIndexNode(
    Long id,
    String docId,
    Long parentId,
    Integer level,
    String title,
    String summary,
    List<String> keywords,
    Integer pageStart,
    Integer pageEnd,
    List<String> chunkIds,
    Long userId,
    Long teamId
) {}
```

建议数据库表：

```sql
create table rag_page_index_node (
    id bigserial primary key,
    doc_id varchar(128) not null,
    parent_id bigint null,
    level int not null default 0,
    title varchar(512) not null,
    summary text,
    keywords jsonb not null default '[]'::jsonb,
    page_start int,
    page_end int,
    chunk_ids jsonb not null default '[]'::jsonb,
    user_id bigint,
    team_id bigint,
    created_at timestamp not null default now(),
    updated_at timestamp not null default now()
);
```

建议索引：

```sql
create index idx_page_index_doc on rag_page_index_node(doc_id);
create index idx_page_index_parent on rag_page_index_node(parent_id);
create index idx_page_index_user on rag_page_index_node(user_id);
create index idx_page_index_team on rag_page_index_node(team_id);
```

如需 PostgreSQL 全文检索，可在后续迁移中增加 `tsvector` 生成列或表达式索引。

### 5.4 Tool 输入

```java
public record PageIndexSearchRequest(
    String queryText,
    Integer topK,
    Integer maxDepth,
    Boolean includeChunks
) {}
```

默认值建议：

| 字段 | 默认值 | 说明 |
|------|--------|------|
| `topK` | 5 | 返回节点数 |
| `maxDepth` | 3 | 最大搜索层级 |
| `includeChunks` | true | 是否把关联 chunk 写入 workspace |

### 5.5 Tool 输出

Tool 继续使用现有 `ToolResult` JSON 格式。`data` 中返回结构化节点结果：

```json
[
  {
    "nodeId": "123",
    "docId": "doc-001",
    "title": "权限审批流程",
    "summary": "本节描述审批角色、审批节点、驳回与重新提交规则。",
    "pageStart": 12,
    "pageEnd": 16,
    "level": 2,
    "score": 0.82,
    "matchedBy": ["title", "summary"],
    "chunkIds": ["chunk-11", "chunk-12"],
    "reason": "标题和摘要均命中权限审批流程相关内容",
    "nextActions": ["docDetail", "rerank"]
  }
]
```

### 5.6 搜索流程

```
1. 校验 queryText 非空
2. 使用现有 QueryNormalizer 清洗查询
3. 从 ToolWorkspace 获取 userId/teamId 隔离条件
4. PageIndexSearcher 查询 title/summary/keywords
5. 计算节点分数
6. 优先返回更具体的低层级节点
7. 如果 includeChunks=true，根据 chunkIds 构造 RetrievedDocument 写入 workspace
8. 返回 ToolResult JSON
```

V1 打分公式可保持简单：

```
score =
  titleMatch * 3
+ keywordMatch * 2
+ summaryMatch * 1
+ specificityBonus
- broadPageRangePenalty
```

字段说明：

| 字段 | 说明 |
|------|------|
| `titleMatch` | 标题命中权重最高 |
| `keywordMatch` | 关键词命中权重次之 |
| `summaryMatch` | 摘要命中权重最低 |
| `specificityBonus` | `level` 越深，节点越具体，适当加分 |
| `broadPageRangePenalty` | 页范围过大时降权，避免返回过粗节点 |

### 5.7 Agent 接入方式

V1 只新增 Tool，不改变现有 Tool 行为。

在 `AgentToolCallbackFactory.buildDeepRetrievalToolSet()` 中额外暴露：

```
pageIndexSearch
```

描述建议：

```
结构化文档导航搜索，适用于长文档、制度、手册、报告、PDF 页码或章节定位。输入 JSON: {"queryText": "...", "topK": 5, "maxDepth": 3, "includeChunks": true}
```

使用建议：

| 场景 | 首选 Tool |
|------|-----------|
| 长文档、章节、页码、制度条款定位 | `pageIndexSearch` |
| 精确关键词 | `bm25Search` |
| 概念性语义问题 | `hybridSearch` / `vectorSearch` |
| 已有候选后需要正文 | `docDetail` |
| 已有候选后需要排序 | `rerank` |

### 5.8 V1 验收标准

- `pageIndexSearch` 可作为独立 Tool 被 Agent 调用。
- 查询必须按 `userId` / `teamId` 做隔离。
- 空查询返回结构化失败结果，不抛运行时异常。
- 命中节点返回标题、摘要、页码范围、层级、分数、关联 chunkIds。
- `includeChunks=true` 时，命中 chunk 可写入 `ToolWorkspace`。
- 现有 `hybridSearch` / `bm25Search` / `vectorSearch` 行为不变。

## 6. V2：树搜索与智能下钻版本

### 6.1 目标

V2 在 V1 的确定性节点检索基础上，引入更接近 PageIndex 的树搜索流程：

```
从 root / 高层节点开始
  -> 判断哪些 child 与问题相关
  -> 递归下钻到更具体节点
  -> 返回最小相关章节/页范围
  -> 再拉取正文证据
```

V2 的目标不是让 LLM 读取整棵树，而是让 LLM 在每一层只判断少量候选子节点。

### 6.2 新增能力

| 能力 | 说明 |
|------|------|
| 父子节点递归下钻 | 从高层节点逐层缩小范围 |
| 节点摘要自动生成 | ETL 阶段为节点生成 summary/keywords |
| `pageNodeDetail` | 按 nodeId 拉取节点正文、子节点、关联 chunks |
| 混合候选生成 | 先用 BM25/规则筛 child，再交给 LLM 判断 |
| 搜索轨迹返回 | 输出每层选择了哪些节点、为什么继续/停止 |

### 6.3 V2 推荐 Tool

保留 V1 的 `pageIndexSearch`，新增：

```
pageNodeDetail(nodeIds)
```

职责划分：

| Tool | 职责 |
|------|------|
| `pageIndexSearch` | 搜索结构树并返回相关节点 |
| `pageNodeDetail` | 根据 nodeId 拉取节点正文、子节点摘要和关联 chunk |

### 6.4 V2 搜索流程

```
1. queryText 清洗与隔离条件解析
2. 选择 root 候选：
   - 指定 docId 时从该文档 root 开始
   - 未指定 docId 时先召回 top 文档 root
3. 对每个当前节点加载 child summaries
4. 使用确定性规则筛出少量 child candidates
5. 可选调用 LLM 判断 child relevance
6. 相关 child 继续下钻
7. 到达 maxDepth、页范围足够小或置信度足够高时停止
8. 返回 final nodes + search trace
9. 根据 includeChunks 决定是否写入 workspace
```

V2 输出增加搜索轨迹：

```json
{
  "finalNodes": [
    {
      "nodeId": "123",
      "title": "权限审批流程",
      "pageStart": 12,
      "pageEnd": 16,
      "score": 0.88
    }
  ],
  "trace": [
    {
      "depth": 0,
      "selectedNodeId": "10",
      "reason": "根节点摘要覆盖权限管理"
    },
    {
      "depth": 1,
      "selectedNodeId": "45",
      "reason": "子章节标题命中审批流程"
    }
  ]
}
```

### 6.5 V2 节点生成策略

V2 可在 ETL 阶段补全节点质量：

| 字段 | 生成方式 |
|------|----------|
| `title` | 目录、Markdown 标题、PDF heading、启发式段首 |
| `summary` | 节点下 chunk 摘要，可用模型生成 |
| `keywords` | 关键词抽取或模型生成 |
| `pageStart/pageEnd` | PDF 解析或上传解析结果 |
| `chunkIds` | 根据页码/章节归属绑定 |

节点生成需要支持失败降级：

- 没有目录时，按页范围生成伪节点。
- 没有页码时，按 chunk 序号生成逻辑范围。
- 摘要生成失败时，保留 title + chunk 前若干字符作为摘要。

### 6.6 V2 非目标

V2 仍不做：

- 全量知识图谱。
- 跨文档实体关系推理。
- 对所有文档强制生成完美目录。
- 每次查询把整棵树塞给模型。

### 6.7 V2 验收标准

- 支持从高层节点向子节点递归下钻。
- 每一层候选数量可配置，避免上下文失控。
- Tool 输出包含搜索轨迹和停止原因。
- `pageNodeDetail` 能按 nodeId 拉正文证据。
- 摘要/关键词生成失败时有降级路径。
- V1 的确定性搜索仍可作为 fallback。

## 7. 风险与约束

| 风险 | 影响 | 缓解 |
|------|------|------|
| 节点摘要质量差 | 搜索误召回或漏召回 | V1 允许 title/keywords 命中；V2 增加摘要质量评估 |
| 页码信息缺失 | 无法返回真实页范围 | 降级为 chunk 序号或逻辑范围 |
| 节点过粗 | 返回内容仍然太大 | 使用页范围惩罚和 level 加分 |
| 节点过细 | 搜索碎片化 | 父节点保留 summary，必要时返回父子组合 |
| LLM 下钻成本高 | 查询延迟增加 | 每层先规则筛选，只给少量 child candidates |
| 与现有检索重复 | Agent 选择混乱 | Tool 描述明确限定“长文档结构定位”场景 |

## 8. 推荐实施顺序

1. 新增设计表和领域模型。
2. 实现 `PageIndexRepository` 和确定性 `PageIndexSearcher`。
3. 实现 `PageIndexSearchTool`，沿用 `ToolResult` 和 `ToolWorkspace`。
4. 在 Deep Retrieval ToolSet 中额外暴露 `pageIndexSearch`。
5. 为 V1 增加单元测试：空查询、隔离条件、节点打分、workspace 写入。
6. 手动准备一份节点数据验证 Agent 调用链路。
7. V2 再增加树下钻、`pageNodeDetail`、摘要生成和搜索轨迹。

