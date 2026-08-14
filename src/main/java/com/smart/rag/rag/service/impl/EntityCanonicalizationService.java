package com.smart.rag.rag.service.impl;

import com.smart.rag.rag.entity.RagChunkEntity;
import com.smart.rag.rag.entity.RagEntity;
import com.smart.rag.rag.mapper.ChunkEntityMapper;
import com.smart.rag.rag.mapper.EntityMapper;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.Map;

/**
 * 实体规范化服务（Level 1: NFC + lowercase + trim）
 * <p>
 * 职责：
 * <ul>
 *   <li>name → name_norm 归一化</li>
 *   <li>按 name_norm 分组，拼接 description</li>
 *   <li>批量 UPSERT rag_entity</li>
 *   <li>批量 INSERT rag_chunk_entity</li>
 *   <li>重算受影响 entity 的 degree</li>
 * </ul>
 */
@Service
@ConditionalOnProperty(prefix = "app.rag.entity", name = "enabled", havingValue = "true")
public class EntityCanonicalizationService {

    private static final Logger log = LoggerFactory.getLogger(EntityCanonicalizationService.class);

    /** 分批插入大小（MyBatis foreach 限制） */
    private static final int INSERT_BATCH_SIZE = 500;

    private final EntityMapper entityMapper;
    private final ChunkEntityMapper chunkEntityMapper;
    private final TransactionTemplate transactionTemplate;

    public EntityCanonicalizationService(EntityMapper entityMapper,
                                         ChunkEntityMapper chunkEntityMapper,
                                         TransactionTemplate transactionTemplate) {
        this.entityMapper = entityMapper;
        this.chunkEntityMapper = chunkEntityMapper;
        this.transactionTemplate = transactionTemplate;
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
     * 聚合并写入实体和关联
     *
     * @param extractions 文档级所有 chunk 的抽取结果
     * @param userId      用户 ID
     * @param teamId      团队 ID（null = 个人文档）
     * @return 受影响的 entity id 列表（用于后续 embedding + community_stale）
     */
    public List<Long> aggregateAndUpsert(List<ParsedExtraction> extractions,
                                         Long userId,
                                         @Nullable Long teamId) {
        // 1. 按 name_norm 分组，拼接 description
        Map<String, AggregatedEntity> aggregated = new LinkedHashMap<>();
        for (ParsedExtraction ext : extractions) {
            for (ParsedEntity pe : ext.entities()) {
                String nameNorm = canonicalize(pe.name());
                if (nameNorm.isEmpty()) {
                    continue;
                }
                aggregated.compute(nameNorm, (key, existing) -> {
                    if (existing == null) {
                        return new AggregatedEntity(pe.name(), pe.description());
                    }
                    existing.appendDescription(pe.description());
                    return existing;
                });
            }
        }
        if (aggregated.isEmpty()) {
            return List.of();
        }

        // 2. 构建 RagEntity 列表用于 UPSERT
        List<RagEntity> entitiesToUpsert = buildEntitiesToUpsert(aggregated, userId, teamId);

        // 3-6. 在事务中执行 UPSERT + chunk_entity 插入 + degree 重算
        //    防止并发 cleanup 看到中间态 degree=0 导致实体被误删
        AtomicReference<List<Long>> entityIdsRef = new AtomicReference<>();
        transactionTemplate.executeWithoutResult(status -> {
            entityMapper.upsertByNormUserTeam(entitiesToUpsert);
            List<RagEntity> upserted = findEntitiesByNameNorms(aggregated.keySet(), userId, teamId);
            insertChunkEntities(extractions, upserted);
            List<Long> ids = recomputeDegrees(upserted);
            entityIdsRef.set(ids);
        });

        log.info("Canonicalized {} entities for {} chunks", aggregated.size(), extractions.size());

        return entityIdsRef.get();
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

    /** Step 5: 组装 chunk-entity 关联并分批 INSERT（MyBatis foreach 限制） */
    private void insertChunkEntities(List<ParsedExtraction> extractions, List<RagEntity> upserted) {
        // name_norm → entity id 的 O(1) 索引（避免对每个 ParsedEntity 线性扫描 upserted）
        Map<String, Long> nameNormToId = new LinkedHashMap<>(upserted.size());
        for (RagEntity e : upserted) {
            nameNormToId.put(e.getNameNorm(), e.getId());
        }

        List<RagChunkEntity> chunkEntities = new ArrayList<>();
        for (ParsedExtraction ext : extractions) {
            for (ParsedEntity pe : ext.entities()) {
                String nameNorm = canonicalize(pe.name());
                if (nameNorm.isEmpty()) {
                    continue;
                }
                Long entityId = nameNormToId.get(nameNorm);
                if (entityId != null) {
                    RagChunkEntity ce = new RagChunkEntity();
                    ce.setChunkId(ext.chunkId());
                    ce.setEntityId(entityId);
                    chunkEntities.add(ce);
                }
            }
        }

        for (int i = 0; i < chunkEntities.size(); i += INSERT_BATCH_SIZE) {
            List<RagChunkEntity> batch = chunkEntities.subList(
                    i, Math.min(i + INSERT_BATCH_SIZE, chunkEntities.size()));
            chunkEntityMapper.insertBatch(batch);
        }
    }

    /** Step 6: 重算受影响实体 degree */
    private List<Long> recomputeDegrees(List<RagEntity> upserted) {
        List<Long> ids = upserted.stream().map(RagEntity::getId).toList();
        if (!ids.isEmpty()) {
            entityMapper.recalculateDegree(ids);
        }
        return ids;
    }

    /**
     * 按 name_norm 列表查询已有实体
     */
    private List<RagEntity> findEntitiesByNameNorms(java.util.Collection<String> nameNorms,
                                                     Long userId,
                                                     @Nullable Long teamId) {
        return entityMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<RagEntity>()
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
        private final String nameNorm;
        private final String nameDisplay;
        private final StringBuilder descriptionBuilder;

        AggregatedEntity(String nameDisplay, String firstDescription) {
            this.nameNorm = Normalizer.normalize(
                    nameDisplay.trim(), Normalizer.Form.NFC).toLowerCase();
            this.nameDisplay = nameDisplay;
            this.descriptionBuilder = new StringBuilder(firstDescription != null ? firstDescription : "");
        }

        String nameNorm() { return nameNorm; }
        String nameDisplay() { return nameDisplay; }
        String description() { return descriptionBuilder.toString(); }

        void appendDescription(String desc) {
            if (desc != null && !desc.isEmpty()) {
                if (!descriptionBuilder.isEmpty()) {
                    descriptionBuilder.append("。");
                }
                descriptionBuilder.append(desc);
            }
        }
    }
}
