package com.demo.chat.rag.evaluation.dataset;

import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Set;

/**
 * 评估数据集单条测试项（record）
 *
 * @param id                 主键
 * @param datasetId          所属数据集 ID
 * @param question           问题
 * @param groundTruthAnswer  标准答案
 * @param relevantChunkIds   相关 chunk ID 集合
 * @param relevantContent    相关内容
 * @param tags               标签
 * @param status             状态（默认 "draft"）
 * @param seq                序号
 */
public record EvaluationDatasetItem(
        @Nullable Long id,
        @Nullable Long datasetId,
        @Nullable String question,
        @Nullable String groundTruthAnswer,
        @Nullable Set<String> relevantChunkIds,
        @Nullable String relevantContent,
        @Nullable List<String> tags,
        @Nullable String status,
        int seq
) {
    public EvaluationDatasetItem {
        if (status == null) status = "draft";
    }
}
