package com.demo.chat.rag.evaluation.result;

import com.demo.chat.rag.evaluation.metrics.retrieval.RetrievalMetrics;
import com.demo.chat.rag.evaluation.runner.PipelineInstrumenter;

import java.util.List;
import java.util.Set;

/**
 * 单条评估结果
 */
public class EvaluationResult {

    private Long id;
    private Long runId;
    private Long itemId;

    // 快照字段（评估时锁定，防止后续修改导致历史报告失效）
    private String itemQuestionSnapshot;
    private String itemGroundTruthSnapshot;
    private Set<String> itemRelevantChunkIdsSnapshot;

    // Pipeline 中间结果
    private String queryRewritten;
    private List<String> retrievedDocIds;
    private String generatedAnswer;
    private List<StageSnapshot> stageSnapshots;

    // 指标
    private RetrievalMetrics retrievalMetrics;
    private String generationMetrics; // JSONB，Phase 4 填充

    // 状态
    private String error;
    private int latencyMs;

    // ======================== Getters & Setters ========================

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getRunId() {
        return runId;
    }

    public void setRunId(Long runId) {
        this.runId = runId;
    }

    public Long getItemId() {
        return itemId;
    }

    public void setItemId(Long itemId) {
        this.itemId = itemId;
    }

    public String getItemQuestionSnapshot() {
        return itemQuestionSnapshot;
    }

    public void setItemQuestionSnapshot(String itemQuestionSnapshot) {
        this.itemQuestionSnapshot = itemQuestionSnapshot;
    }

    public String getItemGroundTruthSnapshot() {
        return itemGroundTruthSnapshot;
    }

    public void setItemGroundTruthSnapshot(String itemGroundTruthSnapshot) {
        this.itemGroundTruthSnapshot = itemGroundTruthSnapshot;
    }

    public Set<String> getItemRelevantChunkIdsSnapshot() {
        return itemRelevantChunkIdsSnapshot;
    }

    public void setItemRelevantChunkIdsSnapshot(Set<String> itemRelevantChunkIdsSnapshot) {
        this.itemRelevantChunkIdsSnapshot = itemRelevantChunkIdsSnapshot;
    }

    public String getQueryRewritten() {
        return queryRewritten;
    }

    public void setQueryRewritten(String queryRewritten) {
        this.queryRewritten = queryRewritten;
    }

    public List<String> getRetrievedDocIds() {
        return retrievedDocIds;
    }

    public void setRetrievedDocIds(List<String> retrievedDocIds) {
        this.retrievedDocIds = retrievedDocIds;
    }

    public String getGeneratedAnswer() {
        return generatedAnswer;
    }

    public void setGeneratedAnswer(String generatedAnswer) {
        this.generatedAnswer = generatedAnswer;
    }

    public List<StageSnapshot> getStageSnapshots() {
        return stageSnapshots;
    }

    public void setStageSnapshots(List<StageSnapshot> stageSnapshots) {
        this.stageSnapshots = stageSnapshots;
    }

    public RetrievalMetrics getRetrievalMetrics() {
        return retrievalMetrics;
    }

    public void setRetrievalMetrics(RetrievalMetrics retrievalMetrics) {
        this.retrievalMetrics = retrievalMetrics;
    }

    public String getGenerationMetrics() {
        return generationMetrics;
    }

    public void setGenerationMetrics(String generationMetrics) {
        this.generationMetrics = generationMetrics;
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }

    public int getLatencyMs() {
        return latencyMs;
    }

    public void setLatencyMs(int latencyMs) {
        this.latencyMs = latencyMs;
    }
}
