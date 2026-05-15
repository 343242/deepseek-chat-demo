package com.demo.chat.rag.evaluation.dataset;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 数据集持久化（JdbcTemplate）
 * <p>
 * 直接使用 SQL 操作，评估模块有意使用 JdbcTemplate + record，不走 MyBatis-Plus。
 * TEXT[] 类型通过 PostgreSQL JDBC 驱动的 Array 支持处理。
 * </p>
 */
@Repository
public class DatasetRepository {

    private static final Logger log = LoggerFactory.getLogger(DatasetRepository.class);

    private final JdbcTemplate jdbc;

    private final RowMapper<EvaluationDataset> datasetRowMapper = (rs, rowNum) -> new EvaluationDataset(
            rs.getLong("id"),
            rs.getString("name"),
            rs.getString("description"),
            rs.getInt("version"),
            rs.getString("source"),
            rs.getString("judge_model"),
            rs.getInt("item_count"),
            rs.getObject("created_at", OffsetDateTime.class),
            rs.getObject("updated_at", OffsetDateTime.class),
            null // items 瞬态
    );

    private final RowMapper<EvaluationDatasetItem> itemRowMapper = (rs, rowNum) -> {
        Set<String> chunkIds = null;
        var chunkIdsArray = rs.getArray("relevant_chunk_ids");
        if (chunkIdsArray != null) {
            String[] ids = (String[]) chunkIdsArray.getArray();
            chunkIds = new HashSet<>(List.of(ids));
        }

        List<String> tags = null;
        var tagsArray = rs.getArray("tags");
        if (tagsArray != null) {
            String[] tagArr = (String[]) tagsArray.getArray();
            tags = List.of(tagArr);
        }

        return new EvaluationDatasetItem(
                rs.getLong("id"),
                rs.getLong("dataset_id"),
                rs.getString("question"),
                rs.getString("ground_truth_answer"),
                chunkIds,
                rs.getString("relevant_content"),
                tags,
                EvaluationItemStatus.fromValue(rs.getString("status")),
                rs.getInt("seq")
        );
    };

    public DatasetRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ======================== Dataset CRUD ========================

    public EvaluationDataset insertDataset(EvaluationDataset dataset) {
        String sql = """
                INSERT INTO evaluation_dataset (name, description, version, source, judge_model, item_count)
                VALUES (?, ?, ?, ?, ?, ?)
                RETURNING id, created_at, updated_at
                """;
        return jdbc.queryForObject(sql,
                datasetRowMapper,
                dataset.name(),
                dataset.description(),
                dataset.version(),
                dataset.source(),
                dataset.judgeModel(),
                dataset.itemCount());
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

    public EvaluationDatasetItem insertItem(EvaluationDatasetItem item) {
        return jdbc.execute((Connection conn) -> {
            try (PreparedStatement ps = conn.prepareStatement("""
                    INSERT INTO evaluation_dataset_item
                        (dataset_id, question, ground_truth_answer, relevant_chunk_ids, relevant_content, tags, status, seq)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    RETURNING id, dataset_id, question, ground_truth_answer, relevant_chunk_ids, relevant_content, tags, status, seq
                    """)) {
                ps.setLong(1, item.datasetId());
                ps.setString(2, item.question());
                ps.setString(3, item.groundTruthAnswer());
                if (item.relevantChunkIds() != null && !item.relevantChunkIds().isEmpty()) {
                    ps.setArray(4, conn.createArrayOf("TEXT", item.relevantChunkIds().toArray()));
                } else {
                    ps.setNull(4, java.sql.Types.ARRAY);
                }
                ps.setString(5, item.relevantContent());
                if (item.tags() != null && !item.tags().isEmpty()) {
                    ps.setArray(6, conn.createArrayOf("VARCHAR", item.tags().toArray()));
                } else {
                    ps.setNull(6, java.sql.Types.ARRAY);
                }
                ps.setString(7, item.status().getValue());
                ps.setInt(8, item.seq());

                try (var rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return itemRowMapper.mapRow(rs, 0);
                    }
                }
            }
            return item;
        });
    }

    public List<EvaluationDatasetItem> insertItems(List<EvaluationDatasetItem> items) {
        return jdbc.execute((Connection conn) -> {
            try (PreparedStatement ps = conn.prepareStatement("""
                    INSERT INTO evaluation_dataset_item
                        (dataset_id, question, ground_truth_answer, relevant_chunk_ids, relevant_content, tags, status, seq)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    RETURNING id, dataset_id, question, ground_truth_answer, relevant_chunk_ids, relevant_content, tags, status, seq
                    """)) {
                for (EvaluationDatasetItem item : items) {
                    ps.setLong(1, item.datasetId());
                    ps.setString(2, item.question());
                    ps.setString(3, item.groundTruthAnswer());
                    if (item.relevantChunkIds() != null && !item.relevantChunkIds().isEmpty()) {
                        ps.setArray(4, conn.createArrayOf("TEXT", item.relevantChunkIds().toArray()));
                    } else {
                        ps.setNull(4, java.sql.Types.ARRAY);
                    }
                    ps.setString(5, item.relevantContent());
                    if (item.tags() != null && !item.tags().isEmpty()) {
                        ps.setArray(6, conn.createArrayOf("VARCHAR", item.tags().toArray()));
                    } else {
                        ps.setNull(6, java.sql.Types.ARRAY);
                    }
                    ps.setString(7, item.status().getValue());
                    ps.setInt(8, item.seq());

                    try (var rs = ps.executeQuery()) {
                        if (rs.next()) {
                            var inserted = itemRowMapper.mapRow(rs, 0);
                            // update in-place in list for caller
                            items.set(items.indexOf(item), inserted);
                        }
                    }
                }
            }
            return items;
        });
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
                ps.setString(1, item.question());
                ps.setString(2, item.groundTruthAnswer());
                if (item.relevantChunkIds() != null && !item.relevantChunkIds().isEmpty()) {
                    ps.setArray(3, conn.createArrayOf("TEXT", item.relevantChunkIds().toArray()));
                } else {
                    ps.setNull(3, java.sql.Types.ARRAY);
                }
                ps.setString(4, item.relevantContent());
                if (item.tags() != null && !item.tags().isEmpty()) {
                    ps.setArray(5, conn.createArrayOf("VARCHAR", item.tags().toArray()));
                } else {
                    ps.setNull(5, java.sql.Types.ARRAY);
                }
                ps.setString(6, item.status().getValue());
                ps.setLong(7, item.id());
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
