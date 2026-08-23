# LLM 语义缓存设计（自建复刻 Redis LangCache）

> **目标**：在 smart-rag 中为 LLM 问答引入语义缓存——对"语义等价"的重复问题直接返回缓存答案，降低 LLM 调用成本（预期 FAQ 类查询节省 30%~90% token 开销）并大幅缩短 P95 延迟。方案为**自建复刻 LangCache 能力**，不引入 LangCache 服务本身。
>
> **核心决策**（论证见正文）：
> 1. **不引入 LangCache**：它是 Redis Cloud 托管专属服务，无开源版、无自托管版，与本项目全自托管 + 国内模型供应商架构冲突（§1.3）。
> 2. **引擎选 Vector Set**（`VADD`/`VSIM` + 属性过滤），备选 Query Engine（`FT.SEARCH` KNN），存储引擎抽象可切换（§4）。
> 3. **Redis 升级 `8.2.6-bookworm` → `8.8.2-alpine`**：Vector Set 在 8.8 已转正（10 亿级向量规模宣称），含 RDB 加载校验、use-after-free 等持久化相关修复，两项向量引擎已在本机 8.8.2-alpine 容器实测通过（§1.4、§3.1）。
> 4. **拦截点在 `ChatServiceImpl` 入口层**（`chat`/`chatStream`），不在 ChatModel 装饰器或 Advisor 链——缓存键必须基于**用户问题本身**（有历史时基于富化改写后的问题），一旦混入 RAG 检索上下文命中率会趋近于零（§6.1）。
> 5. **三种模式全量覆盖，键策略分两档**：无历史（SIMPLE / 首问）用原始问题做键走快路径；有历史（MULTI_TURN / AGENT）先做**富化改写**（脱语境 + 补全为自包含问题）再以富化产物做键——跨对话复用率高、同句异境在键生成处根除（§5.3）。AGENT 另加**只读工具守卫**（轨迹含 MCP 外部工具调用或失败调用即拒绝写回，§5.4）。
> 6. **前置条件**：查询改写升级（[`query-rewrite-upgrade.md`](./query-rewrite-upgrade.md)，三策略：富化 / 回溯提示 / 分解）的 **R1（富化）是本设计 P2（有历史路径）的硬前置**；本设计 P1（无历史 exact）不依赖，可先行。
>
> **范围**：`chat` / `chat`-stream 主问答链路的**三种模式**（SIMPLE / MULTI_TURN / AGENT）最终答案缓存。**不含**：检索中间结果缓存（另一主题，见 `AGENTIC-RAG-OPTIMIZATIONS.md` 的 AgentCacheManager 草案）、意图识别/工具子集选择缓存（P3 可选）。
>
> **状态**：设计已定稿，待立项实施（P1–P3 分期见 §8）。
>
> **变更（2026-08-21，第 3 次）**：改写升级独立为前置子系统 [`query-rewrite-upgrade.md`](./query-rewrite-upgrade.md)（富化 / 回溯提示 / 分解三策略，单次调用统一编排）。本设计中"凝结改写"术语统一为**富化改写（enrichment）**，其实现职责移交该子系统；富化产物 `enrichedQuery` 是唯一缓存键来源，step-back / 分解产物不参与键；两文档分期对齐（R1 → P2 前置）。
>
> **变更（2026-08-21，第 2 次）**：MULTI_TURN/AGENT 的缓存键由"上下文窗口联合 embedding"改为**富化（脱语境）后查缓存**——核验发现现有查询改写模板 history-blind（§1.5），需升级为历史感知改写并前移到缓存点之前（miss 路径零边际调用）。原 contextBlock 机器（K 轮窗口/截断/模式阈值 delta）整体移除；G2 延迟目标按模式分桶。变更动机与对比论证见 §5.3。
>
> **变更（2026-08-21，第 1 次）**：缓存范围由"SIMPLE + 多轮首问"扩展为三种模式全覆盖，新增 Agent 写回守卫与 policyVersion 隔离。

---

## 1. 背景与动机

### 1.1 问题：重复问题重复计费、重复等待

smart-rag 当前每次问答的完整成本链路：

```
用户提问 → 查询改写(可选 LLM) → pgvector ANN + BM25 + RRF → rerank → LLM 生成
```

企业知识库场景（FAQ、产品文档、制度问答）存在大量**语义等价的重复提问**（"年假怎么申请" vs "我要请年假的流程是什么"）。当前实现中每一次都完整执行上述链路：LLM 生成 2–10 秒、按 token 计费。项目内**没有任何 LLM 响应缓存**（已核查：无 Spring Cache 抽象、无 LLM 缓存层；`AGENTIC-RAG-OPTIMIZATIONS.md` 中的 `AgentCacheManager` 仅为未实现的设计草稿）。

### 1.2 Redis 8 的能力变化使"Redis 即向量库"成立

- **Redis 8.0 起**：RediSearch 并入 OSS（`FT.CREATE`/`FT.SEARCH` KNN 无需 module 部署）；Vector Set 作为新数据类型引入（当时为 beta）。
- **Redis 8.8**：Vector Set 转正，官方宣称单实例支撑 10 亿级向量；Search 引擎引入 Rust 迭代器与向量热路径去虚化优化；新增 `VRANGE` 命令（元素确定性遍历）。
- 本项目已有依赖 **Redisson 3.52.0**（`RVectorSet` 自 3.48 引入、3.52 补齐 `VSIM`；`RSearch` 含 HNSW/FLAT 索引参数），**零新增客户端依赖**。

### 1.3 LangCache 调研结论：复刻，而非引入

| 核实事实 | 验证方式 |
|---|---|
| LangCache 是 Redis Cloud 全托管服务（public preview 起），**不支持 Redis Software 自托管** | 官方博客 / 文档（见 §11） |
| **无开源版**：`github.com/redis/langcache` 返回 404；官方仅提供 Python/Java 示例 demo | 本机直接访问验证 |
| Iris 是商业化"上下文引擎"平台捆绑包（RDI、Context Retriever、Agent Memory、LangCache、Search），部署路径仅 Cloud / 商业 Software | redis.io/iris |

对本项目的三个硬伤：

1. **数据出境**：用户问答对需经境外 SaaS。本项目全链路自托管，模型供应商为 DeepSeek / 智谱 / 百炼（全国内），引入外部 SaaS 缓存破坏数据边界。
2. **Embedding 受限**：LangCache 要求走其托管的 embedder 配置体系，无法干净复用本项目唯一的 `EmbeddingModel` Bean（百炼 `qwen3.7-text-embedding`，1536 维，中文相似度判断质量更优）。
3. **依赖收益比**：它换取的只是"省去约 300 行缓存代码"，代价是新增一个外部服务依赖。

**结论**：自建复刻其能力矩阵（对照表见 §5.9），存储引擎抽象为接口，未来若迁移 Redis Cloud 可平移到 LangCache REST。

### 1.4 实测记录（本机探针证据）

以下探针在**正在运行的** `smart-rag-redis`（redis:8.2.6-bookworm）与一次性容器 `redis:8.8.2-alpine` 上执行，全部通过（2026-08-21）：

```text
# Vector Set 写入（⚠ 官方语法：VALUES 子句在前，元素名在后——与直觉相反，踩坑点）
VADD zz:v VALUES 3 1.0 0.0 0.0 a                    → 1   （8.2.6 与 8.8.2 均成功）
VSETATTR zz:v a '{"tenant":"teamA","exp":4102444800000}'  → 1

# 相似检索 + 混合过滤（字符串等值 && 数值比较，一次服务端调用完成租户与过期判定）
VSIM zz:v VALUES 3 1.0 0.0 0.0
      FILTER '.tenant=="teamA" && .exp>1767000000000'
      WITHSCORES COUNT 1 EF 200                     → a / 1（余弦相似度）

# Query Engine（备选引擎）
FT.CREATE zz:idx ON HASH PREFIX 1 zz:h: SCHEMA c TEXT t TAG SORTABLE → OK
FT.SEARCH zz:idx '@t:{teamA}' NOCONTENT             → 命中

# 命令面对比
8.2.6：VADD VCARD VDIM VEMB VGETATTR VINFO VISMEMBER VLINKS VRANDMEMBER VREM VSETATTR VSIM
8.8.2：上述全部 + VRANGE（确定性遍历，用于过期清扫）
两个版本均无条目级 TTL 命令 → 本设计采用"外置 payload + 原生 EXPIRE"模式（§5.1）
```

Redisson 3.52.0 jar（本机 Maven 仓库）确认包含 `RVectorSet`（含 Async/Reactive/Rx 变体）与 `RSearch`（含 `HNSWVectorIndexParams`/`FlatVectorIndexParams`）。

### 1.5 与设计相关的代码事实（已逐一核验）

| 事实 | 位置 |
|---|---|
| 三种模式：SIMPLE（默认，无记忆）/ MULTI_TURN（记忆 + 思考）/ AGENT（意图 → 工具子集 → ReAct） | `ChatMode.java:15-18` |
| **Agent 模式也挂 `MessageChatMemoryAdvisor`**——即 Agent 同样是多轮会话，缓存键必须考虑历史 | `AgentModeStrategy.java:214` |
| **现有查询改写是 history-blind 的**：模板仅 `{query}` 输入，做检索词优化（去填充词/术语展开），**不做指代消解**——"它多少钱"改写后仍含指代；即当前多轮检索的召回也受指代影响。升级方案见前置子系统 [`query-rewrite-upgrade.md`](./query-rewrite-upgrade.md) | `QueryRewritePromptTemplates.java:19`（模板）、`RagConfig.java:32-52`（Transformer，模型可配 `query-rewrite-model`） |
| 改写发生在**检索内部**（缓存点之后）：`query-rewrite-enabled`（dev 为 true）时每次检索前改写 | `RagAdvisorFactory.retrieve()`（经 `ChatRetrievalService.retrieve` 调用，阻塞/流式两路均走） |
| Agent 内置 9 个工具全部实现 `RagTool`，全部只读（检索/回链/详情/知识库信息/改写/事件查询） | `agent/tool/*Tool.java` |
| **MCP 外部工具会注入 Agent 工具集**（`mcpToolCallbackAdapter.toCallbacksForAllServers`）——外部工具副作用不可静态判定 | `AgentToolCallbackFactory.java:101` |
| Agent 轨迹记录每次工具调用（`toolName`/`success`/迭代轮次），可用于**写回守卫** | `ToolCallRecord.java:20`、`AgentTrace.java:33` |
| 流式帧仅 `CONTENT` / `REASONING` 两种，回放协议简单 | `StreamFrame.java:15-17` |

---

## 2. 设计目标与非目标

### 2.1 目标

| # | 目标 | 度量 |
|---|---|---|
| G1 | FAQ/重复类查询命中缓存，LLM 零调用 | 命中率（按 `mode` × 团队分桶统计，P3 报表） |
| G2 | 命中路径 P95：无历史（SIMPLE/首问）< 200ms（含 embedding）；有历史（MULTI_TURN/AGENT，含富化改写）< 1.5s | micrometer Timer |
| G3 | 零错误缓存：语义不等价、**语境不等价**、副作用未隔离的问题不得命中 | 评测集错误命中率 ≈ 0（§7，含多轮/Agent 负样本） |
| G4 | 对现有链路零侵入可回退：开关关闭后行为与现状完全一致 | 回归测试 |
| G5 | 任一依赖故障（Redis / embedding / 富化改写）不影响问答可用性 | fail-open 混沌测试 |

### 2.2 非目标

- 不缓存检索中间结果（向量检索 / BM25 / rerank 结果缓存是另一个独立主题）。
- 不做跨团队共享缓存（隔离是硬约束，见 §5.4）。
- 不引入 LangCache / Redis Cloud / 新的独立缓存服务。
- 不缓存 Agent 的**意图识别/工具子集选择**结果（`AgentCacheManager` 草案的 intentCache 属另一优化层，P3 可选另行立项）。
- 改写子系统本体（三策略、模板、融合）不在本设计范围内——见 [`query-rewrite-upgrade.md`](./query-rewrite-upgrade.md)。

---

## 3. 基础设施决策

### 3.1 Redis 版本：升级到 8.8.2-alpine

**升级理由**（相对停留在 8.2.6）：

1. **Vector Set 转正**：8.0 beta → 8.8 官方宣称 10 亿级向量；近版本修复了 RDB 加载时节点校验缺失（越界访问）与 use-after-free——本项目 compose 开启 `appendonly everysec`，向量集数据落盘，这些修复直接相关。
2. **性能**：8.8 Search 引擎的 Rust 迭代器 + 向量热路径去虚化，对 VSIM 这种高频小查询收益直接。
3. **`VRANGE` 新命令**：过期清扫从 `VRANDMEMBER` 随机抽样升级为确定性分页遍历（§5.7）。
4. 顺带福利（与缓存无关，另行评估）：8.8 的 `XNACK` 可改进现有 Redis Streams 总线的 NACK/DLQ 语义。

**升级操作与兼容性**：

- 改动点仅两处镜像 tag：`docker-compose.yml:25`、`docker-compose.prod.yml:123`（`docker-compose.2c4g.yml` 的 redis 服务无 image 行，继承 base）。
- 同属 8.x 大版本，RDB/AOF 向后兼容：现有 Streams 消息、`chat:memory:*`、`auth:token:*` 等键直接加载，无迁移。
- 缓存命名空间（`llm:cache:*`）全新，不涉及数据兼容。
- 同步调整 maxmemory（§3.3）。

### 3.2 alpine 变体评估：可行

| 维度 | 结论 |
|---|---|
| 镜像 | 官方同时维护 debian/alpine 双变体；alpine 约 30MB（bookworm 约 140MB），已实测拉取并跑通全部探针 |
| 分配器 | Redis 官方镜像两种变体均**内置 jemalloc**，主分配器行为一致 |
| musl libc | 差异仅体现在 DNS 解析等边缘场景；本项目单机 compose、无副本、无外部域名依赖，无影响 |
| 运行环境 | WSL2（当前开发机）实测正常 |

### 3.3 内存预算与 noeviction 约束

**现状约束**：`maxmemory-policy noeviction` 必须保留（Streams 消息总线与聊天记忆不可被驱逐，compose 注释已明确警告），因此缓存内存必须**预算化 + 上限兜底**，不能依赖驱逐。

**预算估算**（Q8 量化，1536 维）：

- Vector Set 元素：约 1.6KB/条（向量）+ HNSW 图开销 ≈ 3–4KB/条
- 外置 payload HASH：平均 4–6KB/条（问题 + 富化问题 + 答案 + 引用快照）
- 合计约 10KB/条 → **2 万条 ≈ 200MB**

**建议配置**：

| 文件 | 现值 | 建议 |
|---|---|---|
| `docker-compose.yml`（dev） | 512mb | 768mb |
| `docker-compose.prod.yml` | 256mb | 512mb（按团队数评估） |
| `docker-compose.2c4g.yml` | 128mb | 保持 128mb，靠 `max-entries-per-team` 压缩（如 5000） |

**兜底机制**（代码侧，§6.5）：`VCARD` 超 `max-entries-per-team` 即跳过回填（只影响缓存写入，不影响问答）；所有缓存写操作 try-catch，Redis 异常 fail-open。

---

## 4. 引擎选型：Vector Set 为主，Query Engine 为备

| 维度 | Vector Set（选定） | Query Engine（`FT.SEARCH` KNN over HASH） |
|---|---|---|
| 接入成本 | 无 schema、无索引管理，`RVectorSet` 两个核心方法 | 需建索引 schema、FLOAT32 二进制序列化 |
| 隔离过滤 | `FILTER` 属性表达式，**字符串等值 + 数值比较**一次调用完成（§1.4 实测） | TAG/NUMERIC 子句，能力等价 |
| 内存 | 默认 Q8 量化，1536 维 ≈ 1.6KB/条 | 原生 FLOAT32 = 6KB/条 |
| 条目 TTL | 无原生 → "外置 payload + EXPIRE"模式解决（§5.1） | 原生 per-key EXPIRE |
| 团队级失效 | per-team 独立 key，`DEL` 即清（O(1) 语义清晰） | 需按 tag 扫描逐条删除 |
| 成熟度 | 8.0 beta → 8.8 转正 | 更成熟（LangCache/GPTCache/Spring AI RedisVectorStore 同模式） |

**选定理由**：接入成本、内存效率、团队失效语义三项占优；唯一短板（条目级 TTL）已被外置 payload 模式消解。

**退出路径**：存储引擎抽象为 `SemanticCacheEngine` 接口（§6.2）。若 Vector Set 在生产暴露稳定性问题，实现 `QueryEngineSemanticCache`（同一接口，索引 schema + TAG 过滤）即可整体切换，上层 `SemanticCacheService` 与接入点零改动。

---

## 5. 核心设计

### 5.1 数据模型

三个 Redis 键类型，职责分离：

```text
① llm:cache:vset:{teamId}          Vector Set
   element = entryId (UUID)
   attrs   = {"model":"deepseek-v4-flash",  // 候选模型隔离
              "mode":"SIMPLE|MULTI_TURN|AGENT",
              "policy":"p3f9a1",            // policyVersion 短 hash（§5.4）
              "exp": 1735488000000}         // 过期时刻 epoch ms（清扫与兜底过滤）

② llm:cache:entry:{entryId}        HASH（Redisson RBucket<CachedChatEntry>, JsonJacksonCodec）
   q          原始问题（审计/调试）
   qEnriched  富化改写后的问题（有历史时；无历史路径为空。审计 + shadow 分析用）
   a          最终答案 markdown（仅 CONTENT，见 §5.8）
   refs       引用快照 JSON（文档 id/title/score 元数据，展示层容错）
   model / mode / teamId / createdAt / negative(P3)
   EXPIRE ttl（默认 24h，原生过期）

③ llm:cache:exact:{teamId}:{sha256(teamId|mode|model|policy|normalize(keyText))}
   String（L1 精确缓存，值为 entryId 或压缩 JSON）
   keyText = q（无历史路径）或 qEnriched（有历史路径）
   EXPIRE exact-ttl（默认 72h）
```

设计要点：

- **payload 外置**（①只存向量键，②存答案并原生 TTL）：Vector Set 无条目级 TTL，答案体放 HASH 用 `EXPIRE` 原生过期；①中残留的 element 由"命中自愈"清除（§5.6）。
- **per-team 独立 Vector Set**：团队失效退化为一次 `DEL`；HNSW 图规模天然按团队分治；`VSIM` 过滤条件含 `model`/`policy`/`exp`（§5.2）。
- **exact key 带 `{teamId}` 前缀**：团队失效时 `SCAN MATCH llm:cache:exact:{teamId}:*` 可批量清除；纯 hash 键无法按团队定位。
- **富化结果非确定性 → exact 碎片化**：同输入两次富化可能字面略异，exact 命中率在多轮路径打折——exact 是 L1 加速器而非正确性机制，语义 VSIM 兜底，可接受（§5.3）。
- **`normalize()`**：NFKC 归一 + 空白折叠 + trim（对齐 LangCache 的 unicode normalization 细节；中文场景不做大小写折叠收益有限，保留原文语义）。

### 5.2 查询路径（读）

```
ChatServiceImpl.chat / chatStream 入口
  │
  ├─ EligibilityGate（§5.4）──── 不满足 → bypass（计数后走原链路）
  │
  ├─ 构造缓存键输入：
  │     无历史（SIMPLE / 会话消息数 == 0）→ keyText = query            （快路径，零 LLM 前置调用）
  │     有历史（MULTI_TURN / AGENT）      → keyText = enrich(query, 最近K轮历史)   （§5.3，
  │                                          由前置子系统 UnifiedRewriteTransformer 产出）
  │
  ├─ L1 精确：GET llm:cache:exact:{team}:{hash(keyText)}    ~1ms，命中省掉 embedding 调用
  │     命中 → 读 entry payload（校验 model/policy 匹配）→ 返回/回放
  │
  ├─ L2 语义：embeddingModel.embed(keyText)                  复用唯一 EmbeddingModel Bean
  │     （百炼 qwen3.7-text-embedding, 1536 维, 50–150ms，超时预算 300ms）
  │
  │     VSIM llm:cache:vset:{team} VALUES 1536 f1...f1536
  │          FILTER '.model=="{candidateId}" && .policy=="{pv}"
  │                 && .mode=="{mode}" && .exp>{now}'
  │          WITHSCORES COUNT {top-k=3} EF {200}
  │
  │     top1 score ≥ similarity-threshold（三模式统一，默认 0.95）？
  │       命中 → HGETALL entry payload
  │                payload 已过期 → VREM 自愈 + 视为 miss
  │       未命中/低于阈值 → miss
  │
  └─ miss → 原链路（候选解析 / Fallback / RAG / advisor / ReAct 全不动，
           有历史路径将 RewriteResult 整体传递给检索并跳过内层改写，§5.3）
           → 异步回填（§5.5）
```

延迟预算：无历史 L1 ≈ 1–5ms，L2 ≈ 60–150ms → **P95 < 200ms**；有历史 = 统一改写调用（0.3–0.8s，flash 级候选，三策略一次产出）+ embedding + VSIM → **P95 < 1.5s**（G2 分桶）。相比全链路 2–10s 仍为数倍收益。

### 5.3 缓存键策略：无历史快路径 + 有历史富化改写

**核心矛盾**：多轮追问（"它多少钱"）的语义取决于对话语境。只用原句做键，"产品 A 对话中的它多少钱"会错配给"产品 B 对话"——**同句异境**是 G3 最危险的场景。

**选定方案**（第 2 次变更确立，第 3 次变更移交实现职责）：

| 路径 | 缓存键输入 keyText | 说明 |
|---|---|---|
| **无历史**（SIMPLE；MULTI_TURN/AGENT 首问） | `query` 原句 | 无语境依赖，原句即完整语义；快路径不付任何 LLM 前置调用（FAQ 主场景，命中率与延迟双优） |
| **有历史**（MULTI_TURN / AGENT） | `RewriteResult.enrichedQuery` 富化后问题 | 脱语境 + 补全后的自包含规范问题（"它多少钱"+iPhone 语境 → "iPhone 17 价格"） |

**富化改写（enrichment）由前置子系统提供**（实现细节全部见 [`query-rewrite-upgrade.md`](./query-rewrite-upgrade.md)，本节只列缓存视角的契约）：

- 富化是统一改写编排（富化 / 回溯提示 / 分解三策略）的产物之一，**单次 LLM 调用**与 step-back/分解产物一并产出——缓存只消费 `enrichedQuery`，其余产物（`stepBackQuery`/`subQueries`）不参与键，miss 时随 `RewriteResult` 传递给检索复用。
- 输入 = 当前问题 + 最近 K 轮历史（`history-rounds`，默认 5）；模型复用 `query-rewrite-model`（flash 级）。
- 质量门槛：富化指代消解正确率 ≥ 95%（前置子系统 R1 的 AC）是启用有历史路径缓存的前提——富化质量是多轮缓存正确性的上限。

**改写前移与结果传递（miss 路径零边际调用的关键）**：

现状改写发生在检索内部（`RagAdvisorFactory.retrieve()`，§1.5），位于缓存点之后。变更为：有历史时统一改写调用**前移到缓存点之前**；miss 后 `RewriteResult` **整体**向下传递——主查询与附加查询（step-back/分解）全部复用，检索跳过内层改写。`query-rewrite-enabled: true`（现状）下 miss 路径的 LLM 调用数**不变**（同一次调用换了位置）；仅命中路径付改写成本。无历史路径不动。

**为什么优于上下文联合键**（第 2 次变更淘汰的方案）：

1. **命中率**：富化键是规范自包含问题——任何对话里的"那退款呢"都富化为"如何申请退款"，指向同一条缓存，跨对话复用；联合键因每段对话历史不同而碎片化。
2. **正确性**：指代由 LLM **显式消解进键文本**；联合键靠"历史窗口 embedding 相似"做代理判断，语境相近但关键指代不同的对话仍可能撞上。富化即使出错，与基线管线**同源同错**（检索同样用错的问题），缓存不引入新错误类别。
3. **附加修复**：现状多轮检索本身 history-blind（原句含指代直接进检索，§1.5），改写前移后 miss 路径检索同样拿到脱语境问题——**超出缓存本身的多轮检索质量修复**（前置子系统 R1 的直接收益）。
4. **简化**：contextBlock 机器（K 轮窗口拼接/截断/模式阈值 delta）整体移除，三模式阈值统一。

**代价（明示）**：

1. 有历史命中路径 = 统一改写（0.3–0.8s）+ embedding（~0.1s）≈ 0.5–1s，G2 按模式分桶（§2.1）。
2. 富化非确定性 → exact 碎片化（§5.1 已述，语义兜底）。
3. policyVersion 输入需纳入统一改写模板版本（§5.4）。
4. **降级**：富化失败/超时/策略关闭（`app.rag.query-rewrite.strategies.enrichment=false`）→ 该查询**不查缓存**（bypass，宁可不命中，不可错命中）；无联合键回退（机器已移除）。富化关闭等价于"有历史不缓存"。

### 5.4 Eligibility 准入门与 Agent 写回守卫

**读取准入门**（决定是否查缓存）：

| 规则 | 说明 |
|---|---|
| 模式开关 | `eligible-modes` 默认全三种；支持按模式独立开关（`agent.enabled` 等独立于全局） |
| 有历史路径依赖富化可用 | `strategies.enrichment=false` 或富化调用失败 → 有历史查询 bypass（§5.3） |
| BYOK 用户客户端不缓存 | 用户自带 Key 的模型响应质量与计费主体不同，不入团队共享缓存 |
| 全局开关 + 团队级开关 + 管理端 bypass | 灰度与应急关闭 |

**写回守卫**（决定是否值得缓存，三模式共通部分）：

| 规则 | 说明 |
|---|---|
| guardrail / 内容过滤拦截的响应不缓存 | 避免缓存被拦截内容 |
| fallback 链中途切换模型（`fallbackRef` 有值）不回填 | 答案来自非首选候选，不满足缓存键 `model` 语义 |
| canceled / error 流不回填 | 答案不完整 |
| `VCARD` 超 `max-entries-per-team` 跳过 | 容量兜底（§3.3） |

**Agent 专属写回守卫**（依据 `AgentTrace.toolCalls`，`ToolCallRecord` 含 `toolName`/`success`，§1.5）：

| 规则 | 说明 |
|---|---|
| **轨迹含 MCP 工具调用 → 拒绝缓存** | 内置 9 个 `RagTool` 全部只读可缓存；MCP 外部工具副作用不可静态判定（发邮件、建工单等），默认全部拒绝。P3 经 `McpToolAdminService` 加 per-tool 只读标注后可放行白名单 |
| **任一工具调用 `success == false` → 拒绝缓存** | 工具失败的轨迹重放价值低（重试可能走不同路径、答案可靠性存疑） |

**policyVersion 隔离**（Agent 必需，三模式统一实现）：

Agent 答案由"系统 Prompt + guardrail 规则 + 工具注册表"共同决定——命中路径完全跳过 advisor 链（guardrail 不再执行），必须保证缓存条目产生时的策略与当前一致；统一改写模板版本同样影响键语义，一并纳入：

```
policyVersion = sha256(系统Prompt模板 + guardrail 规则集 + Agent 候选工具名集合排序签名 + 统一改写模板)[:6]
```

- 写入时记入 attrs `policy`，查询时 `VSIM FILTER '.policy=="{pv}"'`、exact hash 输入含 `policy`。
- 任一要素变更（Prompt 模板更新、guardrail 规则调整、工具集增删——含 MCP server 启停、统一改写模板迭代）→ policyVersion 变化 → 旧条目自动不再命中，自然过期淘汰。
- 工具注册表签名在每请求构建工具子集时已可得（`AgentToolCallbackFactory`），无需额外状态。

**缓存键作用域汇总**：`(teamId, mode, candidateId, policyVersion)`。团队内共享是预期行为（同一知识库与策略）；跨团队、跨模式、跨策略、跨模型严格隔离。

### 5.5 写回路径（异步，miss 后）

原则：**写回永远不阻塞、不影响主链路正确性**。

- 阻塞 `chat()`：响应返回后异步回填（虚拟线程执行器，参照 `ChatMessagePublisher` 的异步发布模式）。
- 流式 `chatStream()`：在外层 `doOnComplete`（`ChatServiceImpl.java:181` 已有先例）聚合最终 content 后回填；Agent 模式同点可取到 `AgentTrace`（经 `agentMetadataRef` 传递，实现时核验挂点）执行 §5.4 守卫。
- 回填内容：`keyText` 的 embedding（复用查询路径已算好的向量，零额外 embedding 调用）+ q/qEnriched + 答案 + 引用快照。
- 回填前逐条执行 §5.4 守卫矩阵，任何一条不满足 → 计数跳过（`llm_cache_store_skipped_total{reason=...}`）。

### 5.6 失效策略

| 触发 | 动作 | 时效 |
|---|---|---|
| TTL 自然过期 | entry payload 原生 `EXPIRE`；vset 残留 element 由命中自愈（payload 读空 → `VREM` + miss）+ 周期清扫 | 默认 24h |
| **知识库变更**（文档摄取完成 / 删除） | 订阅文档生命周期事件（消息总线 `MessageBus` SPI，ETL 已走 `rag_index_document` topic，`EtlDocumentConsumer.java:38`）→ `DEL llm:cache:vset:{teamId}` + `SCAN` 清 exact keys | 秒级——保证"文档更新后不吐旧答案" |
| **策略变更**（系统 Prompt / guardrail / 工具集 / 统一改写模板） | policyVersion 派生自要素 hash，键空间自动切换，旧条目不再命中、自然过期 | 即时 |
| 运维手动 | 管理 API：按团队 / 全量清除（P3） | 即时 |

### 5.7 过期清扫（低频兜底）

自愈只清"被再次查询"的 element，需周期任务回收长期不再命中的残留：

```
每小时 / 每团队：
  VRANGE llm:cache:vset:{team} {cursor} {cursor+500}   # 8.8 新命令，确定性分页
  → VGETATTR 逐条校验 .exp < now → VREM
```

客户端适配注意：Redisson 3.52（2025-09 发布）早于 8.8（2026 年），`RVectorSet` 可能未覆盖 `VRANGE`——实现时若缺失，降级为 `VRANDMEMBER` 抽样清扫或经 Redisson 原生命令通道发送，或升级 Redisson 版本。`VSIM` 的 `FILTER` 参数同理需在实现时核验 `RVectorSet.search` 的参数面（§6.6）。

### 5.8 流式回放（命中路径的 SSE 一致性）

命中缓存时前端体验必须与真实流一致：

- 将缓存答案按块切分，`Flux` 以 20–50ms 间隔逐帧回放（`StreamFrame` 仅 CONTENT/REASONING 两种，`StreamFrame.java:15-17`）。
- **只缓存与回放 CONTENT 帧**：REASONING（思考过程）是模型即时产物，不缓存不回放——命中时答案直接开始，前端将 reasoning 帧视为可选（实现时核验前端容错，见 §10）。
- Agent 模式的 workspace / agentMetadata / usage 尾帧在 cached-hit 路径**合成**：workspace 帧可缺省（前端容错核验），usage 尾帧按 cache-hit 语义合成（`event:usage`，token 记 0）。
- `StreamFrame` / `ChatResponse` DTO 增加 `cached: boolean` 字段（默认 false，向后兼容），前端可展示"来自缓存"标识。
- 会话记忆一致性：命中后仍经 `ChatMessagePublisher` 异步落会话记忆（含 user 与 assistant 消息），多轮上下文不断裂。
- 用量语义：命中不经过 `UsageRecordingChatModel`，LLM usage 记 0；单独记录 cache-hit 事件（含估算节省 token，供报表）。

### 5.9 LangCache 能力对照（复刻范围）

| LangCache 能力 | 本设计复刻方式 | 阶段 |
|---|---|---|
| 语义缓存（可配阈值） | VSIM score ≥ threshold，支持全局/团队级配置（三模式统一阈值） | P2 |
| 精确缓存 | L1 `llm:cache:exact:*` | P1 |
| TTL | entry 原生 `EXPIRE` + `exp` attr 过滤 + 清扫 | P1/P2 |
| 多租户 | per-team vset key + exact key 前缀 | P2 |
| 缓存失效 | 文档事件整队 `DEL` + policyVersion 键空间切换 | P3 |
| **多轮会话安全**（LangCache 未内建，本设计超出项） | 富化改写为键（指代显式消解，§5.3；前置子系统 R1） | P2 |
| **副作用隔离**（LangCache 未内建，本设计超出项） | Agent 只读工具守卫（MCP 拒绝 + 失败拒绝）+ policyVersion | P2/P3 |
| 负缓存（缓存"无法回答"） | entry.negative 标记，短 TTL，命中转检索增强路径 | P3 |
| GenAI Glossary（领域词扩展提升匹配） | 团队级同义词表，embedding 前拼接扩展词 | P3（可选） |
| 监控（hit/miss/延迟面板） | micrometer → Prometheus（§6.4） | P1 |
| REST API / SDK | 进程内 `SemanticCacheService` 接口 + 管理 API（stats/purge） | P3 |

---

## 6. 工程落点

### 6.1 接入位置：`ChatServiceImpl` 入口层，及其原因

**选定**：`ChatServiceImpl.chat()`（`ChatServiceImpl.java:113`）与 `chatStream()`（`:141`）在候选解析之前调用 `SemanticCacheService`。

**为什么不在 ChatModel 装饰器层**（`ChatModelAssembler`，装饰栈 `UsageRecordingChatModel(ChatModelAdapter(capable))`，`ChatModelAssembler.java:41-47`）：该层看到的 Prompt 已包含 `RagContextAdvisor`（ORDER=100，`RagContextAdvisor.java:37`）注入的 `<<REF>>` 检索上下文——以它做缓存键，任何检索扰动（文档新增、top-k 漂移）都会导致 miss，命中率趋近于零。**语义缓存必须以用户问题（有历史时以富化后问题）为键。**

**为什么不在 Advisor 链层**：advisor 链在 `FallbackExecutor` 的逐候选 lambda 内执行，命中请求会白白消耗一次 fallback 槽位与链路开销；且 advisor 层同样位于 RAG 上下文注入点之后。

**入口层的额外收益**：命中请求完全跳过候选解析、fallback 链、检索、advisor 链与 Agent ReAct 循环；usage 走独立 cache-hit 事件（§5.8）。

**改写前移的配套改动**：有历史路径的统一改写调用位于本层（缓存点之前），miss 后 `RewriteResult` 需向下传递至 `ChatRetrievalService.retrieve` → `RagAdvisorFactory.retrieve` 并跳过内层改写（§5.3；检索侧改造见前置子系统文档 §6.1）。

### 6.2 包结构与核心接口

```text
src/main/java/com/smart/rag/infrastructure/cache/semantic/
├── SemanticCacheService.java        # 门面：lookup / store / invalidateTeam / stats
├── SemanticCacheEngine.java         # 引擎 SPI：VectorSet 实现 + 预留 QueryEngine 实现
├── VectorSetSemanticCache.java      # Redisson RVectorSet 引擎实现
├── ExactCache.java                  # L1 精确缓存（RBucket）
├── AgentStoreGuard.java             # Agent 写回守卫（轨迹检查，§5.4）
├── PolicyVersionResolver.java       # policyVersion 计算（Prompt+guardrail+工具集+统一改写模板 hash）
├── SemanticCacheEligibility.java    # 准入门（§5.4）
├── SemanticCacheMetrics.java        # micrometer 指标
├── SemanticCacheSweeper.java        # 过期清扫（§5.7）
├── SemanticCacheProperties.java     # @ConfigurationProperties("app.llm.semantic-cache")
├── SemanticCacheAutoConfiguration.java
└── dto/CachedChatEntry.java         # payload 记录（q/qEnriched/a/refs/model/mode/...）

富化改写本体（UnifiedRewriteTransformer / RewriteResult / 模板）在 rag 侧：
src/main/java/com/smart/rag/rag/retrieval/rewrite/（见 query-rewrite-upgrade.md §6.1）
```

```java
public interface SemanticCacheService {
    /** 查询；null = miss。内部完成富化(有历史时，经前置子系统) → L1 → L2 两级查找与自愈。 */
    CachedChatHit lookup(SemanticCacheKey scope, ChatTurn turn);

    /** 异步回填（不阻塞调用方，内部限流 + 容量检查 + 守卫矩阵）。Agent 轨迹经 result 附带。 */
    void storeAsync(SemanticCacheKey scope, ChatTurn turn, ChatAnswer answer, @Nullable AgentTrace trace);

    /** 文档变更 / 运维清除。 */
    void invalidateTeam(long teamId);
}
```

### 6.3 配置项

沿用项目 `app.*` 惯例：

```yaml
app:
  llm:
    semantic-cache:
      enabled: false            # 总开关（默认关，灰度开启）
      mode: exact               # off | exact | semantic | shadow（shadow 仅记录不生效）
      similarity-threshold: 0.95   # 三模式统一（富化键与原句键同性质，§5.3）
      top-k: 3
      ef: 200
      ttl: 24h
      exact-ttl: 72h
      max-entries-per-team: 20000
      eligible-modes: [SIMPLE, MULTI_TURN, AGENT]
      embed-timeout: 300ms
      lookup-timeout: 1500ms    # 整体查找预算（含富化改写），超时 bypass
      agent:
        enabled: true           # Agent 模式缓存独立开关
        cache-read-only-tools-only: true
        read-only-mcp-tools: [] # P3：MCP 工具只读白名单（经 McpToolAdminService 标注）
      team-overrides:           # 团队级覆盖（灰度/应急）
        # 101: { enabled: true, similarity-threshold: 0.97 }

# 富化（脱语境）改写配置在 app.rag.query-rewrite.*（见 docs/design/query-rewrite-upgrade.md §6.2）；
# strategies.enrichment 关闭或调用失败时，本设计有历史路径自动 bypass（§5.4）
```

### 6.4 指标（micrometer → 现有 Prometheus）

| 指标 | 类型 | 标签 |
|---|---|---|
| `llm_cache_requests_total` | Counter | `result=hit_exact\|hit_semantic\|miss\|bypass\|error`、`mode`、`team` |
| `llm_cache_lookup_duration` | Timer | `stage=l1\|enrich\|embed\|vsim\|payload`、`mode` |
| `llm_cache_similarity_top1` | DistributionSummary | `mode`、`team`（shadow 模式的阈值调优数据源，§7） |
| `llm_cache_entries` | Gauge | `team`（VCARD 周期采样） |
| `llm_cache_store_skipped_total` | Counter | `reason=capacity\|fallback\|guardrail\|byok\|mcp_tool\|tool_failed\|enrich_failed`、`mode` |

### 6.5 容错与降级（fail-open 是硬约束，G5）

- **所有缓存读写 try-catch**：任何 Redis 异常 → 视为 miss / 跳过回填，主链路完全不受影响。
- **富化失败/超时**：该查询 bypass（宁可不命中，不可错命中）；miss 路径继续走原链路（含原有检索内改写，行为与现状一致）。
- **embedding 超时**（`embed-timeout` 300ms）：取消并 bypass——缓存查找不能比 LLM 直答慢得离谱。
- **整体查找预算**（`lookup-timeout`）：富化 + L1 + L2 总超时兜底。
- **写侧容量兜底**：`VCARD` 上限 + Redis `noeviction` 写失败（OOM command not allowed）静默降级。
- 命中路径的答案格式与真实路径完全一致（同一 DTO/流帧协议），前端无感。

### 6.6 客户端与管线适配风险（实现时核验清单）

1. `RVectorSet.search` 是否暴露 `FILTER` / `WITHSCORES` / `EF` 完整参数面；缺失则经 Redisson 原生命令通道发送 `VSIM`。
2. Redisson 3.52 对 `VRANGE`（8.8 新命令）的覆盖；缺失则 `VRANDMEMBER` 抽样兜底或升级 Redisson。
3. `VADD` 语法为 `VADD key VALUES <dim> <v...> <element>`（VALUES 在前，元素名在后）——与直觉相反，单测必须覆盖。
4. Vector Set attrs 为 JSON 字符串（`VSETATTR`），数值过滤比较在服务端完成（§1.4 实测），不在客户端做。
5. **改写前移的传递链路**：`ChatServiceImpl` → `ChatRetrievalService` → `RagAdvisorFactory.retrieve` 增加 `RewriteResult` 预改写入口并跳过内层 `rewriteQueryTransformer`——两处调用点（阻塞 `:101` / 流式 `:168`，`AbstractModeStrategy`）都要改，防止双重改写调用（详见前置子系统文档 §6.1）。
6. Agent 轨迹在流式完成点的可达性（`agentMetadataRef` → `AgentTrace`），实现时核验传递链路。

---

## 7. 阈值调优与评测

阈值（`similarity-threshold`，三模式统一）与富化质量是 G3 的两道闸门，**不拍脑袋，走评测**：

1. **数据集**（复用 evaluation 模块 ragas 对齐的 testset 生成）：
   - **无历史组**：语义等价对（应命中）× 语义相近但答案不同对（不应命中，如"年假天数" vs "年假申请流程"）。
   - **有历史组（验证富化 + 阈值）**：同境等价对——不同对话中语境等价的相似追问（应命中：都富化为同一规范问题）× **同句异境对**——同一句追问置于不同话题对话（不应命中：富化后为不同规范问题）。此组是 G3 的关键负样本。
   - **富化质量组**：指代消解正确率评测，**归属前置子系统**（`query-rewrite-upgrade.md` §7/R1，门槛 ≥95%），此处复用其结果作为有历史路径缓存的启用门槛。
   - **AGENT 组**：纯内置工具轨迹的等价问题对（应命中）× 涉及 MCP 工具或含失败调用的轨迹（应整体不缓存，验证守卫而非阈值）。
2. **shadow 模式**（`mode: shadow`）：只记录 `llm_cache_similarity_top1` 与 would-hit（含 mode 分桶），不实际返回缓存——生产流量上无风险采集。
3. **离线分析**：按阈值扫描（0.90–0.99 步进 0.01）画 命中率 × 错误命中率曲线，选错误率 ≈ 0 前提下命中率的拐点。预期中文 + qwen embedding 下最优区间在 0.93–0.97。
4. **分团队校准**：不同团队语料差异大，`team-overrides` 差异化阈值。
5. shadow 观察期 ≥ 1 周后再切 `mode: semantic`（建议先 SIMPLE，多轮与 Agent 待前置子系统 R1 富化质量达标后跟进）。

---

## 8. 分阶段实施计划

### P0：调研与设计（已完成）

本机能力实测（§1.4）、方案定稿（本文档）、引擎与版本决策。

### P1：基础设施 + 精确缓存（预计 1–2 天；不依赖前置子系统，可先行）

| # | 任务 | 产出 |
|---|---|---|
| 1.1 | compose 升级 `redis:8.8.2-alpine` + maxmemory 调整（§3.3） | 三个 compose 文件变更 |
| 1.2 | `infrastructure/cache/semantic/` 骨架：Properties / AutoConfig / Metrics / Eligibility / `SemanticCacheService` 接口 / `PolicyVersionResolver` | 可装配的空实现 |
| 1.3 | `ExactCache` + normalize 规则（NFKC + 空白折叠）；P1 仅无历史路径（raw query 键） | L1 通路 |
| 1.4 | `ChatServiceImpl.chat/chatStream` 接入（同步路径 + 异步回填 + 流式回放 + `cached` 字段） | 端到端 exact 缓存 |
| 1.5 | `AgentStoreGuard` 基础版（MCP 工具拒绝 + 失败调用拒绝） | Agent 写回安全 |
| 1.6 | Testcontainers（`redis:8.8.2-alpine`）集成测试 + fail-open 注入测试 | 测试 |

**AC**：exact 命中 P95 < 5ms；`enabled=false` 时行为与现状完全一致（回归全绿）；Redis 停机时问答正常（fail-open）；Agent 含 MCP 调用的轨迹零缓存写入。

### P2：富化接入 + 语义缓存 shadow + 阈值评测（预计 3–5 天；**前置：`query-rewrite-upgrade.md` R1 已验收**）

| # | 任务 | 产出 |
|---|---|---|
| 2.1 | 接入前置子系统 R1 产物：有历史路径在缓存点前调用 `UnifiedRewriteTransformer`，`RewriteResult` 前移传递（§6.6-5，含检索跳过内层改写） | 有历史键通路（miss 路径零边际调用） |
| 2.2 | `VectorSetSemanticCache`（VADD/VSIM/VSETATTR/VGETATTR + 客户端适配核验 §6.6） | L2 通路（raw 与富化键统一） |
| 2.3 | 命中自愈 + `SemanticCacheSweeper` | 残留回收 |
| 2.4 | shadow 模式（记录 would-hit + top1 score，按 mode 分桶，不生效） | 生产数据采集 |
| 2.5 | 阈值评测：四组 testset（§7，富化质量组引用 R1 结果）+ 扫描脚本 + 分模式报告 | 阈值建议值 + 多轮缓存启用门槛 |

**AC**：shadow 指标面板可用（按 mode 分桶）；富化指代消解正确率 ≥ 95%（R1 保障，此处复核引用）；"同句异境"负样本在建议阈值下错误命中 ≈ 0；**miss 路径 LLM 调用数与现状一致**（改写前移不产生双重调用，压测验证）；Redis/embedding/富化故障注入下 fail-open 验证通过。

### P3：全量启用 + 高级特性（预计 3–5 天）

| # | 任务 | 产出 |
|---|---|---|
| 3.1 | 生产切 `mode: semantic`（先 SIMPLE，多轮/Agent 按富化质量与 shadow 达标节奏跟进；按团队灰度） | 缓存生效 |
| 3.2 | 文档生命周期事件订阅 → 团队失效（§5.6） | 一致性保障 |
| 3.3 | 负缓存 +（可选）glossary 同义词扩展 | 长尾优化 |
| 3.4 | （可选）MCP 工具只读标注（`McpToolAdminService` 白名单）放行只读 MCP 轨迹 | Agent 命中面扩大 |
| 3.5 | 管理 API（stats / purge）+ 团队 × 模式命中率报表 | 运维面 |

**AC**：FAQ 类查询命中率 > 30%（SIMPLE）；多轮/Agent 命中率达到评测报告预期且错误命中 ≈ 0；文档更新后对应团队缓存 ≤ 1s 清空；policyVersion 要素变更后旧缓存零命中。

> 与前置子系统的分期对齐：`query-rewrite-upgrade.md` R1（富化）↔ 本设计 P2；R2/R3（step-back/分解融合）与本设计无依赖关系，可并行推进。

---

## 9. 风险与对策

| 风险 | 等级 | 对策 |
|---|---|---|
| **富化改写质量不足**（指代消解错 → 键错 → 错误命中） | 高 | 前置子系统 R1 富化质量门槛（≥95%）+ 幻觉率 ≈0 硬规则；错误与基线管线同源不放大；模板迭代走 policyVersion；`strategies.enrichment` 一键降级为"有历史不缓存" |
| **多轮"同句异境"错误命中**（"它多少钱"跨对话错配） | 高 | 富化键将指代显式消解进键文本（根除而非概率降低）；同句异境负样本组持续监控；命中答案带 `cached` 标识可追溯 |
| **MCP 外部工具副作用轨迹被缓存** | 高 | 默认全拒（§5.4 守卫）；只读白名单需显式标注；守卫逻辑单测矩阵覆盖 |
| 阈值过低 → 语义不等价问题错误命中（G3 失守） | 高 | 0.95–0.97 保守起步 + shadow 评测选阈值 + 团队级开关应急关闭 |
| 有历史命中路径延迟（统一改写 0.3–0.8s） | 中 | flash 级改写候选；G2 分桶目标（<1.5s）；改写耗时进 `lookup_duration` 面板监控 |
| Vector Set 生产稳定性（8.0 beta → 8.8 转正的演进期） | 中 | `SemanticCacheEngine` SPI 保留 QueryEngine 备选实现；缓存数据易失，引擎切换无迁移成本 |
| Redis 内存竞争（noeviction，与 Streams/记忆共池） | 中 | §3.3 预算 + `max-entries-per-team` 上限 + 写侧容量兜底 + `llm_cache_entries` Gauge 告警 |
| Agent 命中路径跳过 guardrail/advisor 链带来的策略漂移 | 中 | policyVersion 隔离（§5.4）：Prompt/guardrail/工具集/统一改写模板任一变更即切换键空间 |
| 改写前移的双重调用/漏传递（miss 路径退化） | 中 | §6.6-5 传递链路清单；P2 AC 明确"miss 路径 LLM 调用数与现状一致"压测验证 |
| Agent 思考帧（REASONING）与 workspace 帧在命中路径缺失的体验差异 | 低 | 只缓存 CONTENT；reasoning 前端本就可选；workspace 帧前端容错核验（§10） |
| 引用快照过期（文档删除后缓存答案仍引用） | 低 | refs 存元数据快照而非仅 id；文档删除事件触发整队失效；展示层容错 |
| BYOK 答案混入团队共享缓存 | 低 | Eligibility 排除 BYOK 客户端 |
| fallback 链中途换模型的答案入缓存 | 低 | 回填前检查 `fallbackRef`，有值不回填 |

---

## 10. 测试计划

| 层次 | 覆盖 |
|---|---|
| 单元测试 | normalize 规则（NFKC/空白/中英文）；Eligibility 判定矩阵（mode × 历史 × BYOK × guardrail × 富化可用性）；阈值比较；回填排除矩阵（fallback/cancel/error/mcp_tool/tool_failed/capacity/enrich_failed）；policyVersion 计算（Prompt/guardrail/工具集/统一改写模板任一变更即变） |
| 集成测试（Testcontainers `redis:8.8.2-alpine`） | VADD/VSIM 真实行为（含 **VALUES 语法坑** §6.6-3）；FILTER 过滤（model/policy/mode/exp）；TTL 过期 → 命中自愈 → VREM；清扫任务；团队失效（DEL + SCAN exact）；容量上限跳过；raw 键与富化键的等价/不等价场景 |
| 改写前移专项 | miss 路径改写调用次数 == 1（前置统一调用、内层跳过）；无历史路径内层改写行为不变；富化超时 → bypass 且原链路照常改写；阻塞/流式两路（`AbstractModeStrategy:101/:168`）均覆盖 |
| 流式测试 | 命中回放帧协议与真实流一致（帧序/尾帧 usage）；`cached` 字段；REASONING 帧缺失时前端协议兼容；Agent workspace 帧缺失容错；取消语义 |
| 混沌测试 | Redis 停机 / 命令异常 / embedding 超时 / 富化超时与失败 → fail-open，问答不受影响 |
| 评测 | §7 四组 testset 阈值扫描（富化质量组引用前置子系统 R1）；上线后错误命中抽样人审（每周，重点抽多轮命中样本） |

---

## 11. 参考资料

- Redis LangCache（托管服务，本设计的能力参照物）：https://redis.io/langcache/ ；公告 https://redis.io/blog/langcache-public-preview/ ；文档 https://redis.io/docs/latest/develop/ai/context-engine/langcache/
- Redis Iris 平台（LangCache 所属捆绑包）：https://redis.io/iris/
- Vector Sets 文档：https://redis.io/docs/latest/develop/data-types/vector-sets/
- Redis 8.8 What's New：https://redis.io/docs/latest/develop/whats-new/8-8/
- Redis 8.0 What's New（Vector Set beta 起源）：https://redis.io/docs/latest/develop/whats-new/8-0/
- Redisson CHANGELOG（RVectorSet 3.48 引入 / VSIM 3.52）：https://github.com/redisson/redisson/blob/master/CHANGELOG.md
- Redis OSS 仓库：https://github.com/redis/redis
- 本项目关联文档：**[`docs/design/query-rewrite-upgrade.md`](./query-rewrite-upgrade.md)（前置子系统：富化 / 回溯提示 / 分解）**、`docs/AGENTIC-RAG-OPTIMIZATIONS.md`（AgentCacheManager 草案）、`docs/REDISSON-INTEGRATION.md`（Redisson 集成决策先例）
