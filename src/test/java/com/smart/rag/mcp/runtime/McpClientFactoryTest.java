package com.smart.rag.mcp.runtime;

import com.smart.rag.infrastructure.exception.RemoteException;
import com.smart.rag.infrastructure.exception.ServiceException;
import com.smart.rag.infrastructure.exception.errorcode.ServiceErrorCode;
import com.smart.rag.mcp.runtime.McpEndpointSafetyGuard;
import com.smart.rag.mcp.admin.entity.McpServerConfig;
import com.smart.rag.mcp.config.McpClientTransportConfiguration.McpClientTransportProperties;
import io.modelcontextprotocol.client.McpSyncClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class McpClientFactoryTest {

    @Mock private McpEndpointSafetyGuard safetyGuard;
    @Mock private McpBearerTokenCodec tokenCodec;
    @Mock private McpClientBuilder clientBuilder;
    @Mock private McpSyncClient client;

    private McpClientTransportProperties properties;
    private McpClientFactory factory;

    @BeforeEach
    void setUp() {
        properties = new McpClientTransportProperties();
        properties.setRequestTimeout("15s");
        factory = new McpClientFactory(safetyGuard, tokenCodec, properties, clientBuilder);
    }

    @Test
    void createClientPassesDecodedTokenAndInitializesClient() {
        McpServerConfig config = config("v1:stored");
        when(tokenCodec.decode("v1:stored")).thenReturn("plain-token");
        when(clientBuilder.build("https://mcp.example.com", "plain-token", Duration.ofSeconds(15)))
                .thenReturn(client);

        assertThat(factory.createClient(config)).isSameAs(client);

        verify(safetyGuard).validate("https://mcp.example.com");
        verify(client).initialize();
        verify(client, never()).close();
    }

    @Test
    void createClientClosesBuiltClientWhenInitializeFails() {
        McpServerConfig config = config(null);
        when(clientBuilder.build("https://mcp.example.com", null, Duration.ofSeconds(15)))
                .thenReturn(client);
        when(client.initialize()).thenThrow(new RuntimeException("connection refused"));

        assertThatThrownBy(() -> factory.createClient(config))
                .isInstanceOf(RemoteException.class)
                .hasMessageContaining("初始化失败")
                .hasCauseInstanceOf(RuntimeException.class);

        verify(client).close();
    }

    @Test
    void createClientDoesNotBuildAnonymousClientWhenTokenDecodeFails() {
        McpServerConfig config = config("broken");
        when(tokenCodec.decode("broken")).thenThrow(
                new ServiceException(ServiceErrorCode.INTERNAL_ERROR, "MCP Bearer Token 配置不可解密"));

        assertThatThrownBy(() -> factory.createClient(config))
                .isInstanceOf(ServiceException.class);

        verifyNoInteractions(clientBuilder, client);
    }

    @Test
    void invalidTimeoutFallsBackToThirtySeconds() {
        properties.setRequestTimeout("invalid");
        McpServerConfig config = config(null);
        when(clientBuilder.build("https://mcp.example.com", null, Duration.ofSeconds(30)))
                .thenReturn(client);

        factory.createClient(config);

        ArgumentCaptor<Duration> timeout = ArgumentCaptor.forClass(Duration.class);
        verify(clientBuilder).build(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.isNull(), timeout.capture());
        assertThat(timeout.getValue()).isEqualTo(Duration.ofSeconds(30));
    }

    private static McpServerConfig config(String storedToken) {
        McpServerConfig config = new McpServerConfig();
        config.setUrl("https://mcp.example.com");
        config.setBearerTokenEncrypted(storedToken);
        return config;
    }
}
