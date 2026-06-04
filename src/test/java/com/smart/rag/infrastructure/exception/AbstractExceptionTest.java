package com.smart.rag.infrastructure.exception;

import com.smart.rag.infrastructure.exception.errorcode.ClientErrorCode;
import com.smart.rag.infrastructure.exception.errorcode.RemoteErrorCode;
import com.smart.rag.infrastructure.exception.errorcode.ServiceErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link AbstractException} constructors and methods,
 * tested via concrete subclasses ({@link ClientException}, {@link ServiceException}, {@link RemoteException}).
 */
class AbstractExceptionTest {

    @Nested
    @DisplayName("ClientException constructors")
    class ClientExceptionTests {

        @Test
        @DisplayName("constructor(IErrorCode) sets code and message from enum")
        void constructorWithErrorCodeOnly() {
            ClientException ex = new ClientException(ClientErrorCode.BAD_REQUEST);

            assertThat(ex.getErrorCode()).isEqualTo(ClientErrorCode.BAD_REQUEST);
            assertThat(ex.getErrorCode().getCode()).isEqualTo(100001);
            assertThat(ex.getUserMessage()).isEqualTo("请求参数错误");
            assertThat(ex.getMessage()).isEqualTo("请求参数错误");
        }

        @Test
        @DisplayName("constructor(IErrorCode, String) overrides message with detail")
        void constructorWithDetail() {
            ClientException ex = new ClientException(ClientErrorCode.VALIDATION_ERROR, "字段不能为空");

            assertThat(ex.getErrorCode()).isEqualTo(ClientErrorCode.VALIDATION_ERROR);
            assertThat(ex.getErrorCode().getCode()).isEqualTo(100002);
            assertThat(ex.getUserMessage()).isEqualTo("字段不能为空");
        }

        @Test
        @DisplayName("constructor(IErrorCode, String, Throwable) preserves cause")
        void constructorWithDetailAndCause() {
            RuntimeException cause = new RuntimeException("root cause");
            ClientException ex = new ClientException(ClientErrorCode.BAD_REQUEST, "详细错误", cause);

            assertThat(ex.getErrorCode()).isEqualTo(ClientErrorCode.BAD_REQUEST);
            assertThat(ex.getUserMessage()).isEqualTo("详细错误");
            assertThat(ex.getCause()).isSameAs(cause);
        }
    }

    @Nested
    @DisplayName("ServiceException constructors")
    class ServiceExceptionTests {

        @Test
        @DisplayName("constructor(ServiceErrorCode) sets code and message")
        void constructorWithErrorCodeOnly() {
            ServiceException ex = new ServiceException(ServiceErrorCode.NOT_FOUND);

            assertThat(ex.getErrorCode()).isEqualTo(ServiceErrorCode.NOT_FOUND);
            assertThat(ex.getErrorCode().getCode()).isEqualTo(200001);
            assertThat(ex.getUserMessage()).isEqualTo("资源不存在");
        }

        @Test
        @DisplayName("constructor(IErrorCode, String) overrides message")
        void constructorWithDetail() {
            ServiceException ex = new ServiceException(ServiceErrorCode.USER_NOT_FOUND, "用户 123 不存在");

            assertThat(ex.getErrorCode()).isEqualTo(ServiceErrorCode.USER_NOT_FOUND);
            assertThat(ex.getUserMessage()).isEqualTo("用户 123 不存在");
        }
    }

    @Nested
    @DisplayName("RemoteException constructors")
    class RemoteExceptionTests {

        @Test
        @DisplayName("constructor(RemoteErrorCode) sets code and message")
        void constructorWithErrorCodeOnly() {
            RemoteException ex = new RemoteException(RemoteErrorCode.PROVIDER_NOT_FOUND);

            assertThat(ex.getErrorCode()).isEqualTo(RemoteErrorCode.PROVIDER_NOT_FOUND);
            assertThat(ex.getErrorCode().getCode()).isEqualTo(300001);
            assertThat(ex.getUserMessage()).isEqualTo("厂商未配置");
        }

        @Test
        @DisplayName("constructor(IErrorCode, String, Throwable) preserves cause")
        void constructorWithDetailAndCause() {
            IOException cause = new IOException("connection reset");
            RemoteException ex = new RemoteException(RemoteErrorCode.MODEL_TIMEOUT, "GPT-4 超时", cause);

            assertThat(ex.getErrorCode()).isEqualTo(RemoteErrorCode.MODEL_TIMEOUT);
            assertThat(ex.getUserMessage()).isEqualTo("GPT-4 超时");
            assertThat(ex.getCause()).isSameAs(cause);
        }
    }

    @Nested
    @DisplayName("getUserMessage fallback behavior")
    class UserMessageTests {

        @Test
        @DisplayName("null detail falls back to errorCode message")
        void nullDetailFallsBackToErrorCodeMessage() {
            ClientException ex = new ClientException(ClientErrorCode.RATE_LIMITED, null);

            assertThat(ex.getUserMessage()).isEqualTo(ClientErrorCode.RATE_LIMITED.getMessage());
        }

        @Test
        @DisplayName("non-null detail takes precedence over errorCode message")
        void detailTakesPrecedence() {
            ServiceException ex = new ServiceException(ServiceErrorCode.INTERNAL_ERROR, "自定义错误描述");

            assertThat(ex.getUserMessage()).isEqualTo("自定义错误描述");
            assertThat(ex.getUserMessage()).isNotEqualTo(ServiceErrorCode.INTERNAL_ERROR.getMessage());
        }
    }

    /** Minimal IOException stub for RemoteException cause test. */
    private static class IOException extends Exception {
        IOException(String message) { super(message); }
    }
}
