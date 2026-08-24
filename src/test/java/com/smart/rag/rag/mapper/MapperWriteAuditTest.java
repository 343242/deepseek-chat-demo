package com.smart.rag.rag.mapper;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Mapper 多行写静态审计（V30 §3.2.1 防线：验证 #20）。
 * <p>
 * 扫描全部 mapper XML，凡对三张实体索引表（rag_entity / rag_chunk_entity / rag_entity_cooccurrence）
 * 的<b>写语句</b>（insert/update/delete）必须出现在 ScopeLockTemplate 调用点白名单中
 * （= 写路径/删除路径/derive 写回/embedding 写回/对账锁内重写的持锁事务闭包），
 * 否则测试失败——把"新语句必须守规"从 code review 人肉保证变为 CI 机械检查。
 * <p>
 * 单行写豁免（WHERE 主键等值，至多一行锁/语句，不成环）：显式列入 SINGLE_ROW_EXEMPT。
 * 防呆自证：从白名单移除任一语句 id → 审计必须报 violation（#20）。
 */
@DisplayName("MapperWriteAudit — 三表多行写必须在持锁事务白名单内（验证 #20）")
class MapperWriteAuditTest {

    private static final Set<String> AUDITED_TABLES = Set.of(
            "rag_entity", "rag_chunk_entity", "rag_entity_cooccurrence");

    /**
     * ScopeLockTemplate 调用点白名单（§3.2.1 收编闭包）：
     * 写路径事务 / 删除路径事务 / derive 写回事务 / embedding advisory 短事务 / 对账锁内重写。
     * 新增语句必须先落入某个持锁事务闭包，再登记于此。
     */
    private static final Set<String> LOCK_GUARDED_WHITELIST = Set.of(
            // EntityMapper —— rag_entity
            "EntityMapper.upsertByNormUserTeam",       // 写路径事务（实体 UPSERT）
            "EntityMapper.recalculateDegree",          // 写/删路径事务
            "EntityMapper.deleteOrphans",              // 删除路径事务
            "EntityMapper.markCommunityStale",         // 写/删路径事务（V30 并入写事务）
            "EntityMapper.updateEmbeddingBatch",       // embedding advisory 短事务
            "EntityMapper.batchUpdateCommunities",     // derive 写回事务
            "EntityMapper.updateWeakTieBatch",         // derive 写回事务
            "EntityMapper.updateBridgeBatch",          // derive 写回事务
            "EntityMapper.clearStaleFlag",             // derive 写回事务
            // ChunkEntityMapper —— rag_chunk_entity
            "ChunkEntityMapper.insertBatchReturning",  // 写路径事务（RETURNING 驱动；MyBatis 以 select 承载）
            "ChunkEntityMapper.deleteByDocumentId",    // 删除路径事务（document_id 直查）
            "ChunkEntityMapper.deleteOrphanLinksByScope", // 对账锁内重写（孤儿清扫）
            // EntityCooccurrenceMapper —— rag_entity_cooccurrence
            "EntityCooccurrenceMapper.upsertIncrement",       // 写路径事务（边递增）
            "EntityCooccurrenceMapper.decrementByPairs",      // 删除路径事务（对称递减）
            "EntityCooccurrenceMapper.deleteZeroEdges",       // 删除路径事务（清零边删除）
            "EntityCooccurrenceMapper.deleteByScope",         // 对账锁内重写
            "EntityCooccurrenceMapper.projectCooccurrence"    // 对账锁内重写
    );

    /** 单行写豁免（至多一行锁/语句——§3.2.1 白名单安全标注）。 */
    private static final Set<String> SINGLE_ROW_EXEMPT = Set.of(
            "EntityMapper.updateEmbedding"   // WHERE id = #{id}
    );

    private static final Pattern INSERT_TARGET =
            Pattern.compile("INSERT\\s+INTO\\s+([a-z_]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern UPDATE_TARGET =
            Pattern.compile("UPDATE\\s+([a-z_]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern DELETE_TARGET =
            Pattern.compile("DELETE\\s+FROM\\s+([a-z_]+)", Pattern.CASE_INSENSITIVE);

    /** 收集 namespace.statementId → 目标表（仅三表写语句）。 */
    private static Map<String, String> scanAuditedWriteStatements() throws Exception {
        Path mapperDir = Path.of("src", "main", "resources", "mapper");
        try (Stream<Path> xmls = Files.list(mapperDir)) {
            Map<String, String> found = new HashMap<>();
            for (Path xml : xmls.filter(p -> p.toString().endsWith(".xml")).toList()) {
                DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
                // 不加载外部 DTD（mybatis-3-mapper.dtd 无网络依赖即可解析）
                factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
                Document doc = factory.newDocumentBuilder().parse(xml.toFile());
                String namespace = doc.getDocumentElement().getAttribute("namespace")
                        .substring(doc.getDocumentElement().getAttribute("namespace").lastIndexOf('.') + 1);
                // select 也在扫描之列：MyBatis 以 <select> 承载 INSERT...RETURNING（insertBatchReturning）
                for (String tag : new String[]{"insert", "update", "delete", "select"}) {
                    NodeList nodes = doc.getElementsByTagName(tag);
                    for (int i = 0; i < nodes.getLength(); i++) {
                        Element el = (Element) nodes.item(i);
                        // textContent 不含注释节点——XML 注释中的表名不误报
                        String sql = el.getTextContent();
                        String table = firstAuditedTarget(sql);
                        if (table != null) {
                            found.put(namespace + "." + el.getAttribute("id"), table);
                        }
                    }
                }
            }
            return found;
        }
    }

    private static String firstAuditedTarget(String sql) {
        for (Pattern p : new Pattern[]{INSERT_TARGET, UPDATE_TARGET, DELETE_TARGET}) {
            Matcher m = p.matcher(sql);
            if (m.find() && AUDITED_TABLES.contains(m.group(1).toLowerCase())) {
                return m.group(1).toLowerCase();
            }
        }
        return null;
    }

    /** 返回不在白名单/豁免内的三表写语句 id 集合。 */
    private static Set<String> violations(Map<String, String> found, Set<String> whitelist) {
        Set<String> bad = new LinkedHashSet<>();
        for (String statementId : found.keySet()) {
            if (!whitelist.contains(statementId) && !SINGLE_ROW_EXEMPT.contains(statementId)) {
                bad.add(statementId);
            }
        }
        return bad;
    }

    @Test
    @DisplayName("全部三表写语句均在 ScopeLockTemplate 白名单或单行豁免内")
    void allAuditedWritesAreWhitelisted() throws Exception {
        Map<String, String> found = scanAuditedWriteStatements();

        assertThat(found).isNotEmpty();   // 扫描本身生效（防 mapper 目录漂移致空转假绿）

        Set<String> violations = violations(found, LOCK_GUARDED_WHITELIST);
        assertThat(violations)
                .as("以下三表写语句未登记在 ScopeLockTemplate 白名单（V30 §3.2.1：须在持锁事务内执行，"
                        + "或确为单行写时列入 SINGLE_ROW_EXEMPT）：%n%s", violations)
                .isEmpty();
    }

    @Test
    @DisplayName("白名单无陈旧条目（每条都对应现存语句——防语句退役后白名单腐化）")
    void whitelistHasNoStaleEntries() throws Exception {
        Map<String, String> found = scanAuditedWriteStatements();

        Set<String> stale = new LinkedHashSet<>(LOCK_GUARDED_WHITELIST);
        stale.removeAll(found.keySet());
        assertThat(stale)
                .as("白名单中的语句已不存在于 mapper XML（移除语句时未同步白名单）：%n%s", stale)
                .isEmpty();
    }

    @Test
    @DisplayName("防呆自证（#20）：从白名单移除一条 → 审计报 violation")
    void auditSelfProof_detectsUnwhitelistedStatement() throws Exception {
        Map<String, String> found = scanAuditedWriteStatements();

        Set<String> reduced = new LinkedHashSet<>(LOCK_GUARDED_WHITELIST);
        String removed = reduced.iterator().next();
        reduced.remove(removed);

        assertThat(violations(found, reduced))
                .as("移除白名单条目 %s 后审计必须报 violation", removed)
                .containsExactly(removed);
    }

    @Test
    @DisplayName("白名单确实收编了关键增量语句（抽查：upsertIncrement / decrementByPairs / projectCooccurrence）")
    void keyIncrementalStatementsAreWhitelisted() throws Exception {
        Map<String, String> found = scanAuditedWriteStatements();

        assertThat(found).containsKeys(
                "EntityCooccurrenceMapper.upsertIncrement",
                "EntityCooccurrenceMapper.decrementByPairs",
                "EntityCooccurrenceMapper.projectCooccurrence",
                "ChunkEntityMapper.insertBatchReturning",
                "ChunkEntityMapper.deleteByDocumentId");
    }
}
