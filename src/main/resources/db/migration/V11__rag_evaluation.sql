-- RAG 评估系统数据表
-- 对应设计文档: .trellis/tasks/05-16-rag-evaluation/design.md

-- 评估数据集
CREATE TABLE evaluation_dataset (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(200) NOT NULL,
    description TEXT,
    version     INT NOT NULL DEFAULT 1,
    source      VARCHAR(50) NOT NULL DEFAULT 'hybrid',
    judge_model VARCHAR(100),
    item_count  INT NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- 评估数据项
CREATE TABLE evaluation_dataset_item (
    id                  BIGSERIAL PRIMARY KEY,
    dataset_id          BIGINT NOT NULL REFERENCES evaluation_dataset(id) ON DELETE CASCADE,
    question            TEXT NOT NULL,
    ground_truth_answer TEXT,
    relevant_chunk_ids  TEXT[],
    relevant_content    TEXT,
    tags                VARCHAR(100)[],
    status              VARCHAR(20) NOT NULL DEFAULT 'draft',
    seq                 INT NOT NULL DEFAULT 0,
    UNIQUE(dataset_id, seq)
);
CREATE INDEX idx_eval_item_dataset ON evaluation_dataset_item(dataset_id);

-- 评估运行
CREATE TABLE evaluation_run (
    id               BIGSERIAL PRIMARY KEY,
    dataset_id       BIGINT NOT NULL REFERENCES evaluation_dataset(id),
    name             VARCHAR(200),
    config_snapshot  JSONB,
    status           VARCHAR(20) NOT NULL DEFAULT 'pending',
    generation_model VARCHAR(100),
    judge_model      VARCHAR(100),
    summary          JSONB,
    started_at       TIMESTAMPTZ,
    completed_at     TIMESTAMPTZ,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_eval_run_dataset ON evaluation_run(dataset_id);
CREATE INDEX idx_eval_run_status ON evaluation_run(status);

-- 评估结果
CREATE TABLE evaluation_result (
    id                               BIGSERIAL PRIMARY KEY,
    run_id                           BIGINT NOT NULL REFERENCES evaluation_run(id) ON DELETE CASCADE,
    item_id                          BIGINT NOT NULL REFERENCES evaluation_dataset_item(id),
    item_question_snapshot           TEXT,
    item_ground_truth_snapshot       TEXT,
    item_relevant_chunk_ids_snapshot TEXT[],
    query_rewritten                  TEXT,
    retrieved_doc_ids                TEXT[],
    generated_answer                 TEXT,
    stage_snapshots                  JSONB,
    retrieval_metrics                JSONB,
    generation_metrics               JSONB,
    error                            TEXT,
    latency_ms                       INT
);
CREATE INDEX idx_eval_result_run ON evaluation_result(run_id);
