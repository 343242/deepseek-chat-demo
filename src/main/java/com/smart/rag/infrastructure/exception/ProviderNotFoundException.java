package com.smart.rag.infrastructure.exception;

import com.smart.rag.infrastructure.exception.errorcode.RemoteErrorCode;

/**
 * 模型厂商未找到异常 (C类)
 * <p>
 * 当请求的 providerId 在 ProviderRegistry 中不存在时抛出。
 * 可能原因：厂商未配置 API Key、厂商 ID 拼写错误。
 */
public class ProviderNotFoundException extends RemoteException {

    private final String providerId;

    public ProviderNotFoundException(String providerId, String message) {
        super(RemoteErrorCode.PROVIDER_NOT_FOUND, message);
        this.providerId = providerId;
    }

    public String getProviderId() {
        return providerId;
    }
}
