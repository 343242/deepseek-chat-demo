-- V29__evaluation_dataset_gen_run.sql — KG 测试集生成异步任务表
-- 任务：.trellis/tasks/08-18-ragas-testset-generation
-- 风格对齐 V11（evaluation_*）与 V12（状态 CHECK）；镜像 evaluation_run 的异步生命周期，
-- 由 GenerationJobService 驱动（evalExecutor 虚拟线程 + evalRunSemaphore 背压）。

CREATE TABLE evaluation_dataset_gen_run (
    id            BIGSERIAL PRIMARY KEY,
    name          VARCHAR(200) NOT NULL,
    user_id       BIGINT NOT NULL,
    status        VARCHAR(20) NOT NULL DEFAULT 'pending',
    config        JSONB,
    progress      JSONB,
    dataset_id    BIGINT REFERENCES evaluation_dataset(id),
    error         TEXT,
    started_at    TIMESTAMPTZ,
    completed_at  TIMESTAMPTZ,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_gen_run_status CHECK (status IN ('pending', 'running', 'completed', 'failed'))
);

CREATE INDEX idx_eval_gen_run_status ON evaluation_dataset_gen_run(status);
CREATE INDEX idx_eval_gen_run_dataset ON evaluation_dataset_gen_run(dataset_id);
