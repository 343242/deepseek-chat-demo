package com.demo.chat.rag.evaluation.runner;

import com.demo.chat.rag.evaluation.result.StageSnapshot;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Pipeline 阶段插桩器
 * <p>
 * 在 Pipeline 各阶段之间插入插桩点，捕获中间结果和耗时。
 * 核心功能：
 * <ul>
 *   <li>捕获每个阶段的完整快照（数据 + 时间戳 + 耗时）</li>
 *   <li>获取两个阶段之间的耗时（瓶颈定位）</li>
 *   <li>获取指定阶段的文档 ID 列表（阶段对比）</li>
 * </ul>
 * </p>
 */
public class PipelineInstrumenter {

    private final List<StageSnapshot> snapshots = new ArrayList<>();
    private final long startTimeMs;
    private final ObjectMapper objectMapper;

    public PipelineInstrumenter(ObjectMapper objectMapper) {
        this.startTimeMs = System.currentTimeMillis();
        this.objectMapper = objectMapper;
    }

    /**
     * 捕获阶段快照
     *
     * @param stageName 阶段名称
     * @param data      阶段输出数据（将被序列化为 JSON）
     */
    public void capture(String stageName, Object data) {
        long now = System.currentTimeMillis();
        long elapsed = now - startTimeMs;
        String serialized = serializeSafely(data);
        snapshots.add(new StageSnapshot(stageName, serialized, now, elapsed));
    }

    /**
     * 获取所有快照
     */
    public List<StageSnapshot> getSnapshots() {
        return List.copyOf(snapshots);
    }

    /**
     * 查找指定阶段的快照
     */
    public Optional<StageSnapshot> findStage(String stageName) {
        return snapshots.stream()
                .filter(s -> s.stageName().equals(stageName))
                .findFirst();
    }

    /**
     * 获取两个阶段之间的耗时（毫秒）
     *
     * @return 耗时，-1 表示找不到阶段
     */
    public long getLatencyBetweenStages(String fromStage, String toStage) {
        StageSnapshot from = findStage(fromStage).orElse(null);
        StageSnapshot to = findStage(toStage).orElse(null);
        if (from == null || to == null) return -1;
        return to.timestampMs() - from.timestampMs();
    }

    /**
     * 获取某个阶段的绝对耗时（距离开始）
     */
    public long getElapsedMs(String stageName) {
        return findStage(stageName)
                .map(StageSnapshot::elapsedMs)
                .orElse(-1L);
    }

    /**
     * 获取阶段耗时汇总
     *
     * @return 阶段名 → 耗时(ms) 的映射
     */
    public java.util.Map<String, Long> getStageLatencies() {
        java.util.Map<String, Long> latencies = new java.util.LinkedHashMap<>();
        for (int i = 0; i < snapshots.size(); i++) {
            StageSnapshot current = snapshots.get(i);
            if (i == 0) {
                latencies.put(current.stageName(), current.elapsedMs());
            } else {
                StageSnapshot prev = snapshots.get(i - 1);
                latencies.put(current.stageName(), current.timestampMs() - prev.timestampMs());
            }
        }
        return latencies;
    }

    private String serializeSafely(Object data) {
        try {
            return objectMapper.writeValueAsString(data);
        } catch (Exception e) {
            return String.valueOf(data);
        }
    }
}
