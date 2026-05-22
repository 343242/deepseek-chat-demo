package com.smart.rag.exception;

import java.io.Serial;

/**
 * 模型未找到异常
 */
public class ModelNotFoundException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 34512341L;

    private final String modelId;

    public ModelNotFoundException(String modelId, String message) {
        super(message);
        this.modelId = modelId;
    }

    public String getModelId() {
        return modelId;
    }
}
