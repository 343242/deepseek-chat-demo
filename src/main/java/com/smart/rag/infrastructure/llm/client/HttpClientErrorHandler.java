package com.smart.rag.infrastructure.llm.client;

import com.smart.rag.infrastructure.exception.RateLimitedException;
import com.smart.rag.infrastructure.exception.RemoteException;
import com.smart.rag.infrastructure.exception.errorcode.RemoteErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.io.IOException;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * HTTP 客户端异常处理器
 * <p>
 * 将所有异常统一转换为 {@link RemoteException}，保持异常层级一致：
 * <ul>
 * <li>IOException — 包装为 {@code RemoteException(LLM_TRANSIENT_ERROR)}，保留原始 cause，
 *       使 RetryPolicy 通过 {@code getErrorCode() == LLM_TRANSIENT_ERROR} 判定可重试</li>
 * <li>RemoteException — 原样返回（已是正确类型）</li>
 * <li>RestClientResponseException — 按 HTTP 状态码映射为对应的 RemoteException</li>
 * <li>RestClientException（含 ResourceAccessException）— cause 为 {@link IOException} 时解包并按
 *       IOException 路径处理（瞬态可重试）</li>
 * <li>其他 Exception — 包装为 RemoteException(LLM_STREAM_ERROR)</li>
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
        if (e instanceof IOException io) {
            log.warn("{} 请求 IO 异常: {} - {}", operation, url, io.getMessage());
            return new RemoteException(RemoteErrorCode.LLM_TRANSIENT_ERROR,
                operation + " IO failed: " + url + " - " + io.getMessage(), io);
        }
        if (e instanceof RestClientResponseException rcre) {
            return translateStatus(operation, url, rcre.getStatusCode().value(),
                rcre.getResponseBodyAsString(),
                rcre.getResponseHeaders() != null ? rcre.getResponseHeaders().getFirst("Retry-After") : null);
        }
        // Unwrap RestClientException（含 ResourceAccessException）的 IOException cause → LLM_TRANSIENT_ERROR。
        // 约束：RestClient 的 HttpMessageConverterExtractor 读响应体超时/被重置时抛
        // RestClientException("Error while extracting response ...")，cause 为 IOException——
        // 必须在 RestClientResponseException 之后判定（后者是前者的子类，需优先按状态码映射）。
        if (e instanceof RestClientException rce && rce.getCause() instanceof IOException ioCause) {
            return translate(operation, url, ioCause);
        }
        return new RemoteException(RemoteErrorCode.LLM_STREAM_ERROR,
            operation + " failed: " + url + " - " + e.getMessage(), e);
    }

    /**
     * 按 HTTP 状态码映射为对应的 {@link RemoteException}（阻塞/流式两路共用的公共出口，
     * design llm-resilience-optimization WS1）。
     * <ul>
     * <li>429 → {@link RateLimitedException}（携带 Retry-After 毫秒数，可解析时）</li>
     * <li>5xx → RemoteException(LLM_TRANSIENT_ERROR)，ERROR 日志</li>
     * <li>其余 4xx → RemoteException(LLM_STREAM_ERROR)</li>
     * </ul>
     *
     * @param retryAfterHeader 原始 Retry-After 响应头（秒数整数或 HTTP-date），可为 null
     */
    public static RuntimeException translateStatus(String operation, String url,
            int status, String body, String retryAfterHeader) {
        if (status == 429) {
            // 429 is transient/retriable — WARN is appropriate
            Long retryAfterMs = parseRetryAfter(retryAfterHeader);
            log.warn("{} 请求被限流: HTTP 429 from {} - {} (Retry-After: {})",
                operation, url, body, retryAfterHeader);
            return new RateLimitedException(
                operation + " failed: HTTP 429 from " + url + ": " + body, retryAfterMs);
        }
        if (status >= 500) {
            // 5xx is a server-side fault (business-visible) — ERROR for ops alerting
            log.error("{} 请求服务端错误: HTTP {} from {} - {}", operation, status, url, body);
            return new RemoteException(RemoteErrorCode.LLM_TRANSIENT_ERROR,
                operation + " failed: HTTP " + status + " from " + url + ": " + body);
        }
        return new RemoteException(RemoteErrorCode.LLM_STREAM_ERROR,
            operation + " failed: HTTP " + status + " from " + url + ": " + body);
    }

    /**
     * 解析 Retry-After 头：秒数整数或 HTTP-date（RFC 1123 / ISO-8601）。
     * 解析失败或日期已过去 → null（调用方按无限流指示的普通 429 处理）。
     * {@code >60s} 不截断——保留原值交由 {@code RetryPolicy.isRetryable} 判定放弃（决策 15）。
     */
    static Long parseRetryAfter(String header) {
        if (header == null || header.isBlank()) {
            return null;
        }
        String trimmed = header.trim();
        try {
            return Long.parseLong(trimmed) * 1000L;
        } catch (NumberFormatException ignored) {
            // fall through to HTTP-date
        }
        try {
            Instant date = parseHttpDate(trimmed);
            long ms = date.toEpochMilli() - System.currentTimeMillis();
            return ms > 0 ? ms : null;
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    private static Instant parseHttpDate(String value) {
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            return DateTimeFormatter.RFC_1123_DATE_TIME.parse(value, Instant::from);
        }
    }
}
