package com.smart.rag.infrastructure.llm.client;

import com.smart.rag.infrastructure.exception.RateLimitedException;
import com.smart.rag.infrastructure.exception.RemoteException;
import com.smart.rag.infrastructure.exception.errorcode.IErrorCode;
import com.smart.rag.infrastructure.exception.errorcode.RemoteErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HttpClientErrorHandler 异常分类单测 —— 守护 RetryPolicy 可重试性判定：
 * 读响应体失败的 RestClientException（cause 为 IOException）必须归为 LLM_TRANSIENT_ERROR，
 * 否则瞬态网络错误被误判为不可重试（生产事故：DeepSeek 实体抽取 60s 读超时一次失败即放弃）。
 */
@DisplayName("HttpClientErrorHandler 异常分类（可重试性判定）")
class HttpClientErrorHandlerTest {

    private static final String URL = "https://api.deepseek.com/chat/completions";

    private static IErrorCode codeOf(Exception e) {
        RuntimeException translated = HttpClientErrorHandler.translate("Chat", URL, e);
        assertThat(translated).isInstanceOf(RemoteException.class);
        return ((RemoteException) translated).getErrorCode();
    }

    @Test
    @DisplayName("RestClientException 带 IOException cause（读响应体超时/重置）→ LLM_TRANSIENT_ERROR 可重试")
    void restClientExceptionWithIoCauseIsTransient() {
        RestClientException e = new RestClientException(
                "Error while extracting response for type [java.lang.String] and content type [application/json]",
                new IOException("request timed out"));

        RuntimeException translated = HttpClientErrorHandler.translate("Chat", URL, e);

        assertThat(((RemoteException) translated).getErrorCode())
                .isEqualTo(RemoteErrorCode.LLM_TRANSIENT_ERROR);
        assertThat(translated.getMessage()).startsWith("Chat IO failed: " + URL);
    }

    @Test
    @DisplayName("ResourceAccessException 带 IOException cause 仍按 IO 路径解包（回归守护）")
    void resourceAccessExceptionWithIoCauseIsTransient() {
        assertThat(codeOf(new ResourceAccessException("I/O error", new IOException("connection reset"))))
                .isEqualTo(RemoteErrorCode.LLM_TRANSIENT_ERROR);
    }

    @Test
    @DisplayName("裸 IOException → LLM_TRANSIENT_ERROR")
    void directIoExceptionIsTransient() {
        assertThat(codeOf(new IOException("connection reset")))
                .isEqualTo(RemoteErrorCode.LLM_TRANSIENT_ERROR);
    }

    @Test
    @DisplayName("HTTP 429 → LLM_RATE_LIMITED（状态码映射优先于 RestClientException 泛化解包）")
    void rateLimitedMapsBeforeGenericUnwrap() {
        HttpClientErrorException e = HttpClientErrorException.create(
                HttpStatus.TOO_MANY_REQUESTS, "Too Many Requests", null, null, StandardCharsets.UTF_8);
        assertThat(codeOf(e)).isEqualTo(RemoteErrorCode.LLM_RATE_LIMITED);
    }

    @Test
    @DisplayName("HTTP 5xx → LLM_TRANSIENT_ERROR")
    void serverErrorIsTransient() {
        HttpServerErrorException e = HttpServerErrorException.create(
                HttpStatus.INTERNAL_SERVER_ERROR, "Internal Server Error", null, null, StandardCharsets.UTF_8);
        assertThat(codeOf(e)).isEqualTo(RemoteErrorCode.LLM_TRANSIENT_ERROR);
    }

    @Test
    @DisplayName("无 IOException cause 的 RestClientException → 兜底 LLM_STREAM_ERROR")
    void plainRestClientExceptionFallsBack() {
        assertThat(codeOf(new RestClientException("boom")))
                .isEqualTo(RemoteErrorCode.LLM_STREAM_ERROR);
    }

    // ==================== translateStatus / parseRetryAfter（WS1） ====================

    @Test
    @DisplayName("translateStatus 429 + Retry-After 秒数 → RateLimitedException 携带毫秒值")
    void translateStatus429WithSecondsRetryAfter() {
        RuntimeException ex = HttpClientErrorHandler.translateStatus(
                "Chat Stream", URL, 429, "rate limited", "30");

        assertThat(ex).isInstanceOf(RateLimitedException.class);
        assertThat(((RemoteException) ex).getErrorCode()).isEqualTo(RemoteErrorCode.LLM_RATE_LIMITED);
        assertThat(((RateLimitedException) ex).retryAfterMs()).isEqualTo(30_000L);
    }

    @Test
    @DisplayName("translateStatus 429 + Retry-After HTTP-date → 按剩余时间换算毫秒")
    void translateStatus429WithHttpDateRetryAfter() {
        String httpDate = DateTimeFormatter.RFC_1123_DATE_TIME
                .format(ZonedDateTime.now().plusSeconds(45));

        RuntimeException ex = HttpClientErrorHandler.translateStatus(
                "Chat Stream", URL, 429, "rate limited", httpDate);

        assertThat(ex).isInstanceOf(RateLimitedException.class);
        Long ms = ((RateLimitedException) ex).retryAfterMs();
        assertThat(ms).isNotNull().isBetween(40_000L, 45_000L);
    }

    @Test
    @DisplayName("translateStatus 429 无 Retry-After → retryAfterMs 为 null")
    void translateStatus429WithoutRetryAfter() {
        RuntimeException ex = HttpClientErrorHandler.translateStatus(
                "Chat Stream", URL, 429, "rate limited", null);

        assertThat(ex).isInstanceOf(RateLimitedException.class);
        assertThat(((RateLimitedException) ex).retryAfterMs()).isNull();
    }

    @Test
    @DisplayName("translateStatus Retry-After >60s 不截断，保留原值（放弃判定交给 isRetryable）")
    void translateStatusKeepsLargeRetryAfter() {
        RuntimeException ex = HttpClientErrorHandler.translateStatus(
                "Chat Stream", URL, 429, "rate limited", "3600");

        assertThat(((RateLimitedException) ex).retryAfterMs()).isEqualTo(3_600_000L);
    }

    @Test
    @DisplayName("translateStatus 5xx → LLM_TRANSIENT_ERROR")
    void translateStatus5xxIsTransient() {
        RuntimeException ex = HttpClientErrorHandler.translateStatus(
                "Chat Stream", URL, 503, "unavailable", null);
        assertThat(((RemoteException) ex).getErrorCode()).isEqualTo(RemoteErrorCode.LLM_TRANSIENT_ERROR);
    }

    @Test
    @DisplayName("translateStatus 其余 4xx → LLM_STREAM_ERROR")
    void translateStatusOther4xxIsStreamError() {
        RuntimeException ex = HttpClientErrorHandler.translateStatus(
                "Chat Stream", URL, 400, "bad request", null);
        assertThat(((RemoteException) ex).getErrorCode()).isEqualTo(RemoteErrorCode.LLM_STREAM_ERROR);
    }

    @Test
    @DisplayName("RestClientResponseException 429 + Retry-After 头 → 经 translate 委托携带 retryAfterMs")
    void translateDelegatesWithRetryAfterHeader() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Retry-After", "2");
        HttpClientErrorException e = HttpClientErrorException.create(
                HttpStatus.TOO_MANY_REQUESTS, "Too Many Requests", headers, "rate limited".getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);

        RuntimeException translated = HttpClientErrorHandler.translate("Chat", URL, e);

        assertThat(translated).isInstanceOf(RateLimitedException.class);
        assertThat(((RateLimitedException) translated).retryAfterMs()).isEqualTo(2_000L);
    }

    @Test
    @DisplayName("parseRetryAfter：非法值 / 过去日期 / ISO-8601 形态")
    void parseRetryAfterEdgeCases() {
        assertThat(HttpClientErrorHandler.parseRetryAfter(null)).isNull();
        assertThat(HttpClientErrorHandler.parseRetryAfter("  ")).isNull();
        assertThat(HttpClientErrorHandler.parseRetryAfter("soon")).isNull();
        // 过去的 HTTP-date → null
        assertThat(HttpClientErrorHandler.parseRetryAfter(
                DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.now().minusSeconds(10)))).isNull();
        // ISO-8601 形态
        Long iso = HttpClientErrorHandler.parseRetryAfter(Instant.now().plusSeconds(10).toString());
        assertThat(iso).isBetween(8_000L, 10_000L);
    }
}
