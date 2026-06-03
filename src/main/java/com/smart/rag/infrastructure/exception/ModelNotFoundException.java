package com.smart.rag.infrastructure.exception;

import com.smart.rag.infrastructure.exception.errorcode.ServiceErrorCode;

import java.io.Serial;

/**
 * 模型未找到异常 (B类)
 */
public class ModelNotFoundException extends ServiceException {

    @Serial
    private static final long serialVersionUID = 34512341L;

    private final String modelId;

    public ModelNotFoundException(String modelId, String message) {
        super(ServiceErrorCode.MODEL_NOT_FOUND, message);
        this.modelId = modelId;
    }

    public String getModelId() {
        return modelId;
    }
}
