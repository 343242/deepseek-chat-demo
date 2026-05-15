package com.demo.chat.rag.evaluation.dataset;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 数据集持久化（JdbcTemplate）
 * <p>
 * 直接使用 SQL 操作，不引入 JPA/MyBatis 依赖。
 * TEXT[] 类型通过 PostgreSQL JDBC 驱动的 Array 支持处理。
 * </p>
 */
@Repository
public class DatasetRepository {

    private static final Logger log = LoggerFactory.getLogger(DatasetRepository.class);

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    private final RowMapper<EvaluationDataset> datasetRowMapper = (rs, rowNum) -> {
        EvaluationDataset ds = new EvaluationDataset();
        ds.setId(rs.getLong("id"));
        ds.setName(rs.getString("name"));
        ds.setDescription(rs.getString("description"));
        ds.setVersion(rs.getInt("version"));
        ds.setSource(rs.getString("source"));
        ds.setJudgeModel(rs.getString("judge_model"));
        ds.setItemCount(rs.getInt("item_count"));
        ds.setCreatedAt(rs.getObject("created_at", OffsetDateTime.class));
        ds.setUpdatedAt(rs.getObject("updated_at", OffsetDateTime.class));
        return ds;
    };

    private final RowMapper<EvaluationDatasetItem> itemRowMapper = (rs, rowNum) -> {
        EvaluationDatasetItem item = new EvaluationDatasetItem();
        item.setId(rs.getLong("id"));
        item.setDatasetId(rs.getLong("dataset_id"));
        item.setQuestion(rs.getString("question"));
        item.setGroundTruthAnswer(rs.getString("ground_truth_answer"));

        var chunkIdsArray = rs.getArray("relevant_chunk_ids");
        if (chunkIdsArray != null) {
            String[] ids = (String[]) chunkIdsArray.getArray();
            item.setRelevantChunkIds(new HashSet<>(List.of(ids)));
        }

        item.setRelevantContent(rs.getString("relevant_content"));

        var tagsArray = rs.getArray("tags");
        if (tagsArray != null) {
            String[] tags = (String[]) tagsArray.getArray();
            item.setTags(List.of(tags));
        }

        item.setStatus(rs.getString("status"));
        item.setSeq(rs.getInt("seq"));
        return item;
    };

    public DatasetRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    // ======================== Dataset CRUD ========================

    public EvaluationDataset insertDataset(EvaluationDataset dataset) {
        String sql = """
                INSERT INTO evaluation_dataset (name, description, version, source, judge_model, item_count)
                VALUES (?, ?, ?, ?, ?, ?)
                RETURNING id, created_at, updated_at
                """;
        Map<String, Object> result = jdbc.queryForMap(sql,
                dataset.getName(),
                dataset.getDescription(),
                dataset.getVersion(),
                dataset.getSource(),
                dataset.getJudgeModel(),
                dataset.getItemCount());
        dataset.setId(((Number) result.get("id")).longValue());
        dataset.setCreatedAt((OffsetDateTime) result.get("created_at"));
        dataset.setUpdatedAt((OffsetDateTime) result.get("updated_at"));
        return dataset;
    }

    public Optional<EvaluationDataset> findDatasetById(long id) {
        try {
            return Optional.of(jdbc.queryForObject(
                    "SELECT * FROM evaluation_dataset WHERE id = ?", datasetRowMapper, id));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public List<EvaluationDataset> listDatasets(int page, int size) {
        return jdbc.query(
                "SELECT * FROM evaluation_dataset ORDER BY created_at DESC LIMIT ? OFFSET ?",
                datasetRowMapper, size, page * size);
    }

    public int countDatasets() {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM evaluation_dataset", Integer.class);
        return count != null ? count : 0;
    }

    public void updateDatasetItemCount(long datasetId, int itemCount) {
        jdbc.update("UPDATE evaluation_dataset SET item_count = ?, updated_at = NOW() WHERE id = ?",
                itemCount, datasetId);
    }

    // ======================== DatasetItem CRUD ========================

    /**
     * 插入单条数据项（使用 ConnectionCallback 安全处理 Array 类型）
     */
    public EvaluationDatasetItem insertItem(EvaluationDatasetItem item) {
        jdbc.execute((Connection conn) -> {
            try (PreparedStatement ps = conn.prepareStatement("""
                    INSERT INTO evaluation_dataset_item
                        (dataset_id, question, ground_truth_answer, relevant_chunk_ids, relevant_content, tags, status, seq)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    RETURNING id
                    """)) {
                ps.setLong(1, item.getDatasetId());
                ps.setString(2, item.getQuestion());
                ps.setString(3, item.getGroundTruthAnswer());
                if (item.getRelevantChunkIds() != null && !item.getRelevantChunkIds().isEmpty()) {
                    ps.setArray(4, conn.createArrayOf("TEXT", item.getRelevantChunkIds().toArray()));
                } else {
                    ps.setNull(4, java.sql.Types.ARRAY);
                }
                ps.setString(5, item.getRelevantContent());
                if (item.getTags() != null && !item.getTags().isEmpty()) {
                    ps.setArray(6, conn.createArrayOf("VARCHAR", item.getTags().toArray()));
                } else {
                    ps.setNull(6, java.sql.Types.ARRAY);
                }
                ps.setString(7, item.getStatus());
                ps.setInt(8, item.getSeq());

                try (var rs = ps.executeQuery()) {
                    if (rs.next()) {
                        item.setId(rs.getLong("id"));
                    }
                }
            }
            return null;
        });
        return item;
    }

    /**
     * 批量插入数据项（同一 Connection，避免连接泄漏）
     */
    public List<EvaluationDatasetItem> insertItems(List<EvaluationDatasetItem> items) {
        jdbc.execute((Connection conn) -> {
            try (PreparedStatement ps = conn.prepareStatement("""
                    INSERT INTO evaluation_dataset_item
                        (dataset_id, question, ground_truth_answer, relevant_chunk_ids, relevant_content, tags, status, seq)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    RETURNING id
                    """)) {
                for (EvaluationDatasetItem item : items) {
                    ps.setLong(1, item.getDatasetId());
                    ps.setString(2, item.getQuestion());
                    ps.setString(3, item.getGroundTruthAnswer());
                    if (item.getRelevantChunkIds() != null && !item.getRelevantChunkIds().isEmpty()) {
                        ps.setArray(4, conn.createArrayOf("TEXT", item.getRelevantChunkIds().toArray()));
                    } else {
                        ps.setNull(4, java.sql.Types.ARRAY);
                    }
                    ps.setString(5, item.getRelevantContent());
                    if (item.getTags() != null && !item.getTags().isEmpty()) {
                        ps.setArray(6, conn.createArrayOf("VARCHAR", item.getTags().toArray()));
                    } else {
                        ps.setNull(6, java.sql.Types.ARRAY);
                    }
                    ps.setString(7, item.getStatus());
                    ps.setInt(8, item.getSeq());
                    try (var rs = ps.executeQuery()) {
                        if (rs.next()) {
                            item.setId(rs.getLong("id"));
                        }
                    }
                }
            }
            return null;
        });
        return items;
    }

    public List<EvaluationDatasetItem> listItemsByDatasetId(long datasetId) {
        return jdbc.query(
                "SELECT * FROM evaluation_dataset_item WHERE dataset_id = ? ORDER BY seq",
                itemRowMapper, datasetId);
    }

    public Optional<EvaluationDatasetItem> findItemById(long id) {
        try {
            return Optional.of(jdbc.queryForObject(
                    "SELECT * FROM evaluation_dataset_item WHERE id = ?", itemRowMapper, id));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public void updateItem(EvaluationDatasetItem item) {
        jdbc.execute((Connection conn) -> {
            try (PreparedStatement ps = conn.prepareStatement("""
                    UPDATE evaluation_dataset_item SET
                        question = ?,
                        ground_truth_answer = ?,
                        relevant_chunk_ids = ?,
                        relevant_content = ?,
                        tags = ?,
                        status = ?
                    WHERE id = ?
                    """)) {
                ps.setString(1, item.getQuestion());
                ps.setString(2, item.getGroundTruthAnswer());
                if (item.getRelevantChunkIds() != null && !item.getRelevantChunkIds().isEmpty()) {
                    ps.setArray(3, conn.createArrayOf("TEXT", item.getRelevantChunkIds().toArray()));
                } else {
                    ps.setNull(3, java.sql.Types.ARRAY);
                }
                ps.setString(4, item.getRelevantContent());
                if (item.getTags() != null && !item.getTags().isEmpty()) {
                    ps.setArray(5, conn.createArrayOf("VARCHAR", item.getTags().toArray()));
                } else {
                    ps.setNull(5, java.sql.Types.ARRAY);
                }
                ps.setString(6, item.getStatus());
                ps.setLong(7, item.getId());
                ps.executeUpdate();
            }
            return null;
        });
    }

    public int countItemsByDatasetId(long datasetId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM evaluation_dataset_item WHERE dataset_id = ?",
                Integer.class, datasetId);
        return count != null ? count : 0;
    }
}
