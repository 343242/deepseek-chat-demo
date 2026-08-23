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
import java.util.HashMap;
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
        // long 运算防 page*size 溢出为负 OFFSET（page 上限未受控时 int 乘法可能回绕）
        long offset = (long) page * size;
        return jdbc.query(
                "SELECT * FROM evaluation_dataset ORDER BY created_at DESC LIMIT ? OFFSET ?",
                datasetRowMapper, size, offset);
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

    /**
     * 批量插入数据项（单语句 UNNEST 并行数组，一次数据库往返）。
     * <p>
     * 逐条 INSERT 在数据集规模增长后产生大量数据库往返；改为一条
     * {@code INSERT ... SELECT * FROM UNNEST(...)} 把 N 行打包进单语句。
     * 列级空集合/null（relevant_chunk_ids、tags）用外层数组的 NULL 元素表达，
     * 语义与单条插入的 {@code setNull} 一致。RETURNING 行序不保证与输入对齐，
     * 按 seq（数据集内唯一，postProcess 按序分配）回填生成的 id。
     * </p>
     */
    public List<EvaluationDatasetItem> insertItems(List<EvaluationDatasetItem> items) {
        if (items == null || items.isEmpty()) {
            return items == null ? List.of() : items;
        }
        return jdbc.execute((Connection conn) -> {
            try (PreparedStatement ps = conn.prepareStatement("""
                    INSERT INTO evaluation_dataset_item
                        (dataset_id, question, ground_truth_answer, relevant_chunk_ids, relevant_content, tags, status, seq)
                    SELECT * FROM UNNEST(?::bigint[], ?::text[], ?::text[], ?::text[][], ?::text[], ?::varchar[][], ?::text[], ?::int[])
                    RETURNING id, dataset_id, question, ground_truth_answer, relevant_chunk_ids, relevant_content, tags, status, seq
                    """)) {
                ps.setArray(1, conn.createArrayOf("bigint",
                        items.stream().map(EvaluationDatasetItem::datasetId).toArray(Long[]::new)));
                ps.setArray(2, textColumn(conn, items, EvaluationDatasetItem::question));
                ps.setArray(3, textColumn(conn, items, EvaluationDatasetItem::groundTruthAnswer));
                ps.setArray(4, arrayColumn(conn, items, EvaluationDatasetItem::relevantChunkIds, "TEXT"));
                ps.setArray(5, textColumn(conn, items, EvaluationDatasetItem::relevantContent));
                ps.setArray(6, arrayColumn(conn, items, EvaluationDatasetItem::tags, "VARCHAR"));
                ps.setArray(7, textColumn(conn, items, i -> i.status().getValue()));
                ps.setArray(8, conn.createArrayOf("int4",
                        items.stream().map(EvaluationDatasetItem::seq).toArray(Integer[]::new)));

                var bySeq = new HashMap<Integer, EvaluationDatasetItem>();
                try (var rs = ps.executeQuery()) {
                    while (rs.next()) {
                        var row = itemRowMapper.mapRow(rs, 0);
                        bySeq.put(row.seq(), row);
                    }
                }
                for (int i = 0; i < items.size(); i++) {
                    var saved = bySeq.get(items.get(i).seq());
                    if (saved != null) {
                        items.set(i, saved);
                    }
                }
            }
            return items;
        });
    }

    /** 一维 text 列（question/answer/content/status）打包为 text[]。 */
    private static java.sql.Array textColumn(Connection conn,
                                             List<EvaluationDatasetItem> items,
                                             java.util.function.Function<EvaluationDatasetItem, String> getter)
            throws java.sql.SQLException {
        return conn.createArrayOf("text",
                items.stream().map(getter).toArray(String[]::new));
    }

    /**
     * 数组列（relevant_chunk_ids TEXT[] / tags VARCHAR[]）打包为二维数组：
     * 空集合或 null 的行用 NULL 元素表达（对应列值 NULL，与单条插入的 setNull 语义一致）。
     */
    private static java.sql.Array arrayColumn(Connection conn,
                                              List<EvaluationDatasetItem> items,
                                              java.util.function.Function<EvaluationDatasetItem, ? extends java.util.Collection<String>> getter,
                                              String elementType)
            throws java.sql.SQLException {
        Object[] outer = new Object[items.size()];
        for (int i = 0; i < items.size(); i++) {
            var values = getter.apply(items.get(i));
            outer[i] = (values == null || values.isEmpty())
                    ? null
                    : conn.createArrayOf(elementType, values.toArray());
        }
        return conn.createArrayOf(elementType, outer);
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
