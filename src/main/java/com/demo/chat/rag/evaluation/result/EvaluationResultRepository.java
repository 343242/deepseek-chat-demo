package com.demo.chat.rag.evaluation.result;

import com.demo.chat.rag.evaluation.runner.EvaluationRun;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 评估结果持久化
 */
@Repository
public class EvaluationResultRepository {

    private static final Logger log = LoggerFactory.getLogger(EvaluationResultRepository.class);

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    private final RowMapper<EvaluationRun> runRowMapper = (rs, rowNum) -> {
        EvaluationRun run = new EvaluationRun();
        run.setId(rs.getLong("id"));
        run.setDatasetId(rs.getLong("dataset_id"));
        run.setName(rs.getString("name"));
        run.setConfigSnapshot(rs.getString("config_snapshot"));
        run.setStatus(rs.getString("status"));
        run.setGenerationModel(rs.getString("generation_model"));
        run.setJudgeModel(rs.getString("judge_model"));
        run.setSummary(rs.getString("summary"));
        run.setStartedAt(rs.getObject("started_at", OffsetDateTime.class));
        run.setCompletedAt(rs.getObject("completed_at", OffsetDateTime.class));
        run.setCreatedAt(rs.getObject("created_at", OffsetDateTime.class));
        return run;
    };

    public EvaluationResultRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    // ======================== Run CRUD ========================

    public EvaluationRun insertRun(EvaluationRun run) {
        Map<String, Object> result = jdbc.queryForMap("""
                INSERT INTO evaluation_run (dataset_id, name, config_snapshot, status, generation_model, judge_model)
                VALUES (?, ?, ?::jsonb, ?, ?, ?)
                RETURNING id, created_at
                """,
                run.getDatasetId(),
                run.getName(),
                run.getConfigSnapshot(),
                run.getStatus(),
                run.getGenerationModel(),
                run.getJudgeModel());
        run.setId(((Number) result.get("id")).longValue());
        run.setCreatedAt((OffsetDateTime) result.get("created_at"));
        return run;
    }

    public void updateRunStatus(long runId, String status, String summary) {
        jdbc.update("""
                UPDATE evaluation_run SET status = ?, summary = ?::jsonb,
                    started_at = COALESCE(started_at, CASE WHEN status = 'pending' THEN NOW() END),
                    completed_at = CASE WHEN ? IN ('completed', 'failed') THEN NOW() ELSE completed_at END
                WHERE id = ?
                """, status, summary, status, runId);
    }

    public void markRunStarted(long runId) {
        jdbc.update("UPDATE evaluation_run SET status = 'running', started_at = NOW() WHERE id = ?", runId);
    }

    public Optional<EvaluationRun> findRunById(long id) {
        try {
            return Optional.of(jdbc.queryForObject(
                    "SELECT * FROM evaluation_run WHERE id = ?", runRowMapper, id));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public List<EvaluationRun> listRuns(int page, int size, String status) {
        if (status != null && !status.isBlank()) {
            return jdbc.query(
                    "SELECT * FROM evaluation_run WHERE status = ? ORDER BY created_at DESC LIMIT ? OFFSET ?",
                    runRowMapper, status, size, page * size);
        }
        return jdbc.query(
                "SELECT * FROM evaluation_run ORDER BY created_at DESC LIMIT ? OFFSET ?",
                runRowMapper, size, page * size);
    }

    public int countRuns(String status) {
        if (status != null && !status.isBlank()) {
            Integer count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM evaluation_run WHERE status = ?", Integer.class, status);
            return count != null ? count : 0;
        }
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM evaluation_run", Integer.class);
        return count != null ? count : 0;
    }

    // ======================== Result CRUD ========================

    public void insertResult(EvaluationResult result) {
        try {
            String stageSnapshotsJson = objectMapper.writeValueAsString(result.getStageSnapshots());
            String retrievalMetricsJson = result.getRetrievalMetrics() != null
                    ? objectMapper.writeValueAsString(result.getRetrievalMetrics()) : null;

            jdbc.execute((Connection conn) -> {
                try (PreparedStatement ps = conn.prepareStatement("""
                        INSERT INTO evaluation_result
                            (run_id, item_id, item_question_snapshot, item_ground_truth_snapshot,
                             item_relevant_chunk_ids_snapshot, query_rewritten, retrieved_doc_ids,
                             generated_answer, stage_snapshots, retrieval_metrics,
                             generation_metrics, error, latency_ms)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb, ?, ?)
                        """)) {

                    ps.setLong(1, result.getRunId());
                    ps.setLong(2, result.getItemId());
                    ps.setString(3, result.getItemQuestionSnapshot());
                    ps.setString(4, result.getItemGroundTruthSnapshot());

                    if (result.getItemRelevantChunkIdsSnapshot() != null && !result.getItemRelevantChunkIdsSnapshot().isEmpty()) {
                        ps.setArray(5, conn.createArrayOf("TEXT", result.getItemRelevantChunkIdsSnapshot().toArray()));
                    } else {
                        ps.setNull(5, java.sql.Types.ARRAY);
                    }

                    ps.setString(6, result.getQueryRewritten());

                    if (result.getRetrievedDocIds() != null && !result.getRetrievedDocIds().isEmpty()) {
                        ps.setArray(7, conn.createArrayOf("TEXT", result.getRetrievedDocIds().toArray()));
                    } else {
                        ps.setNull(7, java.sql.Types.ARRAY);
                    }

                    ps.setString(8, result.getGeneratedAnswer());
                    ps.setString(9, stageSnapshotsJson);
                    ps.setString(10, retrievalMetricsJson);
                    ps.setString(11, result.getGenerationMetrics());
                    ps.setString(12, result.getError());
                    ps.setInt(13, result.getLatencyMs());
                    ps.executeUpdate();
                }
                return null;
            });
        } catch (Exception e) {
            log.error("Failed to insert evaluation result: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to persist evaluation result", e);
        }
    }

    public List<Map<String, Object>> listResultsByRunId(long runId, int page, int size) {
        return jdbc.queryForList(
                "SELECT id, run_id, item_id, item_question_snapshot, retrieval_metrics, generation_metrics, error, latency_ms " +
                        "FROM evaluation_result WHERE run_id = ? ORDER BY id LIMIT ? OFFSET ?",
                runId, size, page * size);
    }

    public int countResultsByRunId(long runId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM evaluation_result WHERE run_id = ?", Integer.class, runId);
        return count != null ? count : 0;
    }
}
