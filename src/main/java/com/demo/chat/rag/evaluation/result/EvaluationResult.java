package com.demo.chat.rag.evaluation.result;

import com.demo.chat.rag.evaluation.metrics.retrieval.RetrievalMetrics;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Set;

/**
 * 单条评估结果（record）
 *
 * @param id                            主键
 * @param runId                         运行 ID
 * @param itemId                        数据项 ID
 * @param itemQuestionSnapshot          问题快照
 * @param itemGroundTruthSnapshot       标准答案快照
 * @param itemRelevantChunkIdsSnapshot  相关 chunk IDs 快照
 * @param queryRewritten                改写后查询
 * @param retrievedDocIds               检索到的文档 ID 列表
 * @param generatedAnswer               生成的答案
 * @param stageSnapshots                各阶段快照
 * @param retrievalMetrics              检索指标
 * @param generationMetrics             生成指标（JSON）
 * @param error                         错误信息
 * @param latencyMs                     耗时（毫秒）
 */
public record EvaluationResult(
        @Nullable Long id,
        long runId,
        long itemId,
        @Nullable String itemQuestionSnapshot,
        @Nullable String itemGroundTruthSnapshot,
        @Nullable Set<String> itemRelevantChunkIdsSnapshot,
        @Nullable String queryRewritten,
        @Nullable List<String> retrievedDocIds,
        @Nullable String generatedAnswer,
        @Nullable List<StageSnapshot> stageSnapshots,
        @Nullable RetrievalMetrics retrievalMetrics,
        @Nullable String generationMetrics,
        @Nullable String error,
        int latencyMs
) {}
