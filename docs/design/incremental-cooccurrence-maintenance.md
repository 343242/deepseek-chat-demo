# 实体共现图增量维护设计（V30）

> 目标：废除"每文档抽取后全量重投影共现图"（`deleteByScope` + `projectCooccurrence`，单条投影
> 语句实测可超 30s，是 Hikari 连接泄漏警告与 shutdown 阻塞的直接来源），改为**写路径增量递增 /
> 删除路径对称递减 / 每日 8:00 对账自愈**三支柱模型。
>
> 前提约束（用户已批准）：存量数据可清空重建，不做兼容迁移。
>
> 状态：设计定稿（经九轮缺陷审查修正，见 §11 审查修正记录），未实施。

---

## 1. 背景与动机

### 1.1 现状链路（要被替换的部分）

```
EntityExtractionService（每文档抽取完成后，EntityExtractionService.java:240-241）
  └─ EntityIndexService.recomputeWeakTieScores(userId, teamId)     ← 每「文档」触发「全 scope」重算
       ├─ deleteByScope            — 清空作用域全部共现边
       ├─ projectCooccurrence      — rag_chunk_entity 自连接重投影（长 SQL，实测可超 30s）
       └─ updateWeakTieScores      — O(邻居²) CTE 重算 weak_tie
  └─ CommunityDetectionJob.run(userId, teamId)                     — Leiden + bridge + clearStale
```

问题：

1. **长语句 + 非原子窗口**（第三轮审查修正定性：`recomputeWeakTieScores` 无事务包裹——
   EntityIndexService 及其调用链均无 @Transactional，三条语句各自自动提交，是"长语句"而非
   "长事务"）：scope 稍大时 `projectCooccurrence` 单条语句超 30s，占用连接超过 Hikari
   `leakDetectionThreshold`（dev 30s / stable 60s），触发 Apparent connection leak 警告
   （实为合法长查询，非真泄漏）；在途长语句同样阻塞优雅停机。且 `deleteByScope` 与
   `projectCooccurrence` 之间无事务边界，与并发写存在非原子窗口（现状靠投影幂等自收敛）。
2. **放大效应**：上传一个 10-chunk 文档也要重算整个 scope 的全部边和结构分，成本与文档大小无关、与 scope 大小线性相关。
3. **现状 bug**：两次重投影之间发生的文档删除若未触发清理（如 fastTrack 临时行、`rag_event` 桥接盲区），
   共现边与 `rag_chunk_entity` 实际状态漂移，直到下一次重投影才被冲掉。

### 1.2 设计不变式

> **co_count(a, b) ≡ |{ chunk ∈ scope : chunk 同时链接实体 a 与实体 b }|**

三支柱各自维护该不变式的方式：

| 支柱 | 时机 | 动作 | 性质 |
|------|------|------|------|
| 写路径增量 | `aggregateAndUpsert` 事务内 | 新落库链接 → 边 +delta | 强一致（同事务） |
| 删除路径递减 | `cleanupByDocumentId` 事务内 | 被删链接 → 边 -delta、清零边 | 强一致（同事务） |
| 每日对账 | 8:00 定时 | per-scope 只读漂移/孤儿探测 →（仅阳性时）锁内重投影；重链接检测（§6.2） | 自愈兜底（最终一致） |

支柱一（写路径）自身失败（重试耗尽、进程崩溃）时事务原子回滚，留下的是**链接缺失**——
重投影无法自愈，由对账的重链接检测兜底（§6.2）；支柱二（删除路径）失败时文档已逻辑删而
清理未执行，留下的是**链接/事件残留（僵尸）**——由对账的孤儿清扫与孤儿 EXISTS 探测兜底
（§6）。两条兜底通道各归其位（第八轮修正：原稿以支柱一情形概括了支柱二），三支柱对各自
失败路径全部闭环。

---

## 2. 数据库变更（V30__incremental_cooccurrence.sql）

`rag_chunk_entity` 增加权威文档归属列，删除路径不再依赖 `rag_event` 桥接
（现有代码注释已承认该桥接有盲区：fastTrack 临时行删除后反查会漏）。

```sql
-- ============================================================
-- V30: 共现图增量维护
-- 1) rag_chunk_entity 增加权威 document_id（删除路径直查，废除 rag_event 桥接）
-- 2) 存量数据清空重建（用户批准，不做兼容迁移）
-- ============================================================

-- 清空存量（V21 四张实体索引表；表间无外键，TRUNCATE 单事务原子）
TRUNCATE rag_chunk_entity, rag_event, rag_entity, rag_entity_cooccurrence;

-- 权威文档归属列
ALTER TABLE rag_chunk_entity ADD COLUMN document_id BIGINT NOT NULL DEFAULT 0;
ALTER TABLE rag_chunk_entity ALTER COLUMN document_id DROP DEFAULT;
CREATE INDEX idx_ce_document ON rag_chunk_entity (document_id);

-- 抽取完成标记（§6.2 重链接检测：NULL = 抽取未完成/未尝试，对账据此重触发；存量行 NULL 即
-- 首轮对账自动重建的驱动源）。rag_document 为应用自有表（MyBatis-Plus 管理），不涉 Spring AI 契约。
ALTER TABLE rag_document ADD COLUMN entity_extracted_at TIMESTAMPTZ NULL;
CREATE INDEX idx_doc_entity_extraction_pending ON rag_document (update_time)
    WHERE entity_extracted_at IS NULL AND deleted = 0;

-- Down SQL（Flyway 不执行，人工回滚用——V21 惯例；数据不恢复，回滚即接受清空重建）：
--   DROP INDEX IF EXISTS idx_doc_entity_extraction_pending;
--   ALTER TABLE rag_document DROP COLUMN IF EXISTS entity_extracted_at;
--   DROP INDEX IF EXISTS idx_ce_document;
--   ALTER TABLE rag_chunk_entity DROP COLUMN IF EXISTS document_id;
```

说明：

- `rag_entity_cooccurrence` 结构不变：`uk_cocur_scope_pair (user_id, COALESCE(team_id,-1), LEAST(a,b), GREATEST(a,b))`
  唯一表达式索引（V21:84-85）已满足增量 upsert 的 `ON CONFLICT` 定位需求。
- `rag_entity` 结构不变（`uk_entity_norm_user_team` 唯一索引继续承载实体 upsert 冲突判定）。
- TRUNCATE 后 `rag_entity`/`rag_event`/`rag_chunk_entity`/`rag_entity_cooccurrence` 四表为空
  （第八轮修正：原说明漏列 rag_entity_cooccurrence），已有文档需重跑 ETL 才会重建实体索引
  与事件层（`rag_event` 同时是 SAG 检索层 `expandChunks` 的输入，运维口径需标注其同样为空——
  第三轮审查补充）；这是用户明确批准的取舍（"数据随便删，不需要考虑兼容"）。
- **重建路径已内建（第四轮修正，替代原"人工重跑 ETL"的未定义操作）**：存量文档
  `entity_extracted_at` 均为 NULL，部署后首轮对账的重链接检测（§6.2）自动全量重抽——
  探测以 `rag_document` 为源（不在 TRUNCATE 清单内，与实体表空与否无关——第七轮修正后
  该承诺才真正成立）。
  运维口径：SAG/Path C 降级窗口 = 迁移 → 首轮对账完成（迁移前 6h 内更新过的文档因宽限期
  顺延至次日）；LLM 成本 = 全量文档重抽一次
  （可用 `reconcile.relink-limit` 分日消化）；反复失败的文档保持 NULL、每日重试直至成功。

---

## 3. 并发模型：scope 级 advisory 锁

### 3.1 锁定义

新增 `EntityCooccurrenceMapper.lockScope`：

```xml
<!-- 作用域级事务内 advisory 锁。key 表达式只此一处定义，全域一致性由构造保证。
     hashtextextended 冲突概率 ~2^-64/scope；即使冲突也仅导致无谓串行化，不影响正确性。
     契约（第四轮修正：由 Java 侧运行时断言固化，非仅 javadoc——文档契约不可执行）：
     必须在事务内、且在取任何行锁之前调用（R1）；lockScope / setLockTimeout 执行前断言
     TransactionSynchronizationManager.isActualTransactionActive()，自动提交下抛
     IllegalStateException 拒绝调用——pg_advisory_xact_lock 在自动提交下立即释放、
     静默失去串行化，必须代码级防呆而非依赖调用方自觉。调用点仅限三个持锁事务模板
     （写路径 / 删除路径 / 对账锁内重写）。 -->
<select id="lockScope">
    SELECT pg_advisory_xact_lock(
        hashtextextended(#{userId,jdbcType=BIGINT}::text || ':' ||
                         COALESCE(#{teamId,jdbcType=BIGINT}::text, '-1'), 0))
</select>

<!-- 持锁事务的锁等待上限（等价 SET LOCAL lock_timeout；set_config 第三参 true = 事务级）。
     必须在 lockScope 之前执行：既约束 advisory 队列等待，也约束事务内行锁等待。
     动机见 §3.5-3 与 §8-4：写事务排队/连接占用有上界（防池耗尽，dev 池仅 5）；
     路线三下死锁已构造性消除（§3.2.1），本超时兼作未来漏网语句的保险层。 -->
<select id="setLockTimeout">
    SELECT set_config('lock_timeout', #{millis}::text, true)
</select>
```

- `pg_advisory_xact_lock`：事务结束自动释放，无需手工 unlock，不会因异常遗留死锁锁。
- teamId 为 null 时归一为 `'-1'`，与既有 `COALESCE(team_id, -1)` 约定对齐。
- key 推导**必须且只需**存在于这一条 SQL——所有调用方传 `(userId, teamId)` 参数，不各自拼 key。

### 3.2 锁序规则（缺陷 1 修复核心）

> **R1**：凡对 scope S 的 `rag_entity` / `rag_chunk_entity` / `rag_entity_cooccurrence` 取行锁的事务，
> 第一个**取锁的**数据库操作必须是 `lockScope(S)`——lockScope 之前只允许不取任何锁的
> 语句（如 `setLockTimeout` 的 `set_config`，第八轮措辞修正：原"第一个数据库操作"与实际
> 首语句 setLockTimeout 的执行顺序字面冲突）。
>
> **R2**：不持 `lockScope` 的事务，禁止在持有上述表行锁之后再请求 `lockScope`。

满足 R1+R2 后，**advisory 锁参与的等待环**不可能形成：同 scope 的行锁竞争只发生在
advisory 队列内部（串行化，锁内行锁无竞争）；跨 scope 无共享行（实体/边表带
`(user_id, team_id)` 列直接隔离；链接表无 scope 列，但每行经写路径构造性归属唯一 scope
——chunk 属单一文档、链接仅关联同 scope 实体，第八轮口径修正：不宜表述为"按列分区"，
结论不变）。

**R1/R2 的能力边界（第三轮审查修正——原稿"不可能形成等待环"论断过强）**：R1/R2 消除的
只是 advisory 锁参与的环。持锁事务是**多语句、多批次**的行锁获取者（分批 upsert 按任意顺序
锁 rag_entity 行，随后锁边表行），而不取 advisory 的自动提交多行 UPDATE 同样在 rag_entity 上
按各自执行计划顺序取多行锁——两者可以互相持有对方想要的行锁，形成**纯行锁死锁**（advisory
锁只在其中一方手里，对打破该环无能为力）。第四轮定稿时此为残余风险、靠 §8-4 检测重试兜底；
**第五轮采纳路线三（混合形态）后该残余面构造性消除**，论证见 §3.2.1。

**行锁持有者全量盘点**（设计时核实，实施时以 GitNexus impact 复核；归属列为路线三终态）：

| 语句 | 位置 | 调用方 | 路线三归属（lockScope 内？） |
|------|------|--------|------------------------------|
| `upsertByNormUserTeam`（DO UPDATE） | EntityMapper.xml:7 | 写路径 | ✅ 写路径事务 |
| `recalculateDegree` | EntityMapper.xml:21 | 写/删路径 | ✅ 写/删路径事务 |
| `deleteOrphans` | EntityMapper.xml:30 | 删除路径 | ✅ 删除路径事务 |
| `markCommunityStale` | EntityMapper.xml:36 | 写路径（原事务外自动提交，EntityExtractionService:229）/ 删除路径 | ✅ 写路径调用**并入写事务末尾**（graphChanged 时）；删除路径本就在事务内（第五轮） |
| `updateEmbeddingBatch` | EntityMapper.xml:53 | EntityEmbeddingService:123 | ✅ 新增 advisory **短事务**（LLM 调用留在锁外，仅写回进锁；第五轮） |
| `batchUpdateCommunities` / `clearStaleFlag` | EntityMapper.xml:82/129 | derive 写回 | ✅ derive **写回事务**（计算在锁外分解，第五轮） |
| `updateBridgeScores` | EntityMapper.xml:100 | derive 链 | ✅ **退役分解**：内存计算（锁外）+ `updateBridgeBatch` 有序写回（锁内）（第五轮） |
| `updateWeakTieScores` | EntityCooccurrenceMapper.xml:58 | derive 链 | ✅ **退役分解**：`WeakTieScoreCalculator`（锁外）+ `updateWeakTieBatch` 有序写回（锁内）（第五轮） |
| `insertIgnore`（rag_event 单行） | EventMapper.xml:21 | 写路径（事务后逐条） | ➖ 单行自动提交，至多一行锁/语句，单锁不成环——白名单标注安全，不收编 |
| 边 upsert / 递减 / 清零（新增） | — | 写/删/reconcile 路径 | ✅ 是 |
| 对账阶段一（sweep / deleteByScope / projectCooccurrence） | — | 对账 | ✅ 是（漂移日） |

**明确保留 `upsertByNormUserTeam` 的 `ON CONFLICT DO UPDATE`**（审查否决过改 `DO NOTHING` 的方案，理由见 §11）：
description 跨文档追加合并（`description = rag_entity.description || '\n' || EXCLUDED.description`，
EntityMapper.xml:15）依赖 DO UPDATE 完成；advisory 串行化后其行锁是安全的。

### 3.2.1 零死锁构造（第五轮：路线三混合形态）

**收编原则**：三张实体索引表（`rag_entity` / `rag_chunk_entity` / `rag_entity_cooccurrence`）的
**全部多行写者**都在 `lockScope` 事务内执行——advisory 持锁者互斥，锁内行锁无竞争。轻重分界
不是语句多少，而是**持锁时长的上界参数**：

- **轻写者（SQL 内无计算，纯写）**：语句只做逐行写（无聚合/迭代计算），耗时上界 = 写入行数 ×
  常数，分两档——**文档局部写**：`updateEmbeddingBatch`、`markCommunityStale`，上界 = 该文档
  实体数（几十~几百行），毫秒~百毫秒级；**全 scope 平坦写回**：`batchUpdateCommunities`、
  `clearStaleFlag` 及分解出的 `updateWeakTieBatch`/`updateBridgeBatch`，上界 = O(scope) 行
  （derive 写回覆盖全 scope 实体，含图外孤立实体的 reset 语义，§6 阶段二），大 scope 下
  百毫秒~秒级。可进锁的依据是"无 SQL 内重计算"，而非"与 scope 无关"（第九轮口径修正：
  原稿四条统称"与 scope 全局大小无关"，与 §6 阶段二的全 scope 写回事实自相矛盾）。
- **重写者（SQL 内全局计算）**：语句自身含 O(邻居²)/全 scope 聚合计算——`updateWeakTieScores`
  （O(邻居²) CTE）、`updateBridgeScores`（全 scope 聚合）、Leiden 本身。**不能**进锁（大 scope
  会把临界区拖到分钟级），必须分解为 **read-compute-write**：锁外读快照（一次图加载）→ 纯内存
  计算 → 锁内按 id 序批量写回（O(scope) 行有序 UPDATE，毫秒~秒级）。Leiden 本就是此形态；
  `updateWeakTieScores` 拆为 `WeakTieScoreCalculator`（纯算法组件，语义逐字对齐原 CTE：
  degree<100 预算、Jaccard embeddedness、仅更新有邻居对的实体、hub/孤立实体不动、默认 0.5）；
  `updateBridgeScores` 拆为内存计算（按 Leiden 分区 + 邻接数邻居异社区数，覆盖全 scope 实体
  含图外孤立实体的 reset-0 语义）+ `updateBridgeBatch`。

**derive 写回的额外收益**：weak_tie / community / bridge / clearStale 收进**同一个写回事务**——
原"四步自动提交链（updateWeakTieScores → Leiden 写回 → bridge → clearStale）中途失败留
半代状态"的漂移根因（§6）被原子性消除；且三个分值共享同一次
图快照（原实现 weak_tie 与 Leiden 各读各的快照，存在跨快照混代）。

**无环论证**（四条，构造性）：

1. 每个事务至多取**一把** advisory 锁，且为第一个取锁的数据库操作（`set_config` 不取锁）→
   advisory 等待边只形成单键队列，不可能成环；
2. 三表行锁获取者全部持 advisory；同行冲突仅可能同 scope → 已被 advisory 串行化 →
   锁内行锁获取时无竞争（顺序无关紧要）；
3. 跨 scope 无共享行（实体/边表带 `(user_id, team_id)` 列直接隔离；链接表无 scope 列但
   构造性归属唯一 scope——chunk 属单一文档、链接仅关联同 scope 实体，与 §3.2 口径一致）；
4. `rag_event` 的自动提交写者为单行 INSERT（至多一行锁/语句）与 advisory 内的批量删除——
   单锁不成环。

→ waits-for 图无环，**死锁构造性消除**。前提是写者闭包不被未来代码打破——这正是静态审计
（下条）的职责。

**双重防线 + 静态审计（防纪律腐化——路线二"违规即破功且无人知晓"的解法）**：

- 防线一：多表临界区必须经 `ScopeLockTemplate`（新组件：断言 → setLockTimeout → lockScope →
  body 的统一封装；`LockRetryExecutor` 在其外层）；
- 防线二：derive/embedding 写回批次按 entityId 升序（纵深防御——即使未来出现一条漏网的无序
  自动提交写者，有序方仍不成环，违规需同时突破两道）；
- **静态审计测试**（`MapperWriteAuditTest` 单测）：扫描全部 mapper XML/注解，凡对三张表的
  多行写语句（foreach / UPDATE...FROM VALUES / DELETE...USING / CTE 多行）必须出现在
  `ScopeLockTemplate` 调用点白名单中，否则测试失败——把"新语句必须守规"从 code review 的
  人肉保证变为 CI 的机械检查。

### 3.3 快照必须在锁后（TOCTOU 修正）

所有用于计算增量的读快照（受影响实体列表、文档 pair 计数、既有链接）必须在 `lockScope` **之后**读取。
锁前的读只能用于确定"该锁哪个 scope"。理由：另一持锁事务（如 ETL 重投递本文档）可能在
快照与获锁之间提交新链接，按旧快照计算递减会产生漂移。普通 SELECT 不加锁，锁后读不会引入新的锁序问题。

### 3.4 纵深防御：边写排序分批

增量/递减边写入前按 `(entity_a, entity_b)` 排序分批。advisory 串行化后理论上不再必要，
但成本为零，且能防御未来有人引入非锁路径时的多语句行锁交叉。保留。
**路线三扩展（第五轮）**：derive / embedding 的写回批次同样按 entityId 升序——§3.2.1 双重
防线的第二道。

### 3.5 已知代价与限制

1. **同 scope 吞吐串行化**：同用户多文档并发上传时，实体写入事务在 advisory 队列中排队。
   单事务为秒级（分批 upsert + 链接 + 边 + degree，无长 SQL），可接受。路线三后队列成员
   扩大两类：embedding 写回短事务（毫秒~百毫秒级）、derive 写回短事务（O(scope) 行平坦写，
   大 scope 下百毫秒~秒级——第九轮口径修正，原"毫秒~百毫秒级"与 §6 阶段二的全 scope 写回
   矛盾）；队列总时长上界不变（仍由写/删/对账重写事务主导）。
   **批量上传已是现实路径（第六轮事实修正）**：`uploadBatch` 已存在且已暴露端点
   （DocumentController:65 → PersonalUploadStrategy:103 / TeamUploadStrategy:119——上传循环
   串行落库，但每文档独立登记 ETL dispatch → Redis 总线并发消费 → N 个 `extractAndIndex`
   在 etlIoExecutor 上并发 → 同 scope 的 N 个写事务排队），排队深度常态即达批量规模
   （dev：etlIo 池 core 4/max 8、consumer batch-size=5。第九轮事实修正：原引
   "consumer batch-size=20"有误——`etl.consumer.batch-size` 默认 5 且 dev/stable 无覆盖，
   dev yml 中的 batch-size: 20 是百炼 embedding 模型参数；排队深度实际由 etlIo 并发上限
   决定，结论不变）。排队者的连接占用与线程占用对策见 §3.6。
2. **残余死锁面（第五轮：已消除）**：第四轮定稿时"持锁事务 vs 自动提交多行 UPDATE 互等"
   的纯行锁死锁面，经 §3.2.1 路线三（全量收编 + derive 分解）构造性消除；`lock_timeout` +
   死锁重试从"兜底"降级为"保险层"（防静态审计漏网的未来语句与合法排队超时）。历史定性
   （"单向等待无环"之误）见 §11 第三轮记录。
3. **连接池耗尽风险（第三轮新增；第六轮按批量上传常态重估）**：写事务在 advisory 队列排队时
   各占一条 Hikari 连接（dev `maximum-pool-size=5`，`TransactionTemplate` 无超时）。批量上传
   使这成为**常态路径**而非边缘：20 文档批 → etlIo 并发 8 → 8 个排队写事务即超 dev 池 5；
   对账锁内重写（漂移日）30s+ 持锁期间同上。对策两层（必选）：(a) **§3.6 写闸门**——排队
   发生在应用内信号量上、**不占 DB 连接**，池压力从源头消除；(b) §8-4 `lock_timeout`
   （默认 10s，待压测定标）快速失败 + 有限重试——覆盖多实例跨实例排队（闸门只限本实例）
   与对账持锁期间的残余等待。
4. **多实例部署（第六轮：从"已知限制"升级为已纳入方案，见 §3.7）**：本设计的正确性核心
   （advisory 锁、增量不变式、对账幂等）**天然跨实例**——互斥发生在 PG 层，与实例数无关；
   唯一需要补的是 `@Scheduled` 防重（Redisson 领导选举，复用 outbox 的 `RedissonLeadership`
   先例，§3.7）。
5. **8:00 对账的长语句与长事务（第四轮修正后口径）**：常态下对账只执行只读探测——源侧指纹
   语句与 `projectCooccurrence` 的 SELECT 同形状，大 scope 仍可能 >30s 触发一次 Hikari leak
   警告（预期内合法长查询，频率"每 scope 每天"，**不持 advisory 锁、不阻塞写**）；锁内长事务
   （清扫 + delete + project + 指纹同事务，为换取锁内重投影的原子性）仅在探测阳性（漂移/孤儿）
   时执行（§6 阶段一），写事务排队面从"每天"收窄为"漂移日"。替代的是现状"每文档长语句 +
   非原子窗口"（§1.1 修正后定性）。

### 3.6 批量上传对策：同 scope 写闸门 + derive 防抖（第六轮新增）

批量上传（§3.5-1 事实修正）使"同 scope N 个写事务并发到达"成为常态。advisory 队列本身正确，
问题在排队者的资源占用与 per-document derive 的 CPU 放大。两个对策：

**写闸门（ScopeWriteGate，必选）**：在**开启写事务之前**先 acquire per-scope 信号量
（permits=1，`(userId, teamId)` 键的信号量缓存，Caffeine `maximumSize` 有界防 scope 泄漏）——

```
scopeWriteGate.tryAcquire(userId, teamId, waitMillis); // 上限等待（超时抛出），不占 DB 连接、不取任何锁
try {
    lockRetryExecutor.execute(() -> transactionTemplate.executeWithoutResult(...));
} finally {
    scopeWriteGate.release(userId, teamId);
}
```

- 语义变化：排队从"advisory 队列（每排队者占一条 Hikari 连接）"前移到"应用内信号量
  （零 DB 资源）"——批量 20 文档在 dev 池 5 下不再有池耗尽面；拿到闸门后 advisory 基本无竞争，
  `lock_timeout` 触发面大幅缩小（保留为多实例与对账持锁期的保险）。
- 闸门位于事务外、不取任何数据库锁，**不参与 §3.2.1 的 waits-for 图**——零死锁论证不变。
- 等待上限（`write-gate-wait-millis`，默认 120s，tryAcquire）：闸门等待占用 etlIo 线程，
  超时上限防单 scope 大批量独占线程池、饿死其它 scope 的事件消费；超时抛出 → 失败隔离 →
  标记不写 → §6.2 次日重链接（与重试耗尽同一兜底通道）。
- 多实例：闸门只限本实例并发；跨实例并发（≤ 实例数 M）仍由 advisory + `lock_timeout` 串行，
  池占用 ≤ M + 检索，可控。

**derive 防抖（DeriveDebouncer，推荐，默认开启）**：批量 N 文档 → N 次 `graphChanged=true` →
N 次 derive（O(scope) 计算 ×N 的 CPU 放大）。改为 scope 级 trailing 合并——写路径 graphChanged
后不立即 derive，而是标记 scope 待算并调度延迟窗口（`derive-debounce-millis`，默认 30000，
0=关闭回退逐文档即时 derive）内的后续写入合并为**一次** derive（窗口末尾执行，覆盖窗口内全部
拓扑变化）。取舍：结构分就绪延迟常态 ≤ 窗口（检索由默认分兜底——derive 前用默认分本就是
现状语义，§6 漂移分析）；**异常态例外（第八轮补充）**：防抖任务在内存调度，进程重启即丢——
若重启后该 scope 无新写入，对账阶段〇指纹一致（增量维护正确）不会触发 derive，结构分最坏
陈旧至周一 forceDerive（≤7 天）；期间 `community_stale=TRUE` 的实体可作观测信号（对账/监控
读取）。默认分兜底保证其不构成正确性问题。换来批量场景 derive 次数从 N 降为
⌈批处理时长/窗口⌉。多实例下每实例各自防抖，跨实例重复 derive 幂等无害
（`community_stale` + 对账指纹兜底）。
（删除路径常态化处于同类状态——不提交 derive、指纹恒阴性，结构分陈旧上界同为周一
forceDerive，口径见 §5 结构分补充。）

**闸门覆盖范围（第八轮补充：级联批量删除同构处理）**：写闸门同样前置在**删除路径**事务上
（`cleanupByDocumentId` 与写路径共用同一 per-scope 信号量，§5）——批量/级联删除（如清空
团队空间逐文档清理）与批量上传同构：N 个删除事务在闸门上排队（零 DB 连接占用）而非
advisory 队列。对账锁内重写不加闸门（leader 单线程、每日一次，排队成员仅并发写事务，
由 lock_timeout 保险层覆盖）。

### 3.7 多实例部署方案（第六轮新增：替代原"单实例假设"限制项）

本设计的正确性地基**天然多实例安全**，需要补的只有一处：

- **天然安全（无需改造）**：advisory 锁在 PG 锁管理器中，跨连接跨实例互斥——写/删/对账重写/
  写回短事务的串行正确性与实例数无关；增量不变式与对账幂等（重投影 + ON CONFLICT）保证
  双跑不出错；§6.2 重链接发布的是进程内事件，写路径以 DB 为同步点，无需跨实例投递——
  leader 实例发布、本实例消费即可。
- **需要补**：`@Scheduled` 防重（对账任务双跑仅浪费——Leiden CPU 密集，不损正确性）。
  方案：复用 outbox 已有的 **`RedissonLeadership`** 模式（Redisson 3.52 已在依赖中；持续持锁
  leader election，看门狗 10s 续约、leader 崩溃 ~30s 自动接管、Redis 未配置降级为每实例执行
  ——降级后正确性不变仅浪费）。`EntityGraphReconcileJob.schedule()` 入口检查
  `isLeader()`（lockKey 如 `smart-rag:leader:entity-graph-reconcile`），follower 直接返回。
- 范围边界：本设计只负责 `EntityGraphReconcileJob` 的防重；其余既有 `@Scheduled` 任务
  （AgentEventCleanupTask / EvaluationRunSweeper / OutboxCleanupScheduler 等）的防重属各自的
  改造范围，同模式可推广。
- 单实例 → 多实例的迁移动作 = 注册 leadership + 检查点，**并发模型与正确性论证零改动**。

---

## 4. 写路径：RETURNING 驱动的增量递增（缺陷 2 最终修复）

### 4.1 流程

`EntityCanonicalizationService.aggregateAndUpsert` 签名增加 `documentId`：

```java
public AggregateResult aggregateAndUpsert(List<ParsedExtraction> extractions,
                                          Long userId,
                                          @Nullable Long teamId,
                                          Long documentId)

/** entityIds：受影响实体（embedding 用，契约同旧返回值）；
    graphChanged：本次是否落库了新链接（false = 纯重投递，调用方据此跳过结构分重算，见 §6.1）。 */
public record AggregateResult(List<Long> entityIds, boolean graphChanged) {}
```

事务内顺序（**前两步必须是 setLockTimeout → lockScope**——set_config 不取任何锁，不破坏 R1）：

```
// 锁等待保险重试（§8-4；路线三下死锁已构造性消除，§3.2.1——本层覆盖 lock_timeout
// 排队失败、序列化失败 40001、及静态审计漏网的未来语句）：解包 cause 链读取
// SQLException.getSQLState()，仅精确匹配 {40P01, 40001, 55P03} 才重试
// （第四轮修正：不捕 TransientDataAccessException 整个家族——QueryTimeoutException
// 同族但属语句超时，误重试会把长语句故障放大为 300s×3 次），
// 最多 3 次、退避 1s/2s/4s × U(0.5,1.5) jitter；耗尽后抛出，由 ETL 失败隔离（§8.3）
// 记录 + §6.2 重链接检测次日自愈（链接缺失的兜底通道）。
// 注意：重试以整事务为单位——事务本身无外部副作用，重放安全。
// 前置（§3.6 写闸门，第六轮）：同 scope 信号量在【事务开启之前】acquire——批量上传的排队
// 发生在应用内（零 DB 连接占用），而非 advisory 队列（每排队者占一条连接）；tryAcquire
// 等待上限（默认 120s）防单 scope 大批量独占 etlIo 线程池；闸门超时/重试耗尽 → 失败隔离 →
// §6.2 次日重链接。闸门不取任何 DB 锁，不参与 §3.2.1 waits-for 图。
scopeWriteGate.tryAcquire(userId, teamId, waitMillis);
try {
lockRetryExecutor.execute(() ->
transactionTemplate.executeWithoutResult(status -> {
    // 0. 事务级 lock_timeout（§3.1 setLockTimeout，默认 10s）
    //    实际实现经 ScopeLockTemplate 统一封装（断言 → setLockTimeout → lockScope，§3.2.1）
    cooccurrenceMapper.setLockTimeout(properties.lockTimeoutMillis());

    // 1. scope advisory 锁（锁序规则 R1）
    cooccurrenceMapper.lockScope(userId, teamId);

    // 2. 实体 UPSERT（保留 DO UPDATE：description 合并在 SQL 完成；分批）
    for (batch : entitiesToUpsert 分批 500) {
        entityMapper.upsertByNormUserTeam(batch);
    }

    // 3. 锁内读实体 id → nameNormToId
    List<RagEntity> upserted = findEntitiesByNameNorms(aggregated.keySet(), userId, teamId);

    // 4. 锁内读受影响 chunk 的既有链接（精确 pair 计算需要，见 §4.3；chunkIds 同样按 500 分批）
    Map<UUID, Set<Long>> existingLinks =
        chunkEntityMapper.selectByChunkIds(受影响 chunkIds);          // 新增 mapper

    // 5. 链接插入：ON CONFLICT DO NOTHING + RETURNING（幂等 + 落库真值）
    List<NewLink> newLinks =
        chunkEntityMapper.insertBatchReturning(候选链接, documentId);   // 新增 mapper

    // 6. 由 RETURNING 行计算 pair 增量（§4.3）
    List<PairCount> deltas = computePairDeltas(existingLinks, newLinks);

    // 7. 边递增（排序分批，§3.4；冲突目标必须含 LEAST/GREATEST 表达式，见 §4.4）
    if (!deltas.isEmpty()) {
        cooccurrenceMapper.upsertIncrement(deltas, userId, teamId);     // 新增 mapper
    }

    // 8. degree 重算（返回 AggregateResult：entityIds 交给 embedding；graphChanged = !newLinks.isEmpty()）
    recomputeDegrees(upserted);

    // 9. markCommunityStale(受影响实体)（第五轮并入写事务：原在事务外自动提交，
    //    路线三后收编进锁内；graphChanged=false 时跳过——图谱未变无需标记，§6.1）
}));
} finally {
    scopeWriteGate.release(userId, teamId);        // §3.6 写闸门释放（与 acquire 同 scope 键）
}

// 早退路径（第四轮补充；第九轮明确顺序）：aggregated.isEmpty() 的判定先于写闸门 acquire——
// 空批次不拿闸门、不取锁、不开事务，直接返回 AggregateResult(List.of(), false)
// （与旧返回 List.of() 的契约对齐）。
// embedding 写回（updateEmbeddingBatch）在事务外经 LLM 调用后走独立 advisory 短事务（§3.2.1）。
```

### 4.2 RETURNING 语义（为什么这是缺陷 2 的正解）

```xml
<!-- 真正落库的行才出现在 RETURNING 中；被 ON CONFLICT DO NOTHING 吞掉的重复行不出现。
     MyBatis 用 <select> 承载 INSERT...RETURNING，resultMap 复用现有 constructor 风格。 -->
<select id="insertBatchReturning" resultMap="newLinkRow">
    INSERT INTO rag_chunk_entity (chunk_id, entity_id, document_id)
    VALUES
    <foreach collection="links" item="ce" separator=",">
        (#{ce.chunkId}::uuid, #{ce.entityId}, #{ce.documentId})
    </foreach>
    ON CONFLICT (chunk_id, entity_id) DO NOTHING
    RETURNING chunk_id, entity_id
</select>
```

- **跨调用幂等**：ETL 重投递/fastTrack 重试（同一文档的第二次 `aggregateAndUpsert` 调用）时，
  全部链接撞 `(chunk_id, entity_id)` 主键被吞 → RETURNING 为空 → 增量为 0。
  Java 侧无需任何去重缓存（该方案经审查否决，见 §11——每次调用新建的缓存覆盖不了跨调用场景）。
- **对齐真值**：增量由数据库实际接受的行决定，与 DB 层去重天然一致，不存在"链接被吞但计数照加"的窗口。

### 4.3 精确 pair 计算（覆盖重投递抽取结果不一致的情况）

若重投递的 LLM 抽取结果与首次**不完全一致**（温度 >0 时可能），某 chunk 可能"部分新增"实体：
`RETURNING` 只含新实体 E_new，但 chunk 的真实实体集是 `既有 ∪ 新增`，
新 pair 应包含 (既有实体, 新实体) 这类组合。纯 RETURNING 集合内自配对会漏掉它们。

```
对每个出现于 newLinks 的 chunk c：
    trueSet(c) = existingLinks(c) ∪ newLinks(c)
    对 trueSet(c) 的每一对 (a, b)，若 a ∈ newLinks(c) 或 b ∈ newLinks(c)：
        delta[min(a,b), max(a,b)] += 1
对只存在于 existingLinks 的 chunk：无变化，贡献 0
```

即步骤 4 的一次索引查询（`WHERE chunk_id IN (...)`）换精确性。**采用精确版**；
即便将来退化为纯 RETURNING 版，每日对账（§6）也会兜底自愈。

### 4.4 增量 upsert SQL

```xml
<!-- 排序分批由 Java 侧保证（§3.4）；a < b 由 Java 侧 LEAST/GREATEST 规范（插入行保持规范化，
     递减路径才能按裸列等值匹配）。
     冲突目标必须与 uk_cocur_scope_pair 索引表达式逐字对齐、含 LEAST/GREATEST——第三轮审查修正：
     原稿裸列形式 (…, entity_a, entity_b) 无法命中表达式索引，PG 索引推断失败，
     首次执行即抛 42P10（no unique or exclusion constraint matching），写路径整体不可用。
     与 projectCooccurrence 的 arbiter 形式保持一致（EntityCooccurrenceMapper.xml:52）。 -->
<insert id="upsertIncrement">
    INSERT INTO rag_entity_cooccurrence (entity_a, entity_b, co_count, user_id, team_id)
    VALUES
    <foreach collection="pairs" item="p" separator=",">
        (#{p.entityA}, #{p.entityB}, #{p.coCount}, #{userId}, #{teamId})
    </foreach>
    ON CONFLICT (user_id, COALESCE(team_id, -1), LEAST(entity_a, entity_b), GREATEST(entity_a, entity_b))
    DO UPDATE SET co_count = rag_entity_cooccurrence.co_count + EXCLUDED.co_count
</insert>
```

---

## 5. 删除路径：对称递减

`EntityIndexCleanupService.cleanupByDocumentId` 重写。调用方不变
（`DocumentLifecycleService:61`、`DocumentSupersedeService:326`）。

```
// Step 0: scope 从 rag_document 读（稳定来源：链接生命周期不影响它）——
//         不再从 rag_chunk_entity/rag_event 反查 scope（该读法存在
//         "删除 vs 重投递首写并发 → 读到空 scope → 跳锁 → 残留"竞态，审查修正，见 §11）。
//         selectScopeById 必须是手写 SQL 且【不过滤】@TableLogic 的 deleted 列：
//         rag_document 为逻辑删除（RagDocument.deleted），补偿性/乱序到达的清理
//         （如删除事件重放）仍须能读到 scope 并执行——否则僵尸链接无解（§6 孤儿清扫兜底）。
Scope s = documentMapper.selectScopeById(documentId);   // user_id, team_id（含已逻辑删除的行）
if (s == null) return;  // 文档行物理不存在，无事可做

scopeWriteGate.tryAcquire(s.userId(), s.teamId(), waitMillis); // §3.6 写闸门（第八轮：删除路径与写路径
try {                                                  // 共用同一 per-scope 信号量——批量/级联
lockRetryExecutor.execute(() ->                        // 删除与批量上传同构，排队零连接占用）
transactionTemplate.executeWithoutResult(status -> {
    // 0. 事务级 lock_timeout（§3.1）
    cooccurrenceMapper.setLockTimeout(properties.lockTimeoutMillis());

    // 1. advisory 锁（始终获取，即使文档当前无任何链接）
    cooccurrenceMapper.lockScope(s.userId(), s.teamId());

    // 2. 锁内快照（TOCTOU 修正：必须在锁后）
    List<Long> affectedEntityIds = chunkEntityMapper.selectEntityIdsByDocumentId(documentId);
    //    ↑ 语句 id 原地改写为 WHERE document_id = #{documentId} 直查（V30 新列），废除 rag_event 桥接；
    //      不加 "2" 后缀——避免新旧两版语句并存（第三轮审查命名修正，见 §7）
    List<PairCount> pairCounts = cooccurrenceMapper.selectPairCountsByDocumentId(documentId);

    if (!pairCounts.isEmpty()) {
        // 3-4. 对称递减 + 清零边删除（排序分批）
        cooccurrenceMapper.decrementByPairs(pairCounts, s.userId(), s.teamId());
        cooccurrenceMapper.deleteZeroEdges(s.userId(), s.teamId());
    }

    // 5-6. 删链接（deleteByDocumentId 语句 id 原地改写为 document_id 直查）+ 删事件（rag_event.document_id 仍权威）
    chunkEntityMapper.deleteByDocumentId(documentId);
    eventMapper.deleteByDocumentId(documentId);

    if (!affectedEntityIds.isEmpty()) {
        // 7-9. degree 重算 → 孤儿删除 → stale 标记（与现状一致）
        entityMapper.recalculateDegree(affectedEntityIds);
        entityMapper.deleteOrphans(affectedEntityIds);
        entityMapper.markCommunityStale(affectedEntityIds);
    }
}));  // lockRetryExecutor + transactionTemplate 双层闭合
} finally {
    scopeWriteGate.release(s.userId(), s.teamId());    // §3.6 闸门释放（同写路径）
}
```

配对 SQL：

```xml
<!-- 文档当前贡献的 pair 计数（= 该文档所有 chunk 内实体两两组合） -->
<select id="selectPairCountsByDocumentId" resultMap="pairCountRow">
    SELECT LEAST(e1.entity_id, e2.entity_id)  AS entity_a,
           GREATEST(e1.entity_id, e2.entity_id) AS entity_b,
           count(*)                              AS co_count
    FROM rag_chunk_entity e1
    JOIN rag_chunk_entity e2
      ON e1.chunk_id = e2.chunk_id AND e1.entity_id &lt; e2.entity_id
    WHERE e1.document_id = #{documentId}
    GROUP BY 1, 2
</select>

<!-- 对称递减（VALUES JOIN，排序分批由 Java 侧保证） -->
<update id="decrementByPairs">
    UPDATE rag_entity_cooccurrence c
    SET co_count = c.co_count - v.delta
    FROM (VALUES
        <foreach collection="pairs" item="p" separator=",">
            (#{p.entityA}::bigint, #{p.entityB}::bigint, #{p.coCount}::int)
        </foreach>
    ) AS v(entity_a, entity_b, delta)
    WHERE c.entity_a = v.entity_a AND c.entity_b = v.entity_b
      AND c.user_id = #{userId}
      AND (#{teamId,jdbcType=BIGINT} IS NULL AND c.team_id IS NULL OR c.team_id = #{teamId})
</update>

<!-- 清零边删除：递减后 co_count<=0 的边立即清除，等价于重投影的"去失效边"语义 -->
<delete id="deleteZeroEdges">
    DELETE FROM rag_entity_cooccurrence
    WHERE user_id = #{userId,jdbcType=BIGINT}
      AND (#{teamId,jdbcType=BIGINT} IS NULL AND team_id IS NULL OR team_id = #{teamId})
      AND co_count &lt;= 0
</delete>
```

正确性论证：`selectPairCountsByDocumentId` 在锁内取自当前真实链接，与写路径的
`lockScope` 互斥 → 快照即锁内真值；递减后 `deleteZeroEdges` 清除归零边，
保持不变式。`chunk 级`删除接口 `deleteByChunkIds` 对 `rag_chunk_entity` 无调用方（已核实），
不存在绕过递减的链接删除路径（V30 直接移除该语句，见 §7）。

**残余竞态与兜底（第三轮审查补充）**：删除与在途 ETL 重投递是 last-writer-wins——清理先提交、
重投递后执行时会重建链接，此后无常规路径再清理（文档行已逻辑删/缺失）。该残留由 §6 对账的
孤儿链接清扫兜底（document_id 反查 rag_document 的 anti-join），自愈支柱由此闭环。

**结构分不随删除即时重算（第九轮口径补充）**：删除路径只递减边、标记 `community_stale`，
不提交 derive（§3.6 防抖入口仅挂在写路径，§6.1）——删除后源侧/表侧指纹同步减少，对账阶段〇
恒阴性，结构分最坏陈旧至周一 forceDerive 或该 scope 下一次写入触发的 derive。这与现状一致
（现状删除同样只标 stale，步骤 9"与现状一致"），不构成回归；期间 `community_stale=TRUE`
可作观测信号（与 §3.6 防抖丢失场景共享同一上界与兜底）。

---

## 6. 每日 8:00 对账自愈（EntityGraphReconcileJob，新增）

不暴露管理端点（用户决策），由定时任务自动执行。

```java
@Component
public class EntityGraphReconcileJob {

    /** 独立单线程 executor：对账是小时级批处理，不得占用共享 @Scheduled 调度线程
        （Spring 默认单线程调度器，长对账会饿死 AgentEventCleanupTask / EvaluationRunSweeper
        ——第三轮审查修正）。
        生命周期（第四轮补充）：@PreDestroy shutdown + 具名线程（entity-graph-reconcile），
        队列由"每日一次 submit"的节奏天然有界。 */
    private final ExecutorService reconcileExecutor =
            Executors.newSingleThreadExecutor(namedThreadFactory("entity-graph-reconcile"));

    @Scheduled(cron = "${rag.entity-graph.reconcile.cron:0 0 8 * * *}")
    public void schedule() {
        if (!properties.enabled()) return;          // rag.entity-graph.reconcile.enabled，默认 true
        if (!leadership.isLeader()) return;         // §3.7 多实例防重：复用 RedissonLeadership 模式
                                                    // （Redis 未配置降级为恒 true——每实例执行，
                                                    //  幂等保证正确性，仅浪费 CPU）
        reconcileExecutor.submit(this::reconcileAll);
    }

    private void reconcileAll() {
        // 每周强制 derive（§6 新增）：旁路指纹门控，自愈结构分自身漂移
        boolean forceDerive = LocalDate.now().getDayOfWeek() == properties.forceDeriveDay(); // 默认 MONDAY
        for (Scope s : entityMapper.selectDistinctScopes()) {
            // scope 枚举（第四轮修正）：SELECT DISTINCT ... FROM rag_entity
            //   UNION SELECT DISTINCT ... FROM rag_entity_cooccurrence——
            //   覆盖"实体已尽失但边残留"的异常漂移 scope（rag_chunk_entity 无 scope 列不可枚举；
            //   链接在实体在由 deleteOrphans 的 degree=0 前置保证，边表无此前提）
            try {
                reconcileScope(s, forceDerive);     // 失败隔离：单 scope 失败不影响其余
            } catch (Exception e) {
                log.error("Reconcile failed for scope userId={}, teamId={}", s.userId(), s.teamId(), e);
            }
        }
        relinkDocumentsMissingExtraction();         // §6.2 重链接检测（第四轮新增，置于末尾：
                                                    // 重抽自身增量维护边，无需今日重投影配合。
                                                    // 第七轮修正：无参【全局】探测，scope 枚举源
                                                    // 是 rag_document 自身——绝不依赖上方
                                                    // selectDistinctScopes()（实体表），
                                                    // TRUNCATE 后首轮对账实体表为空也能驱动重建）
    }
}
```

per-scope 三阶段（**derive 链在锁外**——Leiden 是 CPU 密集内存计算，不得持有 advisory 锁与连接）：

```
阶段〇（无锁只读探测——第四轮重构，原稿无条件进入锁内重投影）：
    orphanLinks  = EXISTS(孤儿链接 anti-join)      -- 谓词同下方 deleteOrphanLinksByScope，仅 EXISTS
    orphanEvents = EXISTS(孤儿事件 anti-join)      -- 谓词同 deleteOrphanEventsByScope
    drift        = 源侧指纹 ≠ 表侧指纹             -- 源侧 = projectCooccurrence 的 SELECT 形状聚合成指纹
                                                    -- （经 rag_entity 限 scope，同款自连接 + GROUP BY）；
                                                    -- 表侧 = rag_entity_cooccurrence 现行边指纹
    if (全阴性) {
        if (!forceDerive) return;                  // 常态路径：零锁、零重写、无长事务
        跳过阶段一直接进入阶段二;                    // forceDerive 只旁路 derive 门控，不触发重写
    }
    -- MVCC 说明：两条指纹是独立语句，语句间提交的并发写可致假阳性漂移 → 进入阶段一，
    -- 重投影幂等、无害（宁可错杀）；写路径对链接与边的同事务原子提交保证任一快照内部自洽

阶段一（事务内 + §8-4 重试包装，仅当探测阳性）：
    setLockTimeout()             -- 同写/删路径
    lockScope(s)
    sweepOrphanLinks(s)          -- 孤儿链接清扫（第三轮审查新增），先于重投影（见下）
    sweepOrphanEvents(s)         -- 孤儿事件清扫
    fingerprintBefore = 边指纹   -- count(*) + md5(string_agg(a::text || ':' || b::text || ':' || co_count::text, ',' ORDER BY a, b))
    deleteByScope(s)             -- 复用现有
    projectCooccurrence(s)       -- 复用现有（不变式重投影，per-scope 有界；清扫后的链接集为真值）
    fingerprintAfter  = 边指纹
    rewrote = true

阶段二（锁外计算 + 锁内写回，仅当【rewrote 且指纹变化】或 forceDerive；第五轮路线三分解）：
    graph  = CooccurrenceGraphLoader.load(s)          -- 锁外：单次读快照（边 + scope 实体清单
                                                      --   含 degree/现分值——bridge reset-0 语义
                                                      --   需覆盖图外孤立实体）
    scores = WeakTieScoreCalculator(graph)            -- 锁外：纯内存（语义逐字对齐原 CTE）
            + LeidenCommunityDetector(graph)          -- 锁外：纯内存（现有组件）
            + bridge 内存计算（Leiden 分区 + 邻接）    -- 锁外
    ScopeLockTemplate(s):                             -- 写回短事务（O(scope) 行平坦 UPDATE，毫秒~秒级，重试包装同 §8-4）
        batchUpdateCommunities（Java 侧按 entityId 升序）
        updateWeakTieBatch / updateBridgeBatch（按 entityId 升序；IS DISTINCT FROM 跳过未变行）
        clearStaleFlag
    -- 统一入口：communityDetectionJob.run(userId, teamId)（扩展为全 derive 编排，§7）
    -- 写回事务原子性顺带消除"四步自动提交链中途失败留半代状态"的漂移根因；
    -- 三个分值共享同一次图快照（原实现 weak_tie 与 Leiden 各读各的快照）
```

- **漂移探测前置（第四轮修正）**：原稿阶段一无条件 delete+project——零漂移的 scope 也要每天跑
  一次 30s+ 的锁内长事务，写事务排队面（§3.5-3）每天暴露。改为先无锁只读探测（源侧/表侧指纹
  对比 + 孤儿 EXISTS），仅阳性才进入锁内重写。常态（增量路径维护正确）下对账只剩只读探测语句；
  锁内长事务从"每 scope 每天"降为"仅漂移/孤儿日"。源侧指纹查询与 `projectCooccurrence` 的
  SELECT 同形状（同样的自连接 + 聚合），大 scope 仍可能 >30s 触发一次 Hikari leak 警告——
  预期内合法长查询，**不持锁、不阻塞写**（§8-3）。
- **孤儿清扫（第三轮审查新增，自愈支柱补漏）**：删除路径与在途 ETL 重投递是 last-writer-wins
  （清理先提交、重投递后重建链接），且补偿性清理在文档行物理缺失时会被 Step 0 跳过——两类场景
  都会留下"文档已不存在但链接仍在"的僵尸，而重投影会把僵尸当真值固化、永不自愈。V30 的
  document_id 新列使清扫成为廉价 anti-join；**必须经 rag_entity 限定作用域**（rag_chunk_entity
  无 scope 列），保证行锁获取发生在 lockScope 之后（R1）：

  ```xml
  <!-- 孤儿链接：经 rag_entity 限定 scope；逻辑删除行（deleted=1，以 @TableLogic 实际配置为准）视同不存在 -->
  <delete id="deleteOrphanLinksByScope">
      DELETE FROM rag_chunk_entity ce
      USING rag_entity re
      WHERE re.id = ce.entity_id
        AND re.user_id = #{userId,jdbcType=BIGINT}
        AND (#{teamId,jdbcType=BIGINT} IS NULL AND re.team_id IS NULL OR re.team_id = #{teamId,jdbcType=BIGINT})
        AND NOT EXISTS (SELECT 1 FROM rag_document d WHERE d.id = ce.document_id AND d.deleted = 0)
  </delete>

  <!-- 孤儿事件：rag_event 自带 scope 列，直接清扫（同步修复 SAG 检索层的僵尸） -->
  <delete id="deleteOrphanEventsByScope">
      DELETE FROM rag_event e
      WHERE e.user_id = #{userId,jdbcType=BIGINT}
        AND (#{teamId,jdbcType=BIGINT} IS NULL AND e.team_id IS NULL OR e.team_id = #{teamId,jdbcType=BIGINT})
        AND NOT EXISTS (SELECT 1 FROM rag_document d WHERE d.id = e.document_id AND d.deleted = 0)
  </delete>
  ```

- **每周强制 derive（第三轮审查新增）**：指纹门控只覆盖边漂移——weak_tie/community/bridge 自身
  被污染而边指纹不变时永不自愈（验证 #7 只覆盖边漂移）。forceDeriveDay（默认周一）旁路
  指纹门控无条件执行阶段二。
- **漂移检测 = 指纹对比**：指纹不变说明增量路径已维护正确，跳过昂贵的结构分重算；
  变化才触发 derive 链。源侧/表侧指纹的输出格式必须逐字一致（同一 (a, b, co_count) 三元组
  有序序列）；拼接显式 `::text` 转义（第四轮修正：不依赖隐式 `anynonarray‖text` 操作符，
  避免实现时的移植性噪音）。`string_agg` 对大 scope 生成 MB 级字符串，每日一次可接受
  （更廉价的 count+sum 备选，区分度略低，二选一在实施时定）。
- **derive 期间的并发写**：阶段二锁外读到的边可能被并发写路径修改 → 分值基于略旧快照。
  与现状行为等价（现状的 Leiden 同样不持锁），由 `community_stale` 机制 + 次日对账兜底。
  **路线三改善（第五轮）**：写回收进单事务——原"updateWeakTieScores → Leiden 写回 → bridge →
  clearStale 四步自动提交中途失败留半代状态"（community 新 bridge 旧的混代）被原子性消除；
  且三个分值同源同快照。快照滞后语义本身不变（漂移根因 1/3 仍在，forceDerive 兜底不变）。
- `@EnableScheduling` 已启用（AdvisorAutoConfiguration.java:39），无需新增。

### 6.1 旧全量重投影路径退役

- `EntityIndexService.recomputeWeakTieScores`（deleteByScope + projectCooccurrence + updateWeakTieScores）
  **退役**：重投影职责移交对账任务。
- `EntityExtractionService:240-241` 的每文档后处理改为：
  `deriveDebouncer.submit(userId, teamId)`（§3.6 防抖入口，内部到期调
  `communityDetectionJob.run(...)`——**derive 统一入口**：锁外 load → weak_tie + Leiden + bridge
  内存计算 → 锁内写回，§6 阶段二；第五轮后不再直接调 `updateWeakTieScores`——该 CTE 已退役
  分解），且**仅当 `aggregateAndUpsert` 返回 `graphChanged=true` 时提交**（第三轮审查新增，
  放大效应对策：纯重投递不产生新链接、共现图无变化，跳过 O(邻居²) 计算与 Leiden——这是残余
  成本中"与文档大小无关、与 scope 大小线性"的主要部分，验证 #14；第六轮防抖把批量场景的
  derive 次数进一步从 N 合并为窗口期一次，验证 #21）。
  `embedAndMarkStale` 的 embedding 部分不受门控（保留对实体描述更新的自愈覆盖；写回走
  advisory 短事务，§3.2.1）；`markCommunityStale` 已并入写事务且仅 graphChanged 时标记
  （图谱未变则无需重算）。
- `projectCooccurrence` / `deleteByScope` SQL 保留，仅供对账任务复用。

### 6.2 重链接检测：写路径失败的自愈（第四轮审查新增）

**问题**：写路径事务经 §8-4 重试仍耗尽（对账锁内重写期间同 scope 上传、同 scope 上传风暴排队
超时等）后，`aggregateAndUpsert` 抛出、被 `EntityExtractionService` 的失败隔离 catch-log 吞掉
（EntityExtractionService.java:166-169）。`EtlVectorizedEvent` 是进程内 Spring 事件
（FastTrackStrategy:225 / StandardStrategy:172 发布），不经 outbox、无重投递——该文档的
实体/链接**永久缺失**，而重投影只修边漂移、不补链接：三支柱原本对支柱一自身的失败没有兜底。
且 V30 引入 `lock_timeout` 快速失败后，该路径从现状的"排队慢而最终成功"变为"可丢失"，必须配套。

**机制**：`rag_document.entity_extracted_at`（V30 新增列）作为抽取完成标记——

- `EntityExtractionService.extractAndIndex` 的**所有非异常退出路径**（成功 / 无 chunk / 无实体）
  于末尾执行 `markEntityExtracted(documentId)`（自动提交单语句 UPDATE）。进程崩溃于
  事务提交与标记之间 → 标记仍为 NULL → 次日重链接 → 重抽幂等（RETURNING 驱动增量、
  graphChanged 门控），无副作用；**异常退出不标记**，留待重链接。
- 对账每日探测（**全局、无 scope 参数**，放在 reconcileAll 末尾统一执行——第七轮修正：
  原稿 per-scope 探测与无参调用自相矛盾，且若按 `selectDistinctScopes()` 驱动，
  TRUNCATE 后首轮实体表为空集、重建将空转。重链接的语义是**文档驱动**而非 scope 驱动：
  `rag_document` 不在 TRUNCATE 清单内、行自带 `user_id`/`team_id`（恰为发布事件所需参数），
  不需要任何 scope 枚举）：

  ```xml
  <!-- 待重链接文档（全局）：抽取从未完成（标记 NULL）+ 文档在册 + ETL 终态 + 宽限期。
       宽限期（6h）纯为成本控制——避开在途 ETL 的重复 LLM 抽取；重抽本身幂等，
       即使误触发也无正确性影响。部分索引 idx_doc_entity_extraction_pending 直接命中。 -->
  <select id="selectDocsPendingEntityExtraction" resultMap="scopeDocRow">
      SELECT id, user_id, team_id
      FROM rag_document
      WHERE deleted = 0
        AND status = 'COMPLETED'
        AND entity_extracted_at IS NULL
        AND update_time &lt; NOW() - INTERVAL '6 hours'
      ORDER BY update_time
      LIMIT #{limit}    <!-- rag.entity-graph.reconcile.relink-limit，默认 0 = 不限（0 时省略 LIMIT） -->
  </select>
  ```

  命中文档逐个 `eventPublisher.publishEvent(new EtlVectorizedEvent(docId, userId, teamId))`
  （行自带的 user_id/team_id 即事件参数；**逐文档 try/catch 隔离**——单文档发布失败不影响其余）——
  监听器 `@Async("etlIoExecutor")` 异步执行，不阻塞对账线程；重抽走全量幂等路径。反复失败的
  文档保持 NULL、每日重试——即写路径失败的最终自愈通道（验证 #15）。
- **首轮即迁移后的自动重建**：V30 TRUNCATE 后全部在册文档标记为 NULL，首次对账即自动全量
  重抽——探测以 `rag_document` 为源，**与实体表空与否无关**（第七轮修正后该承诺才真正成立；
  验证 #23）。§2 运维口径：降级窗口、LLM 成本、relink-limit 限流；迁移前 6h 内更新过的文档
  因宽限期顺延至次日对账。

---

## 7. Mapper / 服务变更清单

| 文件 | 变更 |
|------|------|
| `V30__incremental_cooccurrence.sql` | 新增（§2，含 Down SQL 注释） |
| `ChunkEntityMapper.xml` / `.java` | +`insertBatchReturning`（含 resultMap `newLinkRow`）；+`selectByChunkIds`（IN 列表按 500 分批）；`deleteByDocumentId` / `selectEntityIdsByDocumentId` **语句 id 原地改写**为 `document_id` 直查（不加后缀）；**删除** `insertBatch`（切换后无调用方）与 `deleteByChunkIds`（本就无调用方）；+`deleteOrphanLinksByScope`、+`existsOrphanLinksByScope`（§6 清扫与阶段〇探测） |
| `EventMapper.xml` / `.java` | +`deleteOrphanEventsByScope`、+`existsOrphanEventsByScope`（§6） |
| `EntityCooccurrenceMapper.xml` / `.java` | +`lockScope`（契约由运行时断言固化，§3.1）、`setLockTimeout`、`upsertIncrement`（冲突目标含 LEAST/GREATEST，§4.4）、`decrementByPairs`、`deleteZeroEdges`、`selectPairCountsByDocumentId`（含 `pairCountRow` resultMap）、`selectEdgeFingerprint`（表侧）、+`selectSourceFingerprint`（源侧，projectCooccurrence 的 SELECT 形状，§6 阶段〇）；保留 `projectCooccurrence`/`deleteByScope`（对账专用）；**`updateWeakTieScores` 退役删除**（§3.2.1 分解为 WeakTieScoreCalculator + `updateWeakTieBatch`） |
| `EntityMapper.xml` / `.java` | +`selectDistinctScopes`（rag_entity UNION rag_entity_cooccurrence，§6）；+`updateWeakTieBatch` / `updateBridgeBatch`（有序批量写回，按 entityId 升序，§3.2.1）；**`updateBridgeScores` 退役删除**（内存计算分解）；`batchUpdateCommunities` Java 侧按 entityId 升序；`updateEmbeddingBatch` / `markCommunityStale` / `clearStaleFlag` 语句不变（调用点收编进锁，§3.2.1） |
| `DocumentMapper`（或等价入口） | +`selectScopeById`（手写 SQL 读 user_id/team_id，**不过滤** deleted）；+`selectDocsPendingEntityExtraction`（**全局查询、无 scope 参数**，scope 枚举源 = rag_document 自身，§6.2 第七轮修正）/ `markEntityExtracted`；`RagDocument` 实体 +`entityExtractedAt` 字段 |
| `EntityCanonicalizationService` | `aggregateAndUpsert` +`documentId` 参数、返回 `AggregateResult(entityIds, graphChanged)`；事务体重排（setLockTimeout → lockScope 前置）；+`computePairDeltas`；整体包 §8-4 重试 |
| `EntityIndexCleanupService` | `cleanupByDocumentId` 重写（§5：rag_document 取 scope、始终取锁、锁内快照、递减清零、直查删除）；整体包 §8-4 重试 |
| `EntityExtractionService` | `:149` 调用点传 documentId；`:240` 后处理去掉重投影并按 `graphChanged` 门控（§6.1）；所有非异常退出路径写 `entity_extracted_at` 完成标记（§6.2）；`embedAndMarkStale` 仅余 embedding（markCommunityStale 已并入写事务，§4.1 步骤 9） |
| `EntityIndexService` | `recomputeWeakTieScores` 退役（derive 职责移交 `communityDetectionJob.run` 统一入口） |
| `EntityEmbeddingService` | `updateEmbeddings` 的写回改走 `ScopeLockTemplate` advisory 短事务（LLM 调用留锁外，§3.2.1）；写回重试耗尽 → `extractAndIndex` 异常退出 → 标记不写 → 次日重链接补（幂等，§6.2——项目无补嵌调度，已核实 `selectEntitiesNeedingEmbedding` 无调用方） |
| `CommunityDetectionJob` | 扩展为 derive 统一编排（锁外 load → WeakTieScoreCalculator + Leiden + bridge 内存计算 → `ScopeLockTemplate` 锁内有序写回，§6 阶段二）；`CooccurrenceGraphLoader` 扩展返回 scope 实体清单（含 degree/现分值，覆盖图外孤立实体的 reset 语义） |
| `ScopeLockTemplate`（新组件） | advisory 事务统一封装：断言（isActualTransactionActive）→ setLockTimeout → lockScope → body（§3.2.1 防线一）；静态审计白名单的锚点 |
| `WeakTieScoreCalculator`（新组件） | weak_tie 纯内存算法（无状态，参照 LeidenCommunityDetector 形态）；语义逐字对齐原 CTE（degree<100 预算、Jaccard embeddedness、仅更新有邻居对实体、hub/孤立不动、默认 0.5）；上线前以原 CTE 为金标准对拍（验证 #18） |
| `MapperWriteAuditTest`（新单测） | 静态审计：扫描 mapper XML/注解，三张表的多行写语句必须在 `ScopeLockTemplate` 调用点白名单内，否则失败（§3.2.1，验证 #20） |
| `LockRetryExecutor`（新组件） | 锁等待保险重试（§8-4；路线三下死锁已构造性消除，本层为保险）：解包 cause 链精确匹配 SQLState {40P01, 40001, 55P03}（第四轮修正：不捕 `TransientDataAccessException` 整族，排除 `QueryTimeoutException`），默认 3 次、退避 1s/2s/4s × U(0.5,1.5) jitter |
| `EntityGraphReconcileJob` | 新增（§6：阶段〇无锁探测、锁内条件重写、孤儿清扫、周强制 derive、§6.2 重链接检测；独立 executor + @PreDestroy + 具名线程；§3.7 `RedissonLeadership` leader 检查防多实例双跑） |
| `ScopeWriteGate`（新组件） | §3.6 同 scope 写闸门：per-scope `Semaphore(1)` 缓存（Caffeine 有界）+ `tryAcquire(write-gate-wait-millis)` + finally release；**写路径与删除路径共用**（第八轮：批量/级联删除与批量上传同构处理）；事务开启前调用，不取任何 DB 锁 |
| `DeriveDebouncer`（新组件） | §3.6 derive 防抖：per-scope trailing 合并（默认窗口 30s，0=关闭回退逐文档即时 derive）；到期执行 `communityDetectionJob.run`；多实例下每实例各自防抖（幂等） |
| `RagEntityProperties`（或新配置类） | +`reconcile.enabled` / `reconcile.cron` / `reconcile.force-derive-day`（默认 MONDAY）/ `reconcile.relink-limit`（默认 0 = 不限）；+`lock-timeout-millis`（初始值 10000，#12 压测定标）/ `lock-retry-attempts`（默认 3）；+`write-gate-wait-millis`（默认 120000，§3.6）/ `derive-debounce-millis`（默认 30000，0=关闭，§3.6） |

---

## 8. 超时与连接池治理（配套）

1. **MyBatis 语句超时**：全局 `mybatis-plus.configuration.default-statement-timeout=300`（秒）兜底
   （第三轮审查修正：项目用 MyBatis-Plus starter，application-*.yml 均为 `mybatis-plus:` 前缀，
   原稿的 `mybatis.configuration.*` 不会绑定、被静默忽略）；
   重 CTE（`projectCooccurrence`、源侧指纹查询；`updateWeakTieScores` 已于第五轮退役分解）
   可用 mapper 级 `timeout` 属性细化。
2. **JDBC socketTimeout**：**不设全局硬顶**（第三轮审查修正：原稿 `socketTimeout=180` 会杀掉
   对账长语句 >180s 的合法执行，与 §3.5-5 自相矛盾；第四轮后口径：每日源侧指纹探测与漂移日
   锁内重投影均可能长耗时）。网络层悬挂防护改由
   `default-statement-timeout` + Hikari `max-lifetime` 承担；若确需 socketTimeout，
   取值必须 ≥ 对账最坏语句耗时（如 600s）。
3. **Hikari leak 警告语义**：V30 后长查询从"每文档"降为"每 scope 每天（8:00 只读源侧指纹探测，
   不持锁不阻塞写）+ 漂移日的锁内重写"，警告频率大幅下降；对账期间的单次警告属预期内合法
   长查询，无需调 `leakDetectionThreshold`。
4. **lock_timeout + 重试保险层（第三轮升为必选；第四轮修正谓词与退避；第五轮起定位为保险——
   死锁面已由路线三构造性消除，§3.2.1）**：所有持锁事务
   （写路径/删除路径/embedding 短事务/derive 写回/对账锁内重写）首语句
   `set_config('lock_timeout', …, true)`（初始值 10s——**须经验证 #12 压测定标**：取 ≥ p99
   写事务时长的安全倍数，过小会令正常排队误失败），外层 `LockRetryExecutor` **解包 cause 链
   读取 `SQLException.getSQLState()`，仅精确匹配 {40P01 死锁 / 40001 序列化失败 /
   55P03 lock_not_available} 才重试**——不捕 `TransientDataAccessException` 整个家族（该族含
   `QueryTimeoutException`：语句超时被误重试会把对账长投影等故障放大为 300s×3 次）。
   最多 3 次、退避 1s/2s/4s × U(0.5,1.5) jitter（并发 ≥5 同时 55P03 后固定退避会同步重试、
   浪费 attempt 预算）。
   三重动机：(a) **保险**——防静态审计漏网的未来无序语句重新引入死锁、防合法排队超时（55P03
   本身就是 lock_timeout 的正常失败信号）；(b) §3.5-3 连接池耗尽——写事务排队等待时间有上界，
   不会无限占用池内连接（dev 池仅 5）；(c) 重试耗尽不再是终态——链接缺失由 §6.2 重链接检测
   次日自愈（第四轮新增）。

---

## 9. 现有调用方影响面

- `aggregateAndUpsert` 唯一调用方 `EntityExtractionService:149`（已核实），签名（+documentId）
  与返回类型（`List<Long>` → `AggregateResult`）变更影响封闭。
- `cleanupByDocumentId` 调用方 `DocumentLifecycleService:61`、`DocumentSupersedeService:326`，签名不变。
- `EtlVectorizedEvent` 新增第三个发布方：对账任务的重链接检测（§6.2；既有两个发布方为
  FastTrackStrategy:225 /
  StandardStrategy:172）；监听器 `extractAndIndex` 幂等（§4.2/§4.3），重复触发无正确性影响。
- 检索侧（`findFrontierEntities` / `voteBacklinkChunks` / `expandChunks`）只读共现边与链接表，
  增量维护对它们透明；最坏读到"边略滞后于链接"的中间态由不变式在事务提交点收敛。
- 实施前按 AGENTS.md 要求对 `aggregateAndUpsert` / `cleanupByDocumentId` / `recomputeWeakTieScores`
  / `CommunityDetectionJob.run`（第五轮扩展对象）跑 GitNexus impact 复核本清单；
  `updateWeakTieScores` / `updateBridgeScores` 退役删除前同样复核（确认无本清单之外的调用方）。

---

## 10. 验证清单

| # | 场景 | 断言 |
|---|------|------|
| 1 | 并发上传 + 删除（同 scope 多文档，etl-io 多线程，混合 embedding 写回与 derive 写回） | **零 40P01**（路线三构造性零死锁断言，§3.2.1；历史断言演变见 §11）、终态满足不变式、无残留影响 |
| 2 | 同一文档 ETL 重投递（全量重复） | 链接不重复、`co_count` 不变（RETURNING 为空 → 增量 0） |
| 3 | 重投递且抽取结果部分新增实体 | (既有实体, 新实体) pair 恰好 +1（§4.3 精确计算） |
| 4 | 单 chunk 内同名实体重复出现 | 只计一次链接与一次 pair |
| 5 | 文档删除 | 链接清空、本文档贡献的边 -delta、归零边删除、degree 重算、孤儿实体清除 |
| 6 | 删除与重投递并发 | 串行于 advisory 锁，终态满足不变式 |
| 7 | 人为制造漂移（手动 UPDATE co_count） | 次日 8:00 对账后恢复：阶段〇源侧/表侧指纹探测阳性 → 锁内重写执行 → 指纹变化触发 derive 链 |
| 8 | scope 无实体数据 | 对账跳过、无 NPE/空事务 |
| 9 | 对账期间并发上传 | 锁互斥，无部分可见状态；derive 链不持锁 |
| 10 | 事务回滚（链接插入后制造异常） | 边增量同事务回滚，不变式保持 |
| 11 | 僵尸链接：物理删除 rag_document 行后补造该文档链接（模拟重投递复活） | 次日对账孤儿清扫删除链接与事件、边经重投影清除、指纹变化触发 derive |
| 12 | 8:00 对账锁内重写期间同 scope 上传（写闸门后跨实例/残余等待场景） | lock_timeout 快速失败后重试成功、连接池无耗尽、全部文档终态正确 |
| 13 | 结构分漂移：手动 UPDATE weak_tie_score/community_id（边指纹不变） | 次周一强制 derive 恢复正确值 |
| 14 | 纯重投递（第二次投递零新链接，graphChanged=false） | derive 链/Leiden/markCommunityStale 未执行（日志验证），共现图与结构分无变化、embedding 正常刷新 |
| 15 | 重试耗尽终态：对账锁内重写持锁期间制造同 scope 写事务直至 §8-4 重试耗尽 | 当时链接缺失、`entity_extracted_at` 保持 NULL；次日对账重链接检测自动补抽（日志验证事件重发）、终态不变式恢复（§6.2） |
| 16 | 零漂移常态：连续两日无写入/无删除 | 第二日对账不进入阶段一（无 deleteByScope/projectCooccurrence 日志）、无 advisory 锁等待；阶段〇探测语句正常执行 |
| 17 | LockRetryExecutor 谓词精度 | 单测断言：40P01/40001/55P03 触发重试；QueryTimeoutException 及其余非目标异常不触发、直接抛出 |
| 18 | WeakTieScoreCalculator 对拍原 CTE（金标准切换验证） | 同一图数据上 Java 计算结果与原 `updateWeakTieScores` CTE 逐实体一致（含 degree≥100 hub、无邻居对实体、孤立实体的边界语义） |
| 19 | derive 写回原子性：计算完成后写回事务内注入异常 | community_id / bridge_score / weak_tie_score 同代回滚，无半代状态（community 新 bridge 旧的混代不可能出现） |
| 20 | 静态审计防呆自证 | 向 mapper 添加一条未入白名单的三表多行写语句 → `MapperWriteAuditTest` 失败；移除后恢复通过 |
| 21 | 批量上传/级联删除压测：`uploadBatch` 20 文档同 scope + 团队空间级联清空（dev 池 5、etlIo 4-8） | 写闸门下两类路径排队均零 DB 连接占用（池监控验证）、全部终态正确；derive 防抖合并为 ≤⌈批时长/30s⌉ 次（日志验证）；无 lock_timeout 耗尽（§3.6，第八轮扩为含删除路径） |
| 22 | 多实例防重：双实例部署（或单实例模拟双 leader 触发） | 对账仅 leader 执行（follower 日志跳过）；kill leader 后 ≤30s 接管（RedissonLeadership 看门狗）；双跑注入时对账幂等无重复副作用（§3.7） |
| 23 | TRUNCATE 后首轮对账（实体表为空）：造若干在册 COMPLETED 文档 + 空实体表，触发对账 | scope 主循环零迭代（空集）；全局重链接探测仍枚举到全部待重建文档并发布事件（不依赖 `selectDistinctScopes`）；重抽完成后标记写入、次日探测为空（§6.2，第七轮） |

---

## 11. 审查修正记录（防止实现回退）

设计经九轮审查。前两轮的否决方案实现时不得采用；第三至九轮修正项见后表。

### 缺陷 1（HIGH）：写路径锁序倒置 → 死锁

- **原方案问题**：写路径顺序为"实体 upsert（行锁）→ 插链接 → `lockScope` → 边递增"，与删除路径
  （先 advisory 后行锁）构成 advisory↔行锁倒序，同 scope 跨文档并发时形成等待环，
  PG `deadlock_timeout` 后杀事务。
- **修正**：`lockScope` 前置为所有持锁事务的第一个数据库操作（§3.2 规则 R1/R2）。
- **否决的子方案**：将 `upsertByNormUserTeam` 改为 `ON CONFLICT DO NOTHING` 以"消除行锁"。
  否决理由：(a) DO NOTHING 对并发未提交同键插入仍需等待对方事务结束（speculative insertion），
  "不拿行锁"的技术判断不成立；(b) 丢失 `DO UPDATE` 中的 description 跨文档追加合并（EntityMapper.xml:15），语义回归。真正的解法是 advisory 串行化，而非改 SQL 形态。

### 缺陷 2（MEDIUM→HIGH）：增量计数与链接去重不对齐 → co_count 虚高

- **原方案问题**：链接插入 `ON CONFLICT DO NOTHING` 吞掉重复行时，Java 侧计数照加。
- **否决的子方案 A**：Java 层去重缓存（`Set<(chunkId, entityId)>`）对齐计数。否决理由：缓存每次调用
  新建，覆盖不了跨调用重投递（ETL 重投递/fastTrack 重试恰是第二次独立调用）——缺陷主场景原样存在。
- **否决的子方案 B**：去掉 DB 的 `ON CONFLICT DO NOTHING` 改裸 INSERT（"Java 已保证唯一"）。
  否决理由：跨调用重投递必撞 `(chunk_id, entity_id)` 主键 → 整批插入失败，破坏幂等，主动引入故障。
- **采纳**：`RETURNING` 驱动增量（§4.2）——数据库实际接受的行决定增量，天然对齐、跨调用幂等；
  叠加锁内既有链接查询实现精确 pair 计算（§4.3）。

### 附带修正（审查中发现的设计自身缺陷）

| 问题 | 修正 |
|------|------|
| 删除路径快照取在 `lockScope` 之前 → TOCTOU 漂移 | 快照移到锁后（§3.3） |
| 删除路径从链接表反查 scope，空结果跳锁 → 与重投递首写并发的残留竞态 | scope 改从 `rag_document` 读取、始终取锁（§5 Step 0） |
| 排序分批被质疑多余 | 降级为纵深防御保留（§3.4） |

### 第三轮审查修正（评审定稿前，含已采纳的全部修正项）

| 级别 | 问题 | 修正 |
|------|------|------|
| 高 | §4.4 `upsertIncrement` 冲突目标为裸列 `(…, entity_a, entity_b)`，无法命中 `uk_cocur_scope_pair` 表达式索引（V21:84-85），首次执行抛 42P10，写路径整体不可用 | 冲突目标改为 `(user_id, COALESCE(team_id,-1), LEAST(entity_a,entity_b), GREATEST(entity_a,entity_b))`，与 `projectCooccurrence` 的 arbiter 逐字对齐（§4.4） |
| 高 | §3.2"满足 R1+R2 后不可能形成等待环"论断过强：持锁事务（多语句多批次行锁）与不取 advisory 的自动提交多行 UPDATE（updateWeakTieScores / batchUpdateCommunities / updateBridgeScores / clearStaleFlag / updateEmbeddingBatch / markCommunityStale）仍可纯行锁互等死锁；§3.5-2"单向等待"定性错误 | 修正论断边界（§3.2/§3.5-2）；`LockRetryExecutor` 死锁重试升为必选（§8-4）；验证 #1 断言从"零死锁"改为"可检测重试恢复" |
| 高 | 对账持锁期间写事务排队各占连接（dev 池 5、TransactionTemplate 无超时），上传高峰可耗尽连接池、阻塞全应用 | 持锁事务 `lock_timeout`（默认 10s）+ 有限重试升为必选（§3.1 setLockTimeout、§3.5-3、§8-4） |
| 中 | 删除路径"文档行读不到即 return"+ 删除/重投递 last-writer-wins → 僵尸链接无任何常规路径清理，重投影将其固化为真值、永不自愈 | `selectScopeById` 不过滤逻辑删（§5 Step 0）；对账新增孤儿链接/事件清扫 anti-join（§6，验证 #11） |
| 中 | 放大效应只解决一半：每文档全 scope weak_tie CTE + Leiden 原样保留 | `graphChanged` 门控：纯重投递跳过结构分链（§4.1/§6.1，验证 #14）；embedding 不受门控 |
| 中 | §1.1"30s+ 长事务"定性错误：现状 `recomputeWeakTieScores` 无事务包裹、三条自动提交语句，delete→project 非原子窗口未提及 | §1.1 改写为"长语句 + 非原子窗口"，结论方向不变 |
| 中 | §3.2 盘点表 `markCommunityStale` 标注"锁内"错误（实际 EntityExtractionService:229 事务外自动提交） | 盘点表修正，纳入残余死锁面分析 |
| 中 | 指纹门控只覆盖边漂移，weak_tie/community/bridge 自身污染永不自愈 | 每周 forceDeriveDay（默认周一）强制阶段二（§6，验证 #13） |
| 低 | `mybatis.configuration.default-statement-timeout` 命名空间错误（MyBatis-Plus starter 下不绑定） | 改 `mybatis-plus.configuration.*`（§8-1） |
| 低 | `socketTimeout=180` 全局硬顶会杀对账阶段一 >180s 的合法长投影 | 取消全局设置或 ≥ 对账预算（§8-2） |
| 低 | `deleteByDocumentId2` / `selectEntityIdsByDocumentId2` 后缀命名致新旧并存；`insertBatch` 切换后成死代码 | 语句 id 原地改写、删除死语句（§5/§7） |
| 低 | `selectByChunkIds` IN 列表无上限（万级 chunk 文档） | 按 500 分批（§4.1） |
| 低 | `lockScope` 在自动提交下静默失效；`#{teamId}` 缺 jdbcType | javadoc 固化契约 + jdbcType（§3.1） |
| 低 | 对账占用共享 @Scheduled 单线程调度器，会饿死其他定时任务 | 独立单线程 executor（§6） |
| 低 | TRUNCATE 连带清空 rag_event（SAG 检索层）未在运维口径标注；迁移缺 Down 注释 | §2 说明补充 + Down SQL 注释 |

### 第四轮审查修正（外部技术评审，对照代码库逐条核实后定稿）

| 级别 | 问题 | 修正 |
|------|------|------|
| 高 | 写路径失败无自愈：重试耗尽被失败隔离 catch-log 吞掉后，该文档链接永久缺失（`EtlVectorizedEvent` 为进程内 Spring 事件，不经 outbox、无重投递——已核实 FastTrackStrategy:225 / StandardStrategy:172），重投影只修边漂移不补链接；且 V30 的 lock_timeout 把"排队慢而成功"改造成"可丢失" | §6.2 重链接检测：`rag_document.entity_extracted_at` 完成标记（V30 新列）+ 对账探测重发事件（幂等重抽）；首轮对账兼作迁移后的自动重建（验证 #15） |
| 中 | LockRetryExecutor 捕 `TransientDataAccessException` 整族过宽：同族的 `QueryTimeoutException`（语句超时）被误重试，对账长投影故障放大为 300s×3 次 | 解包 cause 链精确匹配 SQLState {40P01, 40001, 55P03}（§8-4，验证 #17） |
| 中 | 对账阶段一无条件 delete+project：零漂移也每天每 scope 跑 30s+ 锁内长事务，§3.5-3 的写事务排队面每天暴露 | 阶段〇无锁只读探测（源侧/表侧指纹 + 孤儿 EXISTS），仅阳性进入锁内重写（§6，验证 #16） |
| 中 | lock_timeout=10s 无压测定标依据（大文档写事务多批 upsert 可能逼近超时）；固定退避无 jitter，并发同时失败后同步重试浪费 attempt 预算 | 默认值标注为初始值、验证 #12 压测定标（§8-4）；退避 ×U(0.5,1.5) 抖动（§8-4） |
| 中 | stable 迁移后"重跑 ETL"的重建手段、降级窗口、LLM 成本无运维口径 | 重链接检测使首轮对账即自动全量重建；§2 补运维口径（窗口/成本/relink-limit 限流） |
| 低 | `selectDistinctScopes` 以 rag_entity 为源，"实体尽失但边残留"的漂移 scope 永不被对账发现 | 枚举改 rag_entity UNION rag_entity_cooccurrence（§6） |
| 低 | 对账 executor 无 shutdown/具名线程；指纹 SQL 依赖隐式 anynonarray‖text；lockScope 事务契约仅 javadoc 不可执行；AggregateResult 早退路径未写全 | §6（@PreDestroy + 具名线程）；§6（显式 ::text）；§3.1（isActualTransactionActive 运行时断言）；§4.1（早退返回 `AggregateResult(List.of(), false)`） |

### 第五轮审查修正（路线三采纳：死锁构造性消除）

决策背景：第三/四轮把"持锁事务 vs 自动提交多行 UPDATE"定性为无法消除的残余死锁面、靠检测
重试兜底。经外部评审论证，允许彻底改造时存在构造性消除路线（全量收编 + derive 分解），
用户拍板采纳路线三（混合形态）。

| 级别 | 变更 | 内容 |
|------|------|------|
| 架构 | 残余死锁面构造性消除 | §3.2.1：三表全部多行写者收编进 lockScope（无环论证四条）；`updateWeakTieScores`/`updateBridgeScores` 重 CTE 退役分解为锁外内存计算 + 锁内有序批量写回（read-compute-write）；验证 #1 断言升回"零 40P01" |
| 架构 | derive 写回原子化 | weak_tie/community/bridge/clearStale 同事务写回——消除"四步自动提交链中途失败留半代状态"漂移根因（验证 #19）；三分值共享同一图快照（原为两个快照混算） |
| 中 | 轻写者收编 | `updateEmbeddingBatch`（advisory 短事务，LLM 留锁外）、`markCommunityStale`（并入写事务步骤 9）；embedding 写回耗尽经 §6.2 重链接自愈（已核实项目无补嵌调度） |
| 中 | 防纪律腐化机制化 | `ScopeLockTemplate` 统一模板（防线一）+ 写回批次按 entityId 升序（防线二）+ `MapperWriteAuditTest` 静态审计（CI 机械检查替代 code review 人肉保证，验证 #20） |
| 中 | `WeakTieScoreCalculator` 语义保真 | 逐字对齐原 CTE（degree<100 预算、Jaccard embeddedness、仅更新有邻居对实体、hub/孤立不动、默认 0.5）；上线前以原 CTE 为金标准对拍（验证 #18） |
| 低 | §8-4 重试降级为保险层 | 死锁面消除后，lock_timeout+重试的动机改为"防审计漏网语句 + 池耗尽上界 + 耗尽后重链接兜底" |

### 第六轮审查修正（批量上传与多实例部署）

决策背景：用户指出需按多实例部署（分布式）方案定稿而非"单实例已知限制"，并要求核实批量
上传支持情况。核实结论：**批量上传已存在**（DocumentController:65 暴露 `uploadBatch`，
PersonalUploadStrategy:103 / TeamUploadStrategy:119 实现；上传循环串行落库但 ETL 消息逐个
入队、Redis 总线并发消费——同 scope 多文档并发写**今天即是真实路径**）；**Redisson 3.52
已在依赖中**且 outbox 已有 `RedissonLeadership` 领导选举先例（持续持锁、看门狗 10s 续约、
崩溃 ~30s 接管、Redis 缺失降级每实例执行）。

| 级别 | 变更 | 内容 |
|------|------|------|
| 架构 | "单实例假设"升级为多实例方案 | §3.7：advisory 锁/增量不变式/对账幂等天然跨实例（互斥在 PG 层）；唯一补丁 = `@Scheduled` 防重复用 `RedissonLeadership` 模式（`EntityGraphReconcileJob.schedule` leader 检查）；单→多实例迁移零正确性改动 |
| 高 | 批量上传使"排队者各占连接"成为常态（20 文档批 × etlIo 并发 8 > dev 池 5） | §3.6 写闸门（ScopeWriteGate）：per-scope `Semaphore(1)`，事务开启前 acquire（排队零 DB 连接占用）、tryAcquire 上限 120s 防 etlIo 线程独占、超时走 §6.2 重链接；闸门不取 DB 锁，不参与 §3.2.1 waits-for 图 |
| 中 | 批量 N 文档 → N 次 derive 的 CPU 放大 | §3.6 derive 防抖（DeriveDebouncer）：scope 级 trailing 合并（默认 30s，可关），批量 derive 次数 N → ⌈批时长/窗口⌉；结构分就绪延迟 ≤ 窗口（默认分兜底为现状语义） |
| 中 | §3.5-1/3 的事实与对策口径 | 排队深度按批量规模常态重估；池耗尽对策从"lock_timeout 单层"扩为"闸门（源头）+ lock_timeout（保险）"两层 |
| 低 | 验证与配置 | #12 语境更新；新增 #21（批量压测）/ #22（多实例防重与接管）；+`write-gate-wait-millis` / `derive-debounce-millis` 配置 |

### 第七轮审查修正（外部评审：重链接 scope 来源缺陷）

| 级别 | 问题 | 修正 |
|------|------|------|
| 严重 | §6.2 重链接探测 scope 来源未定义且自相矛盾：`relinkDocumentsMissingExtraction()` 无参调用置于 `selectDistinctScopes()` 循环外，探测 SQL 却是 per-scope 带参；若按 `selectDistinctScopes()`（rag_entity UNION rag_entity_cooccurrence）驱动，TRUNCATE 后首轮对账实体表为空集 → 重建空转，直接推翻 §2/§6.2"首轮对账自动重建"承诺与"迁移 → 首轮对账完成"降级窗口口径；§7 DocumentMapper 清单亦无对应查询 | 探测改为**全局查询、无 scope 参数**：`rag_document` 不在 TRUNCATE 清单内且行自带 user_id/team_id（恰为发布事件所需参数），重链接语义本就是文档驱动而非 scope 驱动——不需要任何 scope 枚举；`selectDistinctScopes()` 仅服务于对账主循环（实体数据为空即无事可对，正确）。补逐文档 try/catch 发布隔离、`ORDER BY update_time`、迁移前 6h 宽限期顺延口径（§6.2） |
| 低 | 承诺落地缺验证锚点 | 新增 #23：TRUNCATE 后首轮对账（实体表空）仍枚举到全部待重建文档、scope 主循环零迭代、重抽完成次日探测为空 |

### 第八轮审查修正（外部评审：表述冲突、口径统一与实施中间态）

| 级别 | 问题 | 修正 |
|------|------|------|
| 中 | "五步"vs"四步"自动提交链并存（§3.2.1/§6 阶段二注释 vs §6 derive 段/第五轮表）——实际链条 updateWeakTieScores → Leiden 写回 → bridge → clearStale 为四步 | 统一为"四步"并随文列明链条（§3.2.1/§6） |
| 中 | §1.2 以支柱一（写路径原子回滚→链接缺失）的情形概括支柱二——删除路径失败留下的是链接/事件残留（僵尸），兜底是孤儿清扫而非重链接 | 拆分为两条失败路径各归其位（§1.2） |
| 中 | §12 步骤 4-7 中间态缺口：门控切换与 derive 分解错位致 weak_tie 断更、embedding 死锁面回归 | 中间态原则：步骤 4 只做门控不切换（先挂旧重投影路径，门控与 derive 实现正交）；embedding 收编提前至步骤 4；仅步骤 3→4 窗口暴露死锁面且已由步骤 3 重试兜底（§12。该窗口口径经第九轮修正，见 §12/第九轮记录） |
| 低 | rag_chunk_entity"分区"口径：无 scope 列却两处称"按 (user_id, team_id) 分区"，与 §6"无 scope 列"字面冲突 | 改为"构造性归属唯一 scope"（chunk 属单一文档、链接仅关联同 scope 实体），结论不变（§3.2/§3.2.1 论证 3） |
| 低 | R1"第一个数据库操作"与实际首语句 setLockTimeout 字面冲突 | R1 改为"第一个**取锁的**数据库操作"，前置仅允许不取锁语句（§3.2） |
| 低 | §9"新增第二个发布方"计数错误（已有 FastTrack/Standard 两个） | 改为"第三个"（§9） |
| 低 | 验证清单 #21/#22 插在 #12 与 #13 之间 | 移至表尾（#20 后、#23 前），编号与交叉引用不变（§10） |
| 低 | §2 清空说明漏列 rag_entity_cooccurrence | 补齐四表（§2） |
| 低 | §3.6"结构分就绪延迟 ≤ 窗口"在进程崩溃场景不成立（防抖任务丢失、指纹门控不触发，最坏陈旧 ≤7 天）；community_stale 无消费者定义 | 措辞改为"常态 ≤ 窗口 + 异常态由 forceDerive 兜底"；community_stale 明确为观测信号（§3.6） |
| 低 | 写闸门未覆盖删除路径，批量/级联删除（清空团队空间）未评估 | 删除路径共用同一 per-scope 闸门（§3.6/§5），#21 扩为含级联清空压测 |

### 第九轮审查修正（内部一致性复核：交叉引用、口径与事实核对）

| 级别 | 问题 | 修正 |
|------|------|------|
| 中 | §12 中间态断言"死锁面仅步骤 3→4 窗口暴露"与 §3.2/§3.2.1 的死锁面分析自相矛盾：该窗口内 advisory 写路径尚未上线、`LockRetryExecutor` 尚无调用方，"advisory 持锁者 vs 自动提交多行写者"之面并不存在；真实混合暴露窗口为步骤 4→7（旧 derive 链自动提交多行写 vs 新 advisory 写事务）与 4→5（旧删除路径） | §12 口径改写：列明两个真实窗口及兜底（advisory 侧自步骤 4/5 起自带重试；旧链语句成死锁 victim 由下次写入/对账幂等自愈）；窗口内 #1 的"零 40P01"断言不适用（§12） |
| 中 | §3.2.1 轻写者"耗时上界 = 该文档实体数、与 scope 全局无关"与 §6 阶段二 derive 写回覆盖全 scope 实体矛盾（`batchUpdateCommunities`/`clearStaleFlag` 及写回批次均为 O(scope) 行）；多处"毫秒级写回"对大 scope 乐观 | 分类依据改为"SQL 内无计算"，分"文档局部写 / 全 scope 平坦写回"两档（§3.2.1）；§3.5-1/§6 阶段二耗时口径同步为"毫秒~秒级" |
| 低 | §3.5-1 事实错误："dev consumer batch-size=20"系误引（`etl.consumer.batch-size` 默认 5、dev/stable 无覆盖；dev yml 的 batch-size: 20 是百炼 embedding 模型参数） | 数值改正并注明排队深度实际由 etlIo 并发上限（core 4/max 8）决定，结论不变（§3.5-1） |
| 低 | 删除路径常态不触发 derive（指纹两侧同减、阶段〇恒阴性），结构分最坏陈旧至周一——§3.6 仅以"异常态"（防抖崩溃丢失）覆盖该陈旧窗口，删除路径常态未点明 | §5 补口径：设计取舍、与现状一致非回归；§3.6 交叉引用（§5） |
| 低 | §9 第八轮计数修正残留"之外"语病；§4.1 早退与写闸门先后未定义（按伪代码字面空批次先拿闸门）；§3.6 示例 `acquire` 与正文/§7 `tryAcquire` 命名不一 | 删"之外"（§9）；明确判空先于闸门（§4.1）；统一 `tryAcquire`（§3.6/§4.1/§5） |

---

## 12. 实施顺序建议

> **中间态原则（第八轮新增；第九轮修正死锁面窗口口径）**：每一步交付后系统必须处于可上线
> 的一致状态。关键的步骤 4-7 空窗期策略：步骤 4 只做"门控"不做"切换"——每文档后处理的
> graphChanged 门控先挂在**旧 `recomputeWeakTieScores` 全量重投影路径**上（门控与 derive 实现
> 正交，#14 在此形态下即可验证），derive 统一编排（步骤 7）上线前不引入 weak_tie 断更窗口；
> embedding 写回收编从步骤 7 **提前至步骤 4**（与 derive 无依赖，随写路径交付）。
>
> **残余死锁面的真实暴露窗口（第九轮修正：第八轮"仅步骤 3→4 窗口暴露"的断言与
> §3.2/§3.2.1 的死锁面分析矛盾——该窗口内 advisory 写路径尚未上线、`LockRetryExecutor`
> 尚无调用方，"advisory 持锁者 vs 自动提交多行写者"之面并不存在，存在的是今天既有的
> 自动提交互等现状）**：advisory 写/删事务（步骤 4/5 上线）与仍以自动提交多行写运行的旧代码
> 并发有两个窗口——① 步骤 4→7：graphChanged=true 时旧 derive 链（deleteByScope /
> projectCooccurrence / updateWeakTieScores / bridge 写回 / clearStale，全部自动提交多行写，
> 即 §3.2 第三轮分析的残余死锁面）；② 步骤 4→5：旧删除路径（无 advisory 的多行
> DELETE/UPDATE，步骤 5 才重写）。兜底：advisory 侧事务自步骤 4/5 起即包
> `LockRetryExecutor`（40P01 检测重试）；旧链语句成为 PG 死锁 victim 时，由该 scope 下一次
> 写入或每日对账自愈（delete+project 幂等收敛，§1.1/§6）。两个窗口内 #1 的"零 40P01"
> 断言不适用，验证口径为"检测重试恢复"（同第三轮历史断言）。

1. V30 迁移（本地库验证 TRUNCATE + 新列（含 `rag_document.entity_extracted_at`）+ 索引 + Down 注释核对）
2. Mapper 层（新 SQL + 单测：RETURNING 语义、`upsertIncrement` 冲突目标命中表达式索引、递减清零、源侧/表侧指纹、孤儿 EXISTS 探测与清扫 anti-join、重链接探测、`updateWeakTieBatch`/`updateBridgeBatch` 有序写回）
3. 锁基建：`lockScope` / `setLockTimeout`（含事务内运行时断言）/ `ScopeLockTemplate`（断言 → setLockTimeout → lockScope → body 统一封装，§3.2.1 防线一）/ `LockRetryExecutor`（SQLState 精确匹配 + jitter）+ 单测（40P01/40001/55P03 重试、耗尽抛出、QueryTimeoutException 不重试、自动提交下 lockScope 拒绝调用）
4. 写路径改造（`EntityCanonicalizationService`，markCommunityStale 并入写事务；`extractAndIndex` 完成标记；`ScopeWriteGate` 写闸门前置（§3.6，与步骤 3 同层交付）；**`EntityEmbeddingService` 写回收编**提前至本步（§3.2.1 轻写者，消除 embedding 部分的死锁面窗口；derive 链与旧删除路径的暴露窗口见上方第九轮口径）；后处理 graphChanged 门控**先挂旧 `recomputeWeakTieScores` 路径**）+ 验证清单 #2/#3/#4/#14
5. 删除路径重写（`EntityIndexCleanupService`，共用 `ScopeWriteGate`）+ 验证清单 #5/#6
6. `WeakTieScoreCalculator` + 对拍单测（#18，原 CTE 为金标准——先于 derive 改造落地，切换前双算可用）
7. derive 分解切换：每文档后处理从"门控后的旧重投影"切到 `DeriveDebouncer` → `communityDetectionJob.run` 统一编排（锁外 load/计算 → `ScopeLockTemplate` 锁内有序写回）；`updateWeakTieScores`/`updateBridgeScores`/`recomputeWeakTieScores` 退役删除 + 验证清单 #19
8. 对账任务（`EntityGraphReconcileJob`：阶段〇无锁探测 + 锁内条件重写 + 孤儿清扫 + 周强制 derive + §6.2 重链接检测 + 独立 executor 生命周期 + `RedissonLeadership` 防重（§3.7））+ 旧路径退役 + 验证清单 #7/#8/#9/#11/#13/#15/#16/#22
9. `MapperWriteAuditTest` 静态审计（#20 防呆自证；白名单 = 步骤 3–8 建立的全部 `ScopeLockTemplate` 调用点）
10. 超时治理（§8）+ 并发验证（#1 零死锁断言/#10/#12，etl-io 线程池压测 + 8:00 时段重叠上传 + embedding/derive 写回混合负载；据 #12 定标 `lock-timeout-millis` 默认值）+ 批量与多实例验证（#21 `uploadBatch` 20 文档压测 + 级联清空压测，含 derive 防抖合并断言；#22 双实例防重/接管）
