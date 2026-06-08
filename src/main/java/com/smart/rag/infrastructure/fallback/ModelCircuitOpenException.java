package com.smart.rag.infrastructure.fallback;

import com.smart.rag.infrastructure.exception.RemoteException;
import com.smart.rag.infrastructure.exception.errorcode.RemoteErrorCode;

import java.io.Serial;

/**
 * 模型熔断器打开异常 (C类)
 * <p>
 * 当模型的熔断器处于 OPEN 状态时抛出，表示该模型近期连续失败过多。
 * 触发 {@link StreamRetryHandler} 立即降级到下一个候选模型。
 * <p>
 * 继承 {@link RemoteException}，属于第三方服务错误（C类），可触发跨模型降级。
 */
public class ModelCircuitOpenException extends RemoteException {

    @Serial
    private static final long serialVersionUID = 67234561L;

    private final String modelId;

    public ModelCircuitOpenException(String modelId) {
        super(RemoteErrorCode.LLM_CIRCUIT_BREAKER_OPEN, "模型熔断器已打开: " + modelId);
        this.modelId = modelId;
    }

    public String modelId() {
        return modelId;
    }
}

