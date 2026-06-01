package com.smart.rag.evaluation.runner;

import org.jspecify.annotations.Nullable;

import java.time.OffsetDateTime;

/**
 * 单次评估运行记录（record）
 *
 * @param id               主键
 * @param datasetId        数据集 ID
 * @param name             运行名称
 * @param configSnapshot   配置快照（JSON）
 * @param status           状态（默认 PENDING）
 * @param generationModel  生成模型
 * @param judgeModel       Judge 模型
 * @param summary          运行摘要（JSON）
 * @param startedAt        开始时间
 * @param completedAt      完成时间
 * @param createdAt        创建时间
 */
public record EvaluationRun(
        @Nullable Long id,
        @Nullable Long datasetId,
        @Nullable String name,
        @Nullable String configSnapshot,
        @Nullable EvaluationRunStatus status,
        @Nullable String generationModel,
        @Nullable String judgeModel,
        @Nullable String summary,
        @Nullable OffsetDateTime startedAt,
        @Nullable OffsetDateTime completedAt,
        @Nullable OffsetDateTime createdAt
) {
    public EvaluationRun {
        if (status == null) status = EvaluationRunStatus.PENDING;
    }
}
