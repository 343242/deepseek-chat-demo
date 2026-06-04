package com.smart.rag.infrastructure.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the exception type hierarchy: instanceof relationships and inheritance.
 */
class ExceptionHierarchyTest {

    @Test
    @DisplayName("ClientException extends AbstractException")
    void clientExceptionExtendsAbstract() {
        ClientException ex = new ClientException(
                com.smart.rag.infrastructure.exception.errorcode.ClientErrorCode.BAD_REQUEST);
        assertThat(ex).isInstanceOf(AbstractException.class);
        assertThat(ex).isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("ServiceException extends AbstractException")
    void serviceExceptionExtendsAbstract() {
        ServiceException ex = new ServiceException(
                com.smart.rag.infrastructure.exception.errorcode.ServiceErrorCode.NOT_FOUND);
        assertThat(ex).isInstanceOf(AbstractException.class);
        assertThat(ex).isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("RemoteException extends AbstractException")
    void remoteExceptionExtendsAbstract() {
        RemoteException ex = new RemoteException(
                com.smart.rag.infrastructure.exception.errorcode.RemoteErrorCode.PROVIDER_NOT_FOUND);
        assertThat(ex).isInstanceOf(AbstractException.class);
        assertThat(ex).isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("ContentFilteredException extends ClientException")
    void contentFilteredExtendsClient() {
        ContentFilteredException ex = new ContentFilteredException("敏感词");
        assertThat(ex).isInstanceOf(ClientException.class);
        assertThat(ex).isInstanceOf(AbstractException.class);
    }

    @Test
    @DisplayName("RateLimitExceededException extends ClientException")
    void rateLimitedExtendsClient() {
        RateLimitExceededException ex = new RateLimitExceededException("too fast");
        assertThat(ex).isInstanceOf(ClientException.class);
        assertThat(ex).isInstanceOf(AbstractException.class);
    }

    @Test
    @DisplayName("ModelNotFoundException extends ServiceException")
    void modelNotFoundExtendsService() {
        ModelNotFoundException ex = new ModelNotFoundException("gpt-4", "模型不存在: gpt-4");
        assertThat(ex).isInstanceOf(ServiceException.class);
        assertThat(ex).isInstanceOf(AbstractException.class);
    }

    @Test
    @DisplayName("ProviderNotFoundException extends RemoteException")
    void providerNotFoundExtendsRemote() {
        ProviderNotFoundException ex = new ProviderNotFoundException("openai", "厂商未配置: openai");
        assertThat(ex).isInstanceOf(RemoteException.class);
        assertThat(ex).isInstanceOf(AbstractException.class);
    }

    @Test
    @DisplayName("BusinessException is marked @Deprecated and extends AbstractException")
    void businessExceptionDeprecatedTransition() {
        BusinessException ex = new BusinessException(
                com.smart.rag.infrastructure.exception.errorcode.ErrorCode.BAD_REQUEST);
        assertThat(ex).isInstanceOf(AbstractException.class);
        assertThat(ex).isInstanceOf(RuntimeException.class);

        @SuppressWarnings("deprecation")
        boolean deprecated = BusinessException.class.isAnnotationPresent(Deprecated.class);
        assertThat(deprecated).isTrue();
    }

    @Test
    @DisplayName("ClientException is NOT instanceof ServiceException")
    void clientIsNotService() {
        ClientException ex = new ClientException(
                com.smart.rag.infrastructure.exception.errorcode.ClientErrorCode.BAD_REQUEST);
        assertThat(ex).isNotInstanceOf(ServiceException.class);
        assertThat(ex).isNotInstanceOf(RemoteException.class);
    }

    @Test
    @DisplayName("ServiceException is NOT instanceof ClientException")
    void serviceIsNotClient() {
        ServiceException ex = new ServiceException(
                com.smart.rag.infrastructure.exception.errorcode.ServiceErrorCode.NOT_FOUND);
        assertThat(ex).isNotInstanceOf(ClientException.class);
        assertThat(ex).isNotInstanceOf(RemoteException.class);
    }
}
