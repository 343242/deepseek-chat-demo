package com.smart.rag.infrastructure.llm.client;

import com.smart.rag.infrastructure.exception.RemoteException;
import com.smart.rag.infrastructure.exception.errorcode.RemoteErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClientResponseException;

import java.io.IOException;

/**
 * HTTP 客户端异常处理器
 * <p>
 * 将所有异常统一转换为 {@link RemoteException}，保持异常层级一致：
 * <ul>
 *   <li>IOException — 包装为 {@code RemoteException(LLM_TRANSIENT_ERROR)}，保留原始 cause，
 *       使 RetryPolicy 通过 {@code getErrorCode() == LLM_TRANSIENT_ERROR} 判定可重试</li>
 *   <li>RemoteException — 直接重新抛出</li>
 *   <li>RestClientResponseException — 按 HTTP 状态码映射为对应的 RemoteException</li>
 *   <li>其他 Exception — 包装为 RemoteException(LLM_STREAM_ERROR)</li>
 * </ul>
 */
public final class HttpClientErrorHandler {

    private static final Logger log = LoggerFactory.getLogger(HttpClientErrorHandler.class);

    private HttpClientErrorHandler() {}

    /**
     * 处理 HTTP 客户端异常，转换为正确的异常类型。
     *
     * @param operation 操作描述（如 "Chat", "Embedding"），用于异常消息
     * @param url       请求 URL，用于异常消息
     * @param e         原始异常
     * @return 不会返回（总是抛出异常）
     */
    public static RuntimeException translate(String operation, String url, Exception e) {
        if (e instanceof RemoteException re) {
            throw re;
        }
        if (e instanceof IOException io) {
            log.warn("{} 请求 IO 异常: {} - {}", operation, url, io.getMessage());
            throw new RemoteException(RemoteErrorCode.LLM_TRANSIENT_ERROR,
                operation + " IO failed: " + url + " - " + io.getMessage(), io);
        }
        if (e instanceof RestClientResponseException rcre) {
            int status = rcre.getStatusCode().value();
            String body = rcre.getResponseBodyAsString();
            RemoteErrorCode code;
            if (status == 429) {
                code = RemoteErrorCode.LLM_RATE_LIMITED;
                log.warn("{} 请求被限流: HTTP 429 from {} - {}", operation, url, body);
            } else if (status >= 500) {
                code = RemoteErrorCode.LLM_TRANSIENT_ERROR;
                log.warn("{} 请求服务端错误: HTTP {} from {} - {}", operation, status, url, body);
            } else {
                code = RemoteErrorCode.LLM_STREAM_ERROR;
            }
            throw new RemoteException(code,
                operation + " failed: HTTP " + status + " from " + url + ": " + body, rcre);
        }
        throw new RemoteException(RemoteErrorCode.LLM_STREAM_ERROR,
            operation + " failed: " + url + " - " + e.getMessage(), e);
    }
}
