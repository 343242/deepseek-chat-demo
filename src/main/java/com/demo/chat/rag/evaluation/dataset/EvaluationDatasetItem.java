package com.demo.chat.rag.evaluation.dataset;

import java.util.List;
import java.util.Set;

/**
 * 评估数据集单条测试项
 */
public class EvaluationDatasetItem {

    private Long id;
    private Long datasetId;
    private String question;
    private String groundTruthAnswer;
    private Set<String> relevantChunkIds;
    private String relevantContent;
    private List<String> tags;
    private String status = "draft";
    private int seq;

    // ======================== Getters & Setters ========================

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getDatasetId() {
        return datasetId;
    }

    public void setDatasetId(Long datasetId) {
        this.datasetId = datasetId;
    }

    public String getQuestion() {
        return question;
    }

    public void setQuestion(String question) {
        this.question = question;
    }

    public String getGroundTruthAnswer() {
        return groundTruthAnswer;
    }

    public void setGroundTruthAnswer(String groundTruthAnswer) {
        this.groundTruthAnswer = groundTruthAnswer;
    }

    public Set<String> getRelevantChunkIds() {
        return relevantChunkIds;
    }

    public void setRelevantChunkIds(Set<String> relevantChunkIds) {
        this.relevantChunkIds = relevantChunkIds;
    }

    public String getRelevantContent() {
        return relevantContent;
    }

    public void setRelevantContent(String relevantContent) {
        this.relevantContent = relevantContent;
    }

    public List<String> getTags() {
        return tags;
    }

    public void setTags(List<String> tags) {
        this.tags = tags;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public int getSeq() {
        return seq;
    }

    public void setSeq(int seq) {
        this.seq = seq;
    }
}
