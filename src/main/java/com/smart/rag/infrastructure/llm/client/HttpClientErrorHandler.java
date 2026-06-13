package com.smart.rag.infrastructure.llm.client;

import com.smart.rag.infrastructure.exception.RemoteException;
import com.smart.rag.infrastructure.exception.errorcode.RemoteErrorCode;
import org.springframework.web.client.RestClientResponseException;

import java.io.IOException;

/**
 * HTTP 客户端异常处理器
 * <p>
 * 按异常类型分类处理，确保 IOException 不被包装为 RemoteException，
 * 使 RetryPolicy.isRetryable() 的 instanceof IOException 判断生效。
 * <p>
 * 分类规则：
 * <ul>
 *   <li>IOException — 直接抛出（sneaky throw），保持原始类型供重试策略识别</li>
 *   <li>RemoteException — 直接重新抛出</li>
 *   <li>RestClientResponseException — 按 HTTP 状态码映射为对应的 RemoteException</li>
 *   <li>其他 Exception — 包装为 RemoteException(LLM_STREAM_ERROR)</li>
 * </ul>
 */
public final class HttpClientErrorHandler {

    private HttpClientErrorHandler() {}

    /**
     * 处理 HTTP 客户端异常，转换为正确的异常类型。
     *
     * @param operation 操作描述（如 "Chat", "Embedding"），用于异常消息
     * @param url       请求 URL，用于异常消息
     * @param e         原始异常
     * @return 不会返回（总是抛出异常）
     */
    @SuppressWarnings("unchecked")
    public static RuntimeException translate(String operation, String url, Exception e) {
        if (e instanceof RemoteException re) {
            throw re;
        }
        if (e instanceof IOException io) {
            sneakyThrow(io);
            throw new AssertionError("unreachable");
        }
        if (e instanceof RestClientResponseException rcre) {
            int status = rcre.getStatusCode().value();
            String body = rcre.getResponseBodyAsString();
            RemoteErrorCode code;
            if (status == 429) {
                code = RemoteErrorCode.LLM_RATE_LIMITED;
            } else if (status >= 500) {
                code = RemoteErrorCode.LLM_TRANSIENT_ERROR;
            } else {
                code = RemoteErrorCode.LLM_STREAM_ERROR;
            }
            throw new RemoteException(code,
                operation + " failed: HTTP " + status + " from " + url + ": " + body, rcre);
        }
        throw new RemoteException(RemoteErrorCode.LLM_STREAM_ERROR,
            operation + " failed: " + url + " - " + e.getMessage(), e);
    }

    /**
     * Sneaky throw — 绕过 checked exception 的编译器检查。
     * 通过类型擦除将 Throwable 转为 RuntimeException，运行时抛出原始异常。
     */
    @SuppressWarnings("unchecked")
    private static <E extends Throwable> void sneakyThrow(Throwable e) throws E {
        throw (E) e;
    }
}
