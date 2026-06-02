package com.smart.rag.infrastructure.fallback;

/**
 * 首包探测超时异常
 * <p>
 * 当流式模型在探测超时时间内未返回任何数据时抛出。
 * 触发 {@link StreamRetryHandler} 立即降级到下一个候选模型。
 */
public class ProbeTimeoutException extends RuntimeException {

    private final String modelId;

    public ProbeTimeoutException(String modelId) {
        super("First-packet probe timeout for model: " + modelId);
        this.modelId = modelId;
    }

    public String modelId() {
        return modelId;
    }
}
