package com.smart.rag.evaluation.testset;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 测试集生成任务 CRUD（{@code evaluation_dataset_gen_run}，JdbcTemplate，
 * 沿用 evaluation 模块的仓储惯例）。
 */
@Component
public class GenerationJobRepository {

    private final JdbcTemplate jdbc;

    public GenerationJobRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(long userId, String name, String configJson) {
        return jdbc.queryForObject("""
                INSERT INTO evaluation_dataset_gen_run (name, user_id, status, config)
                VALUES (?, ?, 'pending', ?::jsonb)
                RETURNING id
                """, Long.class, name, userId, configJson);
    }

    public void markRunning(long jobId) {
        jdbc.update("""
                UPDATE evaluation_dataset_gen_run
                SET status = 'running', started_at = NOW(), error = NULL
                WHERE id = ?
                """, jobId);
    }

    public void markCompleted(long jobId, long datasetId) {
        jdbc.update("""
                UPDATE evaluation_dataset_gen_run
                SET status = 'completed', dataset_id = ?, completed_at = NOW()
                WHERE id = ?
                """, datasetId, jobId);
    }

    public void markFailed(long jobId, String error) {
        jdbc.update("""
                UPDATE evaluation_dataset_gen_run
                SET status = 'failed', error = ?, completed_at = NOW()
                WHERE id = ?
                """, truncate(error), jobId);
    }

    /**
     * 仅当任务仍处于 pending/running 时标记 FAILED（条件更新）。
     * <p>
     * sweeper 的"查询 → 检查内存 sink → 更新"是非原子序列：任务可能在检查之后、更新之前
     * 已被执行线程收尾，无条件 UPDATE 会把终态改回 failed。返回受影响行数供调用方判定。
     */
    public int markFailedIfPendingOrRunning(long jobId, String error) {
        return jdbc.update("""
                UPDATE evaluation_dataset_gen_run
                SET status = 'failed', error = ?, completed_at = NOW()
                WHERE id = ? AND status IN ('pending', 'running')
                """, truncate(error), jobId);
    }

    /** 进度落库（SSE 断线后状态查询仍可见最近进度）。 */
    public void updateProgress(long jobId, String progressJson) {
        jdbc.update(
                "UPDATE evaluation_dataset_gen_run SET progress = ?::jsonb WHERE id = ?",
                progressJson, jobId);
    }

    public Optional<GenerationJobRecord> find(long jobId) {
        var list = jdbc.query(
                "SELECT * FROM evaluation_dataset_gen_run WHERE id = ?", this::mapRow, jobId);
        return list.stream().findFirst();
    }

    public List<GenerationJobRecord> listByStatus(String status) {
        return jdbc.query(
                "SELECT * FROM evaluation_dataset_gen_run WHERE status = ? ORDER BY id",
                this::mapRow, status);
    }

    private GenerationJobRecord mapRow(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new GenerationJobRecord(
                rs.getLong("id"),
                rs.getString("name"),
                rs.getLong("user_id"),
                rs.getString("status"),
                rs.getString("config"),
                rs.getString("progress"),
                rs.getObject("dataset_id") == null ? null : rs.getLong("dataset_id"),
                rs.getString("error"),
                toOffset(rs.getTimestamp("started_at")),
                toOffset(rs.getTimestamp("completed_at")),
                toOffset(rs.getTimestamp("created_at")));
    }

    private static OffsetDateTime toOffset(Timestamp ts) {
        return ts == null ? null : ts.toInstant().atOffset(java.time.ZoneOffset.UTC);
    }

    private static String truncate(String s) {
        return s == null ? null : (s.length() <= 2000 ? s : s.substring(0, 2000));
    }
}
