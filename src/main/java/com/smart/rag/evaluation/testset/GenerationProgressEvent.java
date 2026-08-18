package com.smart.rag.evaluation.testset;

/**
 * 生成进度事件（SSE 帧 payload）。
 *
 * @param phase   阶段：sampling / kg_build / edges / scenarios / synthesis / done
 * @param current 当前计数
 * @param total   阶段总量
 * @param message 人读消息
 */
public record GenerationProgressEvent(String phase, int current, int total, String message) {
}
