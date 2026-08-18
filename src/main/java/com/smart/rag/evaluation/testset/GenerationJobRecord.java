package com.smart.rag.evaluation.testset;

import java.time.OffsetDateTime;

/**
 * 测试集生成任务记录（对应 {@code evaluation_dataset_gen_run} 表）。
 */
public record GenerationJobRecord(
        Long id,
        String name,
        long userId,
        String status,
        String configJson,
        String progressJson,
        Long datasetId,
        String error,
        OffsetDateTime startedAt,
        OffsetDateTime completedAt,
        OffsetDateTime createdAt) {
}
