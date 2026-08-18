package com.smart.rag.evaluation.dataset;

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
import java.util.Objects;
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
            // PG 数组可能含 null 元素（List.of 会 NPE），过滤掉
            tags = java.util.Arrays.stream((String[]) tagsArray.getArray())
                    .filter(Objects::nonNull)
                    .toList();
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
                RETURNING *
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
                bindInsertParams(ps, conn, item);
                try (var rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return itemRowMapper.mapRow(rs, 0);
                    }
                    log.warn("insertItem RETURNING returned no row for datasetId={}", item.datasetId());
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
                // 按下标回填：record 按内容 equals，indexOf 在重复内容时会命中第一条导致回填丢失
                for (int i = 0; i < items.size(); i++) {
                    bindInsertParams(ps, conn, items.get(i));
                    try (var rs = ps.executeQuery()) {
                        if (rs.next()) {
                            items.set(i, itemRowMapper.mapRow(rs, 0));
                        } else {
                            log.warn("insertItems RETURNING returned no row at index {} (datasetId={})",
                                    i, items.get(i).datasetId());
                        }
                    }
                }
            }
            return items;
        });
    }

    /**
     * 绑定 INSERT 的 8 个参数（relevant_chunk_ids/tags 的数组/null 双分支）。
     * insertItem 与 insertItems 共用，避免同一段绑定逻辑复制两份。
     */
    private void bindInsertParams(PreparedStatement ps, Connection conn,
                                  EvaluationDatasetItem item) throws java.sql.SQLException {
        ps.setLong(1, item.datasetId());
        ps.setString(2, item.question());
        ps.setString(3, item.groundTruthAnswer());
        setTextArrayOrNull(ps, conn, 4, "TEXT", item.relevantChunkIds());
        ps.setString(5, item.relevantContent());
        setTextArrayOrNull(ps, conn, 6, "VARCHAR", item.tags());
        ps.setString(7, item.status().getValue());
        ps.setInt(8, item.seq());
    }

    private static void setTextArrayOrNull(PreparedStatement ps, Connection conn, int index,
                                           String sqlType, java.util.Collection<String> values) throws java.sql.SQLException {
        if (values != null && !values.isEmpty()) {
            ps.setArray(index, conn.createArrayOf(sqlType, values.toArray()));
        } else {
            ps.setNull(index, java.sql.Types.ARRAY);
        }
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
                setTextArrayOrNull(ps, conn, 3, "TEXT", item.relevantChunkIds());
                ps.setString(4, item.relevantContent());
                setTextArrayOrNull(ps, conn, 5, "VARCHAR", item.tags());
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
