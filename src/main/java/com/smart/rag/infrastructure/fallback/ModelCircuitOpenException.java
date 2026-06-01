package com.smart.rag.infrastructure.fallback;

public class ModelCircuitOpenException extends RuntimeException {

    private final String modelId;

    public ModelCircuitOpenException(String modelId) {
        super("模型暂不可用，切换到下一个候选: " + modelId);
        this.modelId = modelId;
    }

    public String modelId() {
        return modelId;
    }
}
