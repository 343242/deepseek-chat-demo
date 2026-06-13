package com.smart.rag.infrastructure.llm.client;

import com.smart.rag.infrastructure.exception.RemoteException;
import com.smart.rag.infrastructure.exception.errorcode.RemoteErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

import java.io.IOException;

/**
 * HTTP 客户端异常处理器
 * <p>
 * 将所有异常统一转换为 {@link RemoteException}，保持异常层级一致：
 * <ul>
 *   <li>IOException — 包装为 {@code RemoteException(LLM_TRANSIENT_ERROR)}，保留原始 cause，
 *       使 RetryPolicy 通过 {@code getErrorCode() == LLM_TRANSIENT_ERROR} 判定可重试</li>
 *   <li>ResourceAccessException — Spring {@code RestClient} 抛出的资源访问异常，
 *       当其 cause 为 {@link IOException} 时解包并按 IOException 路径处理</li>
 *   <li>RemoteException — 原样返回（已是正确类型）</li>
 *   <li>RestClientResponseException — 按 HTTP 状态码映射为对应的 RemoteException</li>
 *   <li>其他 Exception — 包装为 RemoteException(LLM_STREAM_ERROR)</li>
 * </ul>
 */
public final class HttpClientErrorHandler {

    private static final Logger log = LoggerFactory.getLogger(HttpClientErrorHandler.class);

    private HttpClientErrorHandler() {}

    /**
     * 将 HTTP 客户端异常转换为正确的 {@link RemoteException} 类型。
     * <p>
     * <b>纯转换器</b>：本方法不抛出异常，仅返回转换后的异常对象。
     * 调用方负责通过 {@code throw HttpClientErrorHandler.translate(...)} 抛出。
     * 这种模式避免了"sneaky-throw"语义陷阱——签名承诺返回 {@code RuntimeException}，
     * 行为与签名一致，未来若增加新分支忘记返回值，编译器可立即捕获（不会意外返回 null）。
     * <p>
     * 调用方约定（已与现有调用点对齐）：
     * <ul>
     *   <li>阻塞路径：{@code throw HttpClientErrorHandler.translate(op, url, e)}</li>
     *   <li>Reactor 路径：{@code sink.error(HttpClientErrorHandler.translate(op, url, e))}</li>
     * </ul>
     *
     * @param operation 操作描述（如 "Chat", "Embedding"），用于异常消息
     * @param url       请求 URL，用于异常消息
     * @param e         原始异常
     * @return 转换后的 {@link RemoteException}（永不为 null）
     */
    public static RuntimeException translate(String operation, String url, Exception e) {
        if (e instanceof RemoteException re) {
            return re;
        }
        // Unwrap Spring RestClient ResourceAccessException → IOException (same semantics)
        if (e instanceof ResourceAccessException rae && rae.getCause() instanceof IOException ioCause) {
            return translate(operation, url, ioCause);
        }
        if (e instanceof IOException io) {
            log.warn("{} 请求 IO 异常: {} - {}", operation, url, io.getMessage());
            return new RemoteException(RemoteErrorCode.LLM_TRANSIENT_ERROR,
                operation + " IO failed: " + url + " - " + io.getMessage(), io);
        }
        if (e instanceof RestClientResponseException rcre) {
            int status = rcre.getStatusCode().value();
            String body = rcre.getResponseBodyAsString();
            RemoteErrorCode code;
            if (status == 429) {
                // 429 is transient/retriable — WARN is appropriate
                code = RemoteErrorCode.LLM_RATE_LIMITED;
                log.warn("{} 请求被限流: HTTP 429 from {} - {}", operation, url, body);
            } else if (status >= 500) {
                // 5xx is a server-side fault (business-visible) — ERROR for ops alerting
                code = RemoteErrorCode.LLM_TRANSIENT_ERROR;
                log.error("{} 请求服务端错误: HTTP {} from {} - {}", operation, status, url, body);
            } else {
                code = RemoteErrorCode.LLM_STREAM_ERROR;
            }
            return new RemoteException(code,
                operation + " failed: HTTP " + status + " from " + url + ": " + body, rcre);
        }
        return new RemoteException(RemoteErrorCode.LLM_STREAM_ERROR,
            operation + " failed: " + url + " - " + e.getMessage(), e);
    }
}
