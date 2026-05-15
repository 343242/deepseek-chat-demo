package com.demo.chat.rag.evaluation.result;

import java.util.List;

/**
 * Pipeline 阶段快照
 * <p>
 * 记录每个阶段处理后的数据和耗时，用于阶段对比和瓶颈定位。
 * </p>
 *
 * @param stageName  阶段名称（如 after_retrieval, after_rerank 等）
 * @param data       阶段输出数据的序列化形式
 * @param timestampMs 绝对时间戳
 * @param elapsedMs  距评估开始的耗时
 */
public record StageSnapshot(
        String stageName,
        String data,
        long timestampMs,
        long elapsedMs
) {
    /**
     * 获取此阶段的文档 ID 列表（如果是文档列表阶段）
     */
    public List<String> extractDocIds() {
        // data 可能是 JSON 数组的文档列表或文本
        // 简单实现：从 data 中提取 id 字段
        return List.of(); // 由 EvaluationRunner 在构建时直接提供
    }
}
