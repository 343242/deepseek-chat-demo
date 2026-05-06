package com.demo.deepseekchat.exception;

/**
 * 模型未找到异常
 */
public class ModelNotFoundException extends RuntimeException {
    private final String modelId;

    public ModelNotFoundException(String modelId, String message) {
        super(message);
        this.modelId = modelId;
    }

    public String getModelId() {
        return modelId;
    }
}
