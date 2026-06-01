package com.smart.rag.infrastructure.exception;

/**
 * 模型厂商未找到异常
 * <p>
 * 当请求的 providerId 在 ProviderRegistry 中不存在时抛出。
 * 可能原因：厂商未配置 API Key、厂商 ID 拼写错误。
 */
public class ProviderNotFoundException extends BusinessException {

    private final String providerId;

    public ProviderNotFoundException(String providerId, String message) {
        super(message);
        this.providerId = providerId;
    }

    public String getProviderId() {
        return providerId;
    }
}
