package com.smart.rag.evaluation.result;

import com.smart.rag.evaluation.runner.EvaluationRun;
import com.smart.rag.evaluation.runner.EvaluationRunStatus;
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

    private final RowMapper<EvaluationRun> runRowMapper = (rs, rowNum) -> new EvaluationRun(
            rs.getLong("id"),
            rs.getLong("dataset_id"),
            rs.getString("name"),
            rs.getString("config_snapshot"),
            EvaluationRunStatus.fromValue(rs.getString("status")),
            rs.getString("generation_model"),
            rs.getString("judge_model"),
            rs.getString("summary"),
            rs.getObject("started_at", OffsetDateTime.class),
            rs.getObject("completed_at", OffsetDateTime.class),
            rs.getObject("created_at", OffsetDateTime.class)
    );

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
                run.datasetId(),
                run.name(),
                run.configSnapshot(),
                run.status().getValue(),
                run.generationModel(),
                run.judgeModel());
        return new EvaluationRun(
                ((Number) result.get("id")).longValue(),
                run.datasetId(),
                run.name(),
                run.configSnapshot(),
                run.status(),
                run.generationModel(),
                run.judgeModel(),
                run.summary(),
                run.startedAt(),
                run.completedAt(),
                (OffsetDateTime) result.get("created_at")
        );
    }

    public void markRunStarted(long runId) {
        jdbc.update("UPDATE evaluation_run SET status = 'running', started_at = NOW() WHERE id = ?", runId);
    }

    public void updateRunStatus(long runId, EvaluationRunStatus status, String summary) {
        String statusValue = status.getValue();
        jdbc.update("""
                UPDATE evaluation_run SET status = ?, summary = ?::jsonb,
                    started_at = COALESCE(started_at, CASE WHEN status = 'pending' THEN NOW() END),
                    completed_at = CASE WHEN ? = 'completed' OR ? = 'failed' THEN NOW() ELSE completed_at END
                WHERE id = ?
                """,
                statusValue, summary, statusValue, statusValue, runId);
    }

    public Optional<EvaluationRun> findRunById(long id) {
        try {
            return Optional.of(jdbc.queryForObject(
                    "SELECT * FROM evaluation_run WHERE id = ?", runRowMapper, id));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public List<EvaluationRun> listRunsByDatasetId(long datasetId) {
        return jdbc.query(
                "SELECT * FROM evaluation_run WHERE dataset_id = ? ORDER BY created_at DESC",
                runRowMapper, datasetId);
    }

    public List<EvaluationRun> listRunsByStatus(EvaluationRunStatus status) {
        return jdbc.query(
                "SELECT * FROM evaluation_run WHERE status = ? ORDER BY created_at DESC",
                runRowMapper, status.getValue());
    }

    public int countRunsByDatasetId(long datasetId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM evaluation_run WHERE dataset_id = ?", Integer.class, datasetId);
        return count != null ? count : 0;
    }

    public int countRunsByStatus(EvaluationRunStatus status) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM evaluation_run WHERE status = ?", Integer.class, status.getValue());
        return count != null ? count : 0;
    }

    public int countRuns() {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM evaluation_run", Integer.class);
        return count != null ? count : 0;
    }

    // ======================== Result CRUD ========================

    public void insertResult(EvaluationResult result) {
        try {
            String stageSnapshotsJson = objectMapper.writeValueAsString(result.stageSnapshots());
            String retrievalMetricsJson = result.retrievalMetrics() != null
                    ? objectMapper.writeValueAsString(result.retrievalMetrics()) : null;

            jdbc.execute((Connection conn) -> {
                try (PreparedStatement ps = conn.prepareStatement("""
                        INSERT INTO evaluation_result
                            (run_id, item_id, item_question_snapshot, item_ground_truth_snapshot,
                             item_relevant_chunk_ids_snapshot, query_rewritten, retrieved_doc_ids,
                             generated_answer, stage_snapshots, retrieval_metrics,
                             generation_metrics, error, latency_ms)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb, ?, ?)
                        """)) {

                    ps.setLong(1, result.runId());
                    ps.setLong(2, result.itemId());
                    ps.setString(3, result.itemQuestionSnapshot());
                    ps.setString(4, result.itemGroundTruthSnapshot());

                    if (result.itemRelevantChunkIdsSnapshot() != null && !result.itemRelevantChunkIdsSnapshot().isEmpty()) {
                        ps.setArray(5, conn.createArrayOf("TEXT", result.itemRelevantChunkIdsSnapshot().toArray()));
                    } else {
                        ps.setNull(5, java.sql.Types.ARRAY);
                    }

                    ps.setString(6, result.queryRewritten());

                    if (result.retrievedDocIds() != null && !result.retrievedDocIds().isEmpty()) {
                        ps.setArray(7, conn.createArrayOf("TEXT", result.retrievedDocIds().toArray()));
                    } else {
                        ps.setNull(7, java.sql.Types.ARRAY);
                    }

                    ps.setString(8, result.generatedAnswer());
                    ps.setString(9, stageSnapshotsJson);
                    ps.setString(10, retrievalMetricsJson);
                    ps.setString(11, result.generationMetrics());
                    ps.setString(12, result.error());
                    ps.setInt(13, result.latencyMs());
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

    /**
     * 聚合某次运行的所有指标均值（用于 /compare 端点）。
     * <p>
     * retrieval_metrics 理论上每行都有；generation_metrics 可能为 null（generation 关闭时）。
     * 生成侧指标的 -1 哨兵值通过 {@code CASE WHEN ... >= 0 THEN ... END} 过滤——
     * Postgres 的 AVG 会忽略 CASE 缺省 ELSE 产生的 NULL。
     * </p>
     * <p>
     * 注意：聚合 SQL 使用 Postgres jsonb 函数（{@code ->>}、{@code ::jsonb}），依赖 PG 方言。
     * 空 run（无 result 行）返回一行全 NULL 值（AVG 对空集返回 NULL，COUNT 返回 0），
     * 不会抛 EmptyResultDataAccessException。
     * </p>
     *
     * @return Map 的 key 形如 {@code avg_recall} / {@code avg_faithfulness} / {@code total_items} 等；
     *         AVG 结果为 {@code BigDecimal}（可能 null），COUNT 为 {@code Long}
     */
    public Map<String, Object> aggregateMetricsByRunId(long runId) {
        return jdbc.queryForMap("""
                SELECT
                  AVG((retrieval_metrics::jsonb ->> 'recall')::double precision)              AS avg_recall,
                  AVG((retrieval_metrics::jsonb ->> 'precision')::double precision)           AS avg_precision,
                  AVG((retrieval_metrics::jsonb ->> 'mrr')::double precision)                 AS avg_mrr,
                  AVG((retrieval_metrics::jsonb ->> 'ndcg')::double precision)                AS avg_ndcg,
                  AVG((retrieval_metrics::jsonb ->> 'contextPrecision')::double precision)    AS avg_context_precision,
                  AVG(CASE WHEN (generation_metrics::jsonb ->> 'faithfulness')::double precision >= 0
                           THEN (generation_metrics::jsonb ->> 'faithfulness')::double precision END)    AS avg_faithfulness,
                  AVG(CASE WHEN (generation_metrics::jsonb ->> 'contextRecall')::double precision >= 0
                           THEN (generation_metrics::jsonb ->> 'contextRecall')::double precision END)    AS avg_context_recall,
                  AVG(CASE WHEN (generation_metrics::jsonb ->> 'answerRelevance')::double precision >= 0
                           THEN (generation_metrics::jsonb ->> 'answerRelevance')::double precision END)  AS avg_answer_relevance,
                  AVG(CASE WHEN (generation_metrics::jsonb ->> 'contextRelevance')::double precision >= 0
                           THEN (generation_metrics::jsonb ->> 'contextRelevance')::double precision END) AS avg_context_relevance,
                  AVG(CASE WHEN (generation_metrics::jsonb ->> 'answerCorrectness')::double precision >= 0
                           THEN (generation_metrics::jsonb ->> 'answerCorrectness')::double precision END) AS avg_answer_correctness,
                  AVG(CASE WHEN (generation_metrics::jsonb ->> 'noiseSensitivity')::double precision >= 0
                           THEN (generation_metrics::jsonb ->> 'noiseSensitivity')::double precision END) AS avg_noise_sensitivity,
                  AVG(CASE WHEN (generation_metrics::jsonb ->> 'contextPrecisionLlm')::double precision >= 0
                           THEN (generation_metrics::jsonb ->> 'contextPrecisionLlm')::double precision END) AS avg_context_precision_llm,
                  AVG(CASE WHEN (generation_metrics::jsonb ->> 'factualCorrectness')::double precision >= 0
                           THEN (generation_metrics::jsonb ->> 'factualCorrectness')::double precision END) AS avg_factual_correctness,
                  AVG(CASE WHEN (generation_metrics::jsonb ->> 'rougeL')::double precision >= 0
                           THEN (generation_metrics::jsonb ->> 'rougeL')::double precision END) AS avg_rouge_l,
                  AVG(CASE WHEN (generation_metrics::jsonb ->> 'bleu')::double precision >= 0
                           THEN (generation_metrics::jsonb ->> 'bleu')::double precision END) AS avg_bleu,
                  AVG(CASE WHEN (generation_metrics::jsonb ->> 'answerSimilarity')::double precision >= 0
                           THEN (generation_metrics::jsonb ->> 'answerSimilarity')::double precision END) AS avg_answer_similarity,
                  AVG(CASE WHEN (generation_metrics::jsonb ->> 'contextEntityRecall')::double precision >= 0
                           THEN (generation_metrics::jsonb ->> 'contextEntityRecall')::double precision END) AS avg_context_entity_recall,
                  AVG(CASE WHEN (generation_metrics::jsonb ->> 'contextUtilization')::double precision >= 0
                           THEN (generation_metrics::jsonb ->> 'contextUtilization')::double precision END) AS avg_context_utilization,
                  COUNT(*) AS total_items,
                  COUNT(*) FILTER (WHERE error IS NOT NULL) AS error_items
                FROM evaluation_result
                WHERE run_id = ?
                """, runId);
    }
}
