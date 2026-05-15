-- ============================================================
-- V14__vector_store_partitioning.sql — 按 owner_type/owner_id 分区
--
-- 将 vector_store 从普通表重构为分区表：
--   第一级 LIST(owner_type): 'user' | 'team'
--   第二级 HASH(owner_id): user 分 8 个子分区, team 分 4 个子分区
--
-- 查询时 PG 自动裁剪无关分区，等价于 Milvus Partition：
--   - 用户 A 检索 → 只扫 vector_store_user_p{A%8}
--   - 团队 T 检索 → 只扫 vector_store_team_p{T%4}
--
-- 注意：application-dev.yml 需同步修改：
--   spring.ai.vectorstore.pgvector.initialize-schema: false
-- （该文件在 .gitignore 中，不入库）
-- ============================================================
--
-- 分区键 owner_type/owner_id 由 GENERATED ALWAYS AS 从 metadata JSON 派生，
-- Spring AI 无需感知这两列，INSERT 时自动计算。
--
-- 主键改为复合主键 (id, owner_type, owner_id)，
-- PostgreSQL 分区表要求主键包含所有分区键。
-- Spring AI 的 DELETE 用 metadata 过滤，不依赖主键结构，无影响。
-- ============================================================

-- ============================================================
-- Step 1: 重命名旧表（保留数据，用于迁移）
-- ============================================================
ALTER TABLE vector_store RENAME TO vector_store_old;

-- ============================================================
-- Step 2: 创建分区主表
-- ============================================================
CREATE TABLE vector_store (
    id          UUID DEFAULT gen_random_uuid(),
    content     TEXT,
    metadata    JSON,
    embedding   VECTOR(1024),
    content_tsv TSVECTOR,
    -- 分区列：从 metadata JSON 自动派生，应用层无需感知
    owner_type  VARCHAR(4) NOT NULL GENERATED ALWAYS AS (
        CASE WHEN metadata->>'teamId' IS NOT NULL THEN 'team' ELSE 'user' END
    ) STORED,
    owner_id    BIGINT NOT NULL GENERATED ALWAYS AS (
        COALESCE(
            CASE WHEN metadata->>'teamId' IS NOT NULL
                THEN (metadata->>'teamId')::bigint
                ELSE (metadata->>'userId')::bigint
            END,
            0
        )
    ) STORED,
    PRIMARY KEY (id, owner_type, owner_id)
) PARTITION BY LIST (owner_type);

-- ============================================================
-- Step 3: 创建用户分区（LIST 'user' → HASH 8 子分区）
-- ============================================================
CREATE TABLE vector_store_user PARTITION OF vector_store
    FOR VALUES IN ('user') PARTITION BY HASH (owner_id);

CREATE TABLE vector_store_user_p0 PARTITION OF vector_store_user
    FOR VALUES WITH (MODULUS 8, REMAINDER 0);
CREATE TABLE vector_store_user_p1 PARTITION OF vector_store_user
    FOR VALUES WITH (MODULUS 8, REMAINDER 1);
CREATE TABLE vector_store_user_p2 PARTITION OF vector_store_user
    FOR VALUES WITH (MODULUS 8, REMAINDER 2);
CREATE TABLE vector_store_user_p3 PARTITION OF vector_store_user
    FOR VALUES WITH (MODULUS 8, REMAINDER 3);
CREATE TABLE vector_store_user_p4 PARTITION OF vector_store_user
    FOR VALUES WITH (MODULUS 8, REMAINDER 4);
CREATE TABLE vector_store_user_p5 PARTITION OF vector_store_user
    FOR VALUES WITH (MODULUS 8, REMAINDER 5);
CREATE TABLE vector_store_user_p6 PARTITION OF vector_store_user
    FOR VALUES WITH (MODULUS 8, REMAINDER 6);
CREATE TABLE vector_store_user_p7 PARTITION OF vector_store_user
    FOR VALUES WITH (MODULUS 8, REMAINDER 7);

-- ============================================================
-- Step 4: 创建团队分区（LIST 'team' → HASH 4 子分区）
-- ============================================================
CREATE TABLE vector_store_team PARTITION OF vector_store
    FOR VALUES IN ('team') PARTITION BY HASH (owner_id);

CREATE TABLE vector_store_team_p0 PARTITION OF vector_store_team
    FOR VALUES WITH (MODULUS 4, REMAINDER 0);
CREATE TABLE vector_store_team_p1 PARTITION OF vector_store_team
    FOR VALUES WITH (MODULUS 4, REMAINDER 1);
CREATE TABLE vector_store_team_p2 PARTITION OF vector_store_team
    FOR VALUES WITH (MODULUS 4, REMAINDER 2);
CREATE TABLE vector_store_team_p3 PARTITION OF vector_store_team
    FOR VALUES WITH (MODULUS 4, REMAINDER 3);

-- ============================================================
-- Step 5: 迁移旧表数据到分区表
-- owner_type 和 owner_id 由 GENERATED ALWAYS 自动计算，无需显式插入
-- ============================================================
INSERT INTO vector_store (id, content, metadata, embedding, content_tsv)
SELECT id, content, metadata, embedding, content_tsv
FROM vector_store_old;

-- ============================================================
-- Step 6: 重建 HNSW 索引（在分区主表上创建，自动传播到所有子分区）
-- PG 11+ 支持 CREATE INDEX ON partitioned table，自动为每个子分区创建索引
-- ============================================================
CREATE INDEX idx_vector_store_embedding
    ON vector_store USING hnsw (embedding vector_cosine_ops)
    WITH (m = 32, ef_construction = 128);

-- ============================================================
-- Step 7: 重建 GIN 索引（BM25 全文检索）
-- ============================================================
CREATE INDEX idx_vector_store_content_tsv
    ON vector_store USING GIN (content_tsv);

-- ============================================================
-- Step 8: 重建 content_tsv 自动更新触发器
-- 触发器函数 vector_store_content_tsv_trigger() 由 V2 创建，仍然可用
-- ============================================================
CREATE TRIGGER trg_vector_store_content_tsv
    BEFORE INSERT OR UPDATE OF content ON vector_store
    FOR EACH ROW
    EXECUTE FUNCTION vector_store_content_tsv_trigger();

-- ============================================================
-- Step 9: 验证迁移（数据量一致性检查）
-- 如果不一致，整个事务回滚
-- ============================================================
DO $$
DECLARE
    old_count BIGINT;
    new_count BIGINT;
BEGIN
    SELECT COUNT(*) INTO old_count FROM vector_store_old;
    SELECT COUNT(*) INTO new_count FROM vector_store;
    IF old_count <> new_count THEN
        RAISE EXCEPTION 'Data migration mismatch: old=%, new=%', old_count, new_count;
    END IF;
    RAISE NOTICE 'Migration verified: % rows migrated successfully', new_count;
END $$;

-- ============================================================
-- Step 10: 删除旧表（验证通过后才执行）
-- ============================================================
DROP TABLE vector_store_old;
