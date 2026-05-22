package com.smart.rag.rag.evaluation.dataset;

import org.jspecify.annotations.Nullable;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 评估数据集实体（record）
 *
 * @param id          主键
 * @param name        数据集名称
 * @param description 描述
 * @param version     版本号（默认 1）
 * @param source      来源（默认 "hybrid"）
 * @param judgeModel  Judge 模型
 * @param itemCount   数据项数量
 * @param createdAt   创建时间
 * @param updatedAt   更新时间
 * @param items       内存中的数据项（瞬态字段，非持久化）
 */
public record EvaluationDataset(
        @Nullable Long id,
        @Nullable String name,
        @Nullable String description,
        int version,
        @Nullable String source,
        @Nullable String judgeModel,
        int itemCount,
        @Nullable OffsetDateTime createdAt,
        @Nullable OffsetDateTime updatedAt,
        @Nullable List<EvaluationDatasetItem> items
) {
    public EvaluationDataset {
        if (version == 0) version = 1;
        if (source == null) source = "hybrid";
    }
}
