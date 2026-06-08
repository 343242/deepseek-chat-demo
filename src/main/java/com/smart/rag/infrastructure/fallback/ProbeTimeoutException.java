package com.smart.rag.infrastructure.fallback;

import com.smart.rag.infrastructure.exception.RemoteException;
import com.smart.rag.infrastructure.exception.errorcode.RemoteErrorCode;

import java.io.Serial;

/**
 * 首包探测超时异常 (C类)
 * <p>
 * 当流式模型在探测超时时间内未返回任何数据时抛出。
 * 触发 {@link StreamRetryHandler} 立即降级到下一个候选模型。
 * <p>
 * 继承 {@link RemoteException}，属于第三方服务错误（C类），可触发跨模型降级。
 */
public class ProbeTimeoutException extends RemoteException {

    @Serial
    private static final long serialVersionUID = 78234561L;

    private final String modelId;

    public ProbeTimeoutException(String modelId) {
        super(RemoteErrorCode.LLM_PROBE_TIMEOUT, "首包探测超时: " + modelId);
        this.modelId = modelId;
    }

    public String modelId() {
        return modelId;
    }
}

