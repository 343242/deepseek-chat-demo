package com.smart.rag.rag.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.smart.rag.rag.config.RagEntityProperties;
import com.smart.rag.rag.entity.RagChunkEntity;
import com.smart.rag.rag.entity.RagEntity;
import com.smart.rag.rag.mapper.ChunkEntityMapper;
import com.smart.rag.rag.mapper.ChunkEntityMapper.NewLink;
import com.smart.rag.rag.mapper.EntityCooccurrenceMapper;
import com.smart.rag.rag.mapper.EntityCooccurrenceMapper.PairCount;
import com.smart.rag.rag.mapper.EntityMapper;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 实体规范化服务（Level 1: NFC + lowercase + trim）——V30 写路径增量递增。
 * <p>
 * 职责（V30 §4.1，事务内顺序）：
 * <ul>
 *   <li>scope advisory 锁（经 {@link ScopeLockTemplate}，写闸门 + 重试在外层）</li>
 *   <li>name → name_norm 归一化，按 name_norm 分组拼接 description，批量 UPSERT rag_entity</li>
 *   <li>链接插入 ON CONFLICT DO NOTHING + RETURNING（幂等 + 落库真值，§4.2）</li>
 *   <li>由 RETURNING 行计算 pair 增量（精确版，§4.3）→ 边递增 upsert（§4.4）</li>
 *   <li>重算受影响 entity 的 degree；graphChanged 时标记 community_stale（并入写事务，§4.1 步骤 9）</li>
 * </ul>
 */
@Service
public class EntityCanonicalizationService {

    private static final Logger log = LoggerFactory.getLogger(EntityCanonicalizationService.class);

    /** 分批插入大小（MyBatis foreach 限制） */
    private static final int INSERT_BATCH_SIZE = 500;

    /** description 拼接硬上限倍数：cap = 倍数 × descriptionMaxLength */
    private static final int DESCRIPTION_CAP_FACTOR = 4;

    private final EntityMapper entityMapper;
    private final ChunkEntityMapper chunkEntityMapper;
    private final EntityCooccurrenceMapper cooccurrenceMapper;
    private final TransactionTemplate transactionTemplate;
    private final ScopeLockTemplate scopeLockTemplate;
    private final LockRetryExecutor lockRetryExecutor;
    private final ScopeWriteGate scopeWriteGate;
    private final RagEntityProperties properties;

    public EntityCanonicalizationService(EntityMapper entityMapper,
                                         ChunkEntityMapper chunkEntityMapper,
                                         EntityCooccurrenceMapper cooccurrenceMapper,
                                         TransactionTemplate transactionTemplate,
                                         ScopeLockTemplate scopeLockTemplate,
                                         LockRetryExecutor lockRetryExecutor,
                                         ScopeWriteGate scopeWriteGate,
                                         RagEntityProperties properties) {
        this.entityMapper = entityMapper;
        this.chunkEntityMapper = chunkEntityMapper;
        this.cooccurrenceMapper = cooccurrenceMapper;
        this.transactionTemplate = transactionTemplate;
        this.scopeLockTemplate = scopeLockTemplate;
        this.lockRetryExecutor = lockRetryExecutor;
        this.scopeWriteGate = scopeWriteGate;
        this.properties = properties;
    }

    /**
     * Level 1 规范化：NFC → lowercase → trim
     */
    public String canonicalize(String name) {
        if (name == null || name.isBlank()) {
            return "";
        }
        return Normalizer.normalize(name.trim(), Normalizer.Form.NFC).toLowerCase();
    }

    /**
     * 抽取结果的内部分组结构
     */
    public record ParsedExtraction(
            String chunkId,
            String eventSummary,
            List<ParsedEntity> entities
    ) {}

    public record ParsedEntity(
            String name,
            String description,
            String type
    ) {}

    /**
     * 写路径结果：entityIds 交 embedding（契约同旧返回值）；
     * graphChanged = 本次是否落库了新链接（false = 纯重投递，调用方据此跳过结构分重算，§6.1）。
     */
    public record AggregateResult(List<Long> entityIds, boolean graphChanged) {}

    /**
     * 聚合并写入实体和关联（V30 §4.1：RETURNING 驱动的增量递增）。
     * <p>
     * 重投递幂等：全部链接撞 (chunk_id, entity_id) 主键被吞 → RETURNING 为空 → 增量 0、graphChanged=false。
     *
     * @param extractions 文档级所有 chunk 的抽取结果
     * @param userId      用户 ID
     * @param teamId      团队 ID（null = 个人文档）
     * @param documentId  文档 ID（链接的权威文档归属，V30 新列）
     */
    public AggregateResult aggregateAndUpsert(List<ParsedExtraction> extractions,
                                              Long userId,
                                              @Nullable Long teamId,
                                              Long documentId) {
        // 1. 按 name_norm 分组，拼接 description（锁外纯内存）
        int descCap = properties.descriptionMaxLength() * DESCRIPTION_CAP_FACTOR;
        Map<String, AggregatedEntity> aggregated = new LinkedHashMap<>();
        for (ParsedExtraction ext : extractions) {
            for (ParsedEntity pe : ext.entities()) {
                String nameNorm = canonicalize(pe.name());
                if (nameNorm.isEmpty()) {
                    continue;
                }
                aggregated.compute(nameNorm, (key, existing) -> {
                    if (existing == null) {
                        return new AggregatedEntity(pe.name(), pe.description(), descCap);
                    }
                    existing.appendDescription(pe.description());
                    return existing;
                });
            }
        }
        // 早退路径（§4.1 第九轮：判定先于写闸门——空批次不拿闸门、不取锁、不开事务）
        if (aggregated.isEmpty()) {
            return new AggregateResult(List.of(), false);
        }

        List<RagEntity> entitiesToUpsert = buildEntitiesToUpsert(aggregated, userId, teamId);

        // 写闸门（§3.6：排队发生在应用内信号量，零 DB 连接占用）→ 保险重试（§8-4）→ 事务 → scope 锁
        AggregateResult[] result = new AggregateResult[1];
        scopeWriteGate.tryAcquire(userId, teamId, properties.writeGateWaitMillis());
        try {
            lockRetryExecutor.execute(() ->
                    transactionTemplate.execute(status -> {
                        scopeLockTemplate.withinScopeLock(userId, teamId, () ->
                                result[0] = writeWithinLock(extractions, aggregated, entitiesToUpsert,
                                        userId, teamId, documentId));
                        return null;
                    }));
        } finally {
            scopeWriteGate.release(userId, teamId);
        }

        log.info("Canonicalized {} entities for {} chunks (documentId={}, graphChanged={})",
                aggregated.size(), extractions.size(), documentId, result[0].graphChanged());

        return result[0];
    }

    /** §4.1 步骤 2-9：锁内临界区（行锁获取者语句全部在 lockScope 之后，R1）。 */
    private AggregateResult writeWithinLock(List<ParsedExtraction> extractions,
                                            Map<String, AggregatedEntity> aggregated,
                                            List<RagEntity> entitiesToUpsert,
                                            Long userId,
                                            @Nullable Long teamId,
                                            Long documentId) {
        // 2. 实体 UPSERT（保留 DO UPDATE：description 跨文档合并在 SQL 完成；分批控语句体积）
        for (int i = 0; i < entitiesToUpsert.size(); i += INSERT_BATCH_SIZE) {
            entityMapper.upsertByNormUserTeam(
                    entitiesToUpsert.subList(i, Math.min(i + INSERT_BATCH_SIZE, entitiesToUpsert.size())));
        }

        // 3. 锁内读实体 id → nameNormToId
        List<RagEntity> upserted = findEntitiesByNameNorms(aggregated.keySet(), userId, teamId);
        Map<String, Long> nameNormToId = new LinkedHashMap<>(upserted.size());
        for (RagEntity e : upserted) {
            nameNormToId.put(e.getNameNorm(), e.getId());
        }

        // 候选链接（per chunk 去重：单 chunk 内同名实体重复出现只计一次链接与一次 pair，验证 #4）
        Map<String, LinkedHashSet<Long>> candidatesByChunk = buildCandidates(extractions, nameNormToId);

        // 4. 锁内读受影响 chunk 的既有链接（§4.3 精确 pair 计算需要；快照必须在锁后，§3.3）
        Map<String, Set<Long>> existingByChunk = selectExistingLinks(candidatesByChunk.keySet());

        // 5. 链接插入：ON CONFLICT DO NOTHING + RETURNING（幂等 + 落库真值，§4.2；分批）
        List<NewLink> newLinks = insertBatchReturning(candidatesByChunk, documentId);

        // 6. 由 RETURNING 行计算 pair 增量（§4.3 精确版：trueSet = 既有 ∪ 新增）
        List<PairCount> deltas = computePairDeltas(existingByChunk, newLinks);

        // 7. 边递增（排序分批，§3.4；冲突目标含 LEAST/GREATEST，§4.4）
        if (!deltas.isEmpty()) {
            for (int i = 0; i < deltas.size(); i += INSERT_BATCH_SIZE) {
                cooccurrenceMapper.upsertIncrement(
                        deltas.subList(i, Math.min(i + INSERT_BATCH_SIZE, deltas.size())), userId, teamId);
            }
        }

        // 8. degree 重算（返回 entityIds 交 embedding；graphChanged = !newLinks.isEmpty()）
        List<Long> ids = upserted.stream().map(RagEntity::getId).toList();
        if (!ids.isEmpty()) {
            entityMapper.recalculateDegree(ids);
        }

        // 9. markCommunityStale（§4.1 步骤 9：并入写事务；graphChanged=false 时跳过——图谱未变无需标记）
        boolean graphChanged = !newLinks.isEmpty();
        if (graphChanged && !ids.isEmpty()) {
            entityMapper.markCommunityStale(ids);
        }

        return new AggregateResult(ids, graphChanged);
    }

    /** 候选链接：chunkId → entityId 集合（去重 + 保持插入顺序稳定）。 */
    private Map<String, LinkedHashSet<Long>> buildCandidates(List<ParsedExtraction> extractions,
                                                             Map<String, Long> nameNormToId) {
        Map<String, LinkedHashSet<Long>> candidatesByChunk = new LinkedHashMap<>();
        for (ParsedExtraction ext : extractions) {
            for (ParsedEntity pe : ext.entities()) {
                Long entityId = nameNormToId.get(canonicalize(pe.name()));
                if (entityId != null) {
                    candidatesByChunk.computeIfAbsent(ext.chunkId(), k -> new LinkedHashSet<>()).add(entityId);
                }
            }
        }
        return candidatesByChunk;
    }

    /** 步骤 4：锁内读既有链接（chunkIds 按 500 分批，§11 第三轮低项修正）。 */
    private Map<String, Set<Long>> selectExistingLinks(Collection<String> chunkIds) {
        Map<String, Set<Long>> existingByChunk = new LinkedHashMap<>();
        List<String> ids = List.copyOf(chunkIds);
        for (int i = 0; i < ids.size(); i += INSERT_BATCH_SIZE) {
            for (var link : chunkEntityMapper.selectByChunkIds(
                    ids.subList(i, Math.min(i + INSERT_BATCH_SIZE, ids.size())))) {
                existingByChunk.computeIfAbsent(link.chunkId(), k -> new LinkedHashSet<>()).add(link.entityId());
            }
        }
        return existingByChunk;
    }

    /** 步骤 5：分批插入并累计 RETURNING 行。 */
    private List<NewLink> insertBatchReturning(Map<String, LinkedHashSet<Long>> candidatesByChunk,
                                               Long documentId) {
        List<RagChunkEntity> candidates = new ArrayList<>();
        candidatesByChunk.forEach((chunkId, entityIds) ->
                entityIds.forEach(entityId -> {
                    RagChunkEntity ce = new RagChunkEntity();
                    ce.setChunkId(chunkId);
                    ce.setEntityId(entityId);
                    ce.setDocumentId(documentId);
                    candidates.add(ce);
                }));
        List<NewLink> newLinks = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i += INSERT_BATCH_SIZE) {
            newLinks.addAll(chunkEntityMapper.insertBatchReturning(
                    candidates.subList(i, Math.min(i + INSERT_BATCH_SIZE, candidates.size()))));
        }
        return newLinks;
    }

    /**
     * §4.3 精确 pair 计算（覆盖重投递抽取结果部分新增实体的情况）：
     * 对每个出现于 newLinks 的 chunk c：trueSet(c) = existing(c) ∪ new(c)；
     * 对 trueSet(c) 的每一对 (a, b)，若 a ∈ new(c) 或 b ∈ new(c)：delta[min(a,b), max(a,b)] += 1。
     * 只存在于 existingLinks 的 chunk 无变化，贡献 0。
     */
    static List<PairCount> computePairDeltas(Map<String, Set<Long>> existingByChunk, List<NewLink> newLinks) {
        Map<String, Set<Long>> newByChunk = new LinkedHashMap<>();
        for (NewLink link : newLinks) {
            newByChunk.computeIfAbsent(link.chunkId(), k -> new LinkedHashSet<>()).add(link.entityId());
        }
        // key：a<b 的规范化 pair（TreeMap 按 (a, b) 字典序 = 写回排序，§3.4）
        TreeMap<long[], Integer> deltas = new TreeMap<>((x, y) ->
                x[0] != y[0] ? Long.compare(x[0], y[0]) : Long.compare(x[1], y[1]));
        for (Map.Entry<String, Set<Long>> entry : newByChunk.entrySet()) {
            Set<Long> newSet = entry.getValue();
            Set<Long> trueSet = new LinkedHashSet<>(newSet);
            Set<Long> existing = existingByChunk.get(entry.getKey());
            if (existing != null) {
                trueSet.addAll(existing);
            }
            long[] arr = trueSet.stream().mapToLong(Long::longValue).toArray();
            for (int i = 0; i < arr.length; i++) {
                for (int j = i + 1; j < arr.length; j++) {
                    long a = arr[i];
                    long b = arr[j];
                    if (!newSet.contains(a) && !newSet.contains(b)) {
                        continue;   // (既有, 既有) 对：无变化
                    }
                    long min = Math.min(a, b);
                    long max = Math.max(a, b);
                    deltas.merge(new long[]{min, max}, 1, Integer::sum);
                }
            }
        }
        return deltas.entrySet().stream()
                .map(e -> new PairCount(e.getKey()[0], e.getKey()[1], e.getValue()))
                .toList();
    }

    /** Step 2: 聚合结果 → RagEntity 列表 */
    private List<RagEntity> buildEntitiesToUpsert(Map<String, AggregatedEntity> aggregated,
                                                  Long userId, @Nullable Long teamId) {
        return aggregated.values().stream()
                .map(ae -> {
                    RagEntity e = new RagEntity();
                    e.setNameNorm(ae.nameNorm());
                    e.setNameDisplay(ae.nameDisplay());
                    e.setDescription(ae.description());
                    e.setUserId(userId);
                    e.setTeamId(teamId);
                    return e;
                })
                .toList();
    }

    /**
     * 按 name_norm 列表查询已有实体
     */
    private List<RagEntity> findEntitiesByNameNorms(Collection<String> nameNorms,
                                                     Long userId,
                                                     @Nullable Long teamId) {
        return entityMapper.selectList(
                new LambdaQueryWrapper<RagEntity>()
                        .in(RagEntity::getNameNorm, nameNorms)
                        .eq(RagEntity::getUserId, userId)
                        .eq(teamId != null, RagEntity::getTeamId, teamId)
                        .isNull(teamId == null, RagEntity::getTeamId)
        );
    }

    /**
     * 聚合中的中间结构
     */
    private static class AggregatedEntity {
        /**
         * description 拼接硬上限（= 4 × descriptionMaxLength，由调用方传入）。
         * 同一实体跨 chunk 描述高度冗余且下游必压缩到 ≤ maxLen；无限拼接只会徒增压缩调用量。
         */
        private final int descriptionHardCap;
        private final String nameNorm;
        private final String nameDisplay;
        private final StringBuilder descriptionBuilder;

        AggregatedEntity(String nameDisplay, String firstDescription, int descriptionHardCap) {
            this.descriptionHardCap = Math.max(1, descriptionHardCap);
            this.nameNorm = Normalizer.normalize(
                    nameDisplay.trim(), Normalizer.Form.NFC).toLowerCase();
            this.nameDisplay = nameDisplay;
            this.descriptionBuilder = new StringBuilder(firstDescription != null ? firstDescription : "");
        }

        String nameNorm() { return nameNorm; }
        String nameDisplay() { return nameDisplay; }
        String description() { return descriptionBuilder.toString(); }

        void appendDescription(String desc) {
            if (desc == null || desc.isEmpty() || descriptionBuilder.length() >= descriptionHardCap) {
                return;
            }
            if (!descriptionBuilder.isEmpty()) {
                descriptionBuilder.append("。");
            }
            descriptionBuilder.append(desc);
        }
    }

    /** 供单测断言排序语义（§3.4：按 (entity_a, entity_b) 排序分批）。 */
    static List<PairCount> sortedByPair(List<PairCount> pairs) {
        return pairs.stream()
                .sorted(Comparator.comparingLong(PairCount::entityA)
                        .thenComparingLong(PairCount::entityB))
                .toList();
    }
}
