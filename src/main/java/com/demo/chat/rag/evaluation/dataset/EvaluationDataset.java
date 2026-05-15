package com.demo.chat.rag.evaluation.dataset;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 评估数据集实体
 */
public class EvaluationDataset {

    private Long id;
    private String name;
    private String description;
    private int version = 1;
    private String source = "hybrid";
    private String judgeModel;
    private int itemCount = 0;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;

    /** 内存中的数据项（非持久化字段，按需加载） */
    private List<EvaluationDatasetItem> items;

    // ======================== Getters & Setters ========================

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public int getVersion() {
        return version;
    }

    public void setVersion(int version) {
        this.version = version;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getJudgeModel() {
        return judgeModel;
    }

    public void setJudgeModel(String judgeModel) {
        this.judgeModel = judgeModel;
    }

    public int getItemCount() {
        return itemCount;
    }

    public void setItemCount(int itemCount) {
        this.itemCount = itemCount;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(OffsetDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    public List<EvaluationDatasetItem> getItems() {
        return items;
    }

    public void setItems(List<EvaluationDatasetItem> items) {
        this.items = items;
    }
}
