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
        List<String> allChunkIds = new ArrayList<>();

        for (ParsedExtraction ext : extractions) {
            if (ext.chunkId() != null) {
                allChunkIds.add(ext.chunkId());
            }
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
        List<RagEntity> entitiesToUpsert = aggregated.values().stream()
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

        // 3-6. 在事务中执行 UPSERT + chunk_entity 插入 + degree 重算
        //    防止并发 cleanup 看到中间态 degree=0 导致实体被误删
        AtomicReference<List<Long>> entityIdsRef = new AtomicReference<>();
        transactionTemplate.executeWithoutResult(status -> {
            // 3. 批量 UPSERT
            entityMapper.upsertByNormUserTeam(entitiesToUpsert);

            // 4. 查询 UPSERT 后的 entity ids（按 name_norm + user_id + team_id 查回）
            List<RagEntity> upserted = findEntitiesByNameNorms(
                    aggregated.keySet(), userId, teamId);

            // 5. 批量 INSERT rag_chunk_entity
            List<RagChunkEntity> chunkEntities = new ArrayList<>();
            for (ParsedExtraction ext : extractions) {
                for (ParsedEntity pe : ext.entities()) {
                    String nameNorm = canonicalize(pe.name());
                    if (nameNorm.isEmpty()) {
                        continue;
                    }
                    // 找到对应的 entity id
                    upserted.stream()
                            .filter(e -> e.getNameNorm().equals(nameNorm))
                            .findFirst()
                            .ifPresent(entity -> {
                                RagChunkEntity ce = new RagChunkEntity();
                                ce.setChunkId(ext.chunkId());
                                ce.setEntityId(entity.getId());
                                chunkEntities.add(ce);
                            });
                }
            }

            if (!chunkEntities.isEmpty()) {
                // 分批插入（MyBatis foreach 限制）
                int batchSize = 500;
                for (int i = 0; i < chunkEntities.size(); i += batchSize) {
                    List<RagChunkEntity> batch = chunkEntities.subList(i, Math.min(i + batchSize, chunkEntities.size()));
                    chunkEntityMapper.insertBatch(batch);
                }
            }

            // 6. 重算 degree
            List<Long> ids = upserted.stream().map(RagEntity::getId).toList();
            if (!ids.isEmpty()) {
                entityMapper.recalculateDegree(ids);
            }
            entityIdsRef.set(ids);
        });

        log.info("Canonicalized {} entities for {} chunks",
                aggregated.size(), allChunkIds.size());

        return entityIdsRef.get();
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
