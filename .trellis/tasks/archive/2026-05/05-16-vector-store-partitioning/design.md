# vector_store 表分区 + HNSW 优化设计

## 状态：已撤回

撤回原因：Spring AI PgVectorStore 的 INSERT 使用 `ON CONFLICT (id) DO UPDATE`，
分区表唯一约束必须包含分区键 `(id, owner_type, owner_id)`，无法提供单列 `id` 唯一约束。
当前 iterative scan + HNSW 参数调优（V13）已足够，等数据量需要分区时再处理。

## 背景

当前 `vector_store` 表由 Spring AI PgVectorStore 自动管理（`initialize-schema: true`），存在以下问题：

1. **过滤检索召回不足**：HNSW 先 ANN 搜索再应用 metadata JSON 过滤，ef_search=64 返回的结果中可能只有少量满足 userId/teamId 条件
2. **全表扫描**：即使用户只查自己的数据，PG 也要在 HNSW 索引中搜索所有用户的向量
3. **HNSW 参数不可控**：Spring AI 创建索引时使用 pgvector 默认值（m=16, ef_construction=64），无法自定义

## 目标

1. 开启 pgvector 0.8.2 的 **iterative_scan**（解决过滤召回不足）
2. 按 **userId / teamId 分区**，查询时自动裁剪无关分区（等价于 Milvus Partition）
3. **完全接管建表**，Spring AI 只负责读写，不再管理 DDL
4. HNSW 索引参数：m=32, ef_construction=128, ef_search=64

## 分区策略

### 核心问题：metadata 是 JSON，不能直接做分区键

PostgreSQL 声明式分区要求分区键是普通列。需要用 **GENERATED ALWAYS AS ... STORED** 从 metadata JSON 中提取：

```sql
-- 分区列 1：所有者类型
owner_type VARCHAR(4) GENERATED ALWAYS AS (
    CASE WHEN metadata->>'teamId' IS NOT NULL THEN 'team' ELSE 'user' END
) STORED;

-- 分区列 2：所有者 ID
owner_id BIGINT GENERATED ALWAYS AS (
    CASE WHEN metadata->>'teamId' IS NOT NULL
        THEN (metadata->>'teamId')::bigint
        ELSE (metadata->>'userId')::bigint
    END
) STORED;
```

### 两级分区

```
vector_store (主表，PARTITION BY LIST (owner_type))
├── vector_store_user (PARTITION BY HASH (owner_id))
│   ├── vector_store_user_p0   -- owner_id % 8 = 0
│   ├── vector_store_user_p1   -- owner_id % 8 = 1
│   ├── ...
│   └── vector_store_user_p7   -- owner_id % 8 = 7
└── vector_store_team (PARTITION BY HASH (owner_id))
    ├── vector_store_team_p0   -- owner_id % 4 = 0
    ├── vector_store_team_p1   -- owner_id % 4 = 1
    ├── vector_store_team_p2   -- owner_id % 4 = 2
    └── vector_store_team_p3   -- owner_id % 4 = 3
```

**为什么 user 分 8 个、team 分 4 个？**
- 用户数据量大（N 个用户 × N 份文档），需要更多分区减少单分区大小
- 团队数量远少于用户，4 个足够；团队文档集中度高，分区太多反而增加开销

### 查询裁剪效果

| 查询模式 | 当前 | 分区后 |
|---------|------|-------|
| 用户 A 检索 | 全表 HNSW 搜索 → 过滤 | 只扫 `vector_store_user_p{A%8}`（1/8 数据） |
| 团队 T 检索 | 全表 HNSW 搜索 → 过滤 | 只扫 `vector_store_team_p{T%4}`（1/4 数据） |
| 全局搜索（评估/管理） | 全表 | 全部分区（可接受，低频操作） |

## 表结构

```sql
CREATE TABLE vector_store (
    id          UUID DEFAULT gen_random_uuid() PRIMARY KEY,
    content     TEXT,
    metadata    JSON,
    embedding   VECTOR(1024),
    content_tsv TSVECTOR,
    owner_type  VARCHAR(4) GENERATED ALWAYS AS (
        CASE WHEN metadata->>'teamId' IS NOT NULL THEN 'team' ELSE 'user' END
    ) STORED,
    owner_id    BIGINT GENERATED ALWAYS AS (
        CASE WHEN metadata->>'teamId' IS NOT NULL
            THEN (metadata->>'teamId')::bigint
            ELSE (metadata->>'userId')::bigint
        END
    ) STORED
) PARTITION BY LIST (owner_type);
```

### 子分区 DDL 模板

```sql
-- 用户分区（8 个）
CREATE TABLE vector_store_user PARTITION OF vector_store
    FOR VALUES IN ('user') PARTITION BY HASH (owner_id);

CREATE TABLE vector_store_user_p0 PARTITION OF vector_store_user
    FOR VALUES WITH (MODULUS 8, REMAINDER 0);
-- ... p1 ~ p7

-- 团队分区（4 个）
CREATE TABLE vector_store_team PARTITION OF vector_store
    FOR VALUES IN ('team') PARTITION BY HASH (owner_id);

CREATE TABLE vector_store_team_p0 PARTITION OF vector_store_team
    FOR VALUES WITH (MODULUS 4, REMAINDER 0);
-- ... p1 ~ p3
```

### HNSW 索引（每个子分区独立）

```sql
CREATE INDEX vector_store_user_p0_embedding_idx
    ON vector_store_user_p0 USING hnsw (embedding vector_cosine_ops)
    WITH (m = 32, ef_construction = 128);
-- 每个子分区各一个
```

### BM25 GIN 索引 + 触发器

BM25 触发器和 GIN 索引继续沿用，需要在每个子分区上创建 GIN 索引：
```sql
CREATE INDEX idx_vector_store_user_p0_content_tsv
    ON vector_store_user_p0 USING GIN (content_tsv);
```

触发器定义在主表上，自动传播到所有子分区。

## Spring AI 兼容性

### 关闭自动建表

```yaml
spring.ai.vectorstore.pgvector:
  initialize-schema: false    # 改为 false，Flyway 接管
  index-type: HNSW
  distance-type: COSINE_DISTANCE
  dimensions: 1024
  table-name: vector_store
```

Spring AI PgVectorStore 的 `afterPropertiesSet()` 在 `initialize-schema: false` 时跳过所有 DDL，只做 schema 校验（如果 `vectorTableValidationsEnabled=true`）。

### 校验兼容性

Spring AI PgVectorSchemaValidator 检查：
- ✅ `id` 列存在（UUID 类型）
- ✅ `content` 列存在（TEXT）
- ✅ `metadata` 列存在（JSON）
- ✅ `embedding` 列存在（VECTOR）
- ✅ 多出来的 `content_tsv`、`owner_type`、`owner_id` 列不影响校验
- ⚠️ 分区表的主键必须包含分区键 — **需要处理**

### 主键约束

PostgreSQL 分区表要求主键包含所有分区键。这意味着：

```sql
-- ❌ 不行：PRIMARY KEY (id) 不包含 owner_type, owner_id
-- ✅ 必须：PRIMARY KEY (id, owner_type, owner_id)
```

Spring AI 不依赖主键做 WHERE 条件（它用 metadata->>'documentId' 做删除），所以改为复合主键不影响功能。

但 `id` 列原来是 `UUID DEFAULT gen_random_uuid() PRIMARY KEY`，需要改为：

```sql
id UUID DEFAULT gen_random_uuid(),
PRIMARY KEY (id, owner_type, owner_id)
```

## pgvector 参数设置

```sql
-- 开启 iterative scan（解决过滤后召回不足）
ALTER DATABASE chatdemo SET hnsw.iterative_scan = on;
-- 限制最大扫描行数（防止单次查询过慢）
ALTER DATABASE chatdemo SET hnsw.max_scan_tuples = 20000;
-- 查询时搜索宽度
ALTER DATABASE chatdemo SET hnsw.ef_search = 64;
```

## 状态：已实施

### 现有数据迁移

由于 V2 已创建非分区表，需要：

1. 创建新的分区表（临时名称 `vector_store_new`）
2. 从旧表迁移数据（generated column 自动填充）
3. 交换表名：`vector_store` → `vector_store_old`，`vector_store_new` → `vector_store`
4. 验证后删除 `vector_store_old`

或更安全的做法：
1. 在事务外创建分区表结构
2. `INSERT INTO vector_store_new SELECT ... FROM vector_store`
3. 在事务中 `DROP TABLE vector_store; ALTER TABLE vector_store_new RENAME TO vector_store;`

### Flyway 脚本规划

| 脚本 | 内容 |
|------|------|
| V13 | iterative_scan + ef_search（**已完成，需补充 iterative_scan**）|
| V14 | 表分区重构（新建分区表 + 数据迁移 + 交换表名 + 重建索引 + 重建触发器）|

## 风险

1. **锁表**：数据迁移期间 `vector_store` 不可写入。数据量小时（<10 万行）秒级完成
2. **Spring AI 版本升级**：未来升级可能改变 schema 校验逻辑，需关注
3. **复合主键**：`VectorStoreMapper` 和 `VectorStoreLoader` 用 metadata 过滤做删除，不受影响
4. **新增子分区**：如果 user 或 team 数量暴增，需要增加 HASH 分区数（需重建分区表）

## 文件改动清单

| 文件 | 改动 |
|------|------|
| `V13__hnsw_tuning.sql` | 补充 `iterative_scan` 和 `max_scan_tuples` |
| `V14__vector_store_partitioning.sql`（新建）| 分区表 DDL + 数据迁移 + 索引 + 触发器 |
| `application-dev.yml` | `initialize-schema: false` |
| `HybridDocumentRetriever.java` | BM25 查询利用 `owner_type`/`owner_id` 列替代 metadata JSON 过滤（性能优化，可选） |
| `VectorStoreMapper.java` | 同上，BM25 search 方法可选利用分区列 |

## 分区列优化（可选 Phase 2）

当前 BM25 查询用 `metadata->>'userId' = ?`，JSON 解析有开销。
分区后可以用 `owner_type = 'user' AND owner_id = ?` 替代，直接走分区裁剪，不需要 JSON 解析。

这是 Phase 2 优化，Phase 1 先让分区跑起来。
