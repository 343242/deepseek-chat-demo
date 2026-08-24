package com.smart.rag.infrastructure.llm.client;

import com.smart.rag.infrastructure.exception.RemoteException;
import com.smart.rag.infrastructure.exception.errorcode.IErrorCode;
import com.smart.rag.infrastructure.exception.errorcode.RemoteErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

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
}
