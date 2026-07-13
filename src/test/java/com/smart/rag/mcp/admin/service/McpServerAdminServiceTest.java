package com.smart.rag.mcp.admin.service;

import com.smart.rag.infrastructure.exception.ServiceException;
import com.smart.rag.infrastructure.security.HostSafetyValidator;
import com.smart.rag.mcp.admin.entity.McpServerConfig;
import com.smart.rag.mcp.admin.mapper.McpServerConfigMapper;
import com.smart.rag.mcp.admin.mapper.McpToolConfigMapper;
import com.smart.rag.mcp.runtime.McpBearerTokenCodec;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class McpServerAdminServiceTest {

    @Mock private McpServerConfigMapper serverMapper;
    @Mock private McpToolConfigMapper toolMapper;
    @Mock private TransactionTemplate transactionTemplate;
    @Mock private McpServerRuntime runtime;
    @Mock private HostSafetyValidator urlValidator;
    @Mock private McpBearerTokenCodec tokenCodec;
    @Mock private McpToolAdminService toolService;
    @Mock private McpSyncClient client;

    private McpServerAdminService service;

    @BeforeEach
    void setUp() {
        service = new McpServerAdminService(serverMapper, toolMapper, transactionTemplate,
                runtime, urlValidator, tokenCodec, toolService);
        doAnswer(invocation -> {
            java.util.function.Consumer<TransactionStatus> action = invocation.getArgument(0);
            action.accept(mock(TransactionStatus.class));
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
    }

    @Test
    void createClosesInitializedClientAndFailsWhenRegistryHandoffFails() {
        when(runtime.connect(any())).thenReturn(client);
        McpSchema.InitializeResult initializeResult = mock(McpSchema.InitializeResult.class);
        when(initializeResult.serverInfo()).thenReturn(new McpSchema.Implementation("knowledge", "1.0"));
        when(client.getCurrentInitializationResult()).thenReturn(initializeResult);
        doAnswer(invocation -> {
            McpServerConfig config = invocation.getArgument(0);
            config.setId(10L);
            return 1;
        }).when(serverMapper).insert(any(McpServerConfig.class));
        when(serverMapper.updateById(any(McpServerConfig.class))).thenReturn(1);
        doThrow(new IllegalStateException("registry unavailable"))
                .when(runtime).add(any(McpServerConfig.class), org.mockito.ArgumentMatchers.eq(client),
                        org.mockito.ArgumentMatchers.isNull());

        CreateServerRequest request = new CreateServerRequest(
                "https://mcp.example.com", "knowledge", null, true, null);

        assertThatThrownBy(() -> service.createServer(request))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("注册");
        verify(runtime).close(client);
    }

    @Test
    void createStoresPlaceholderWhenRemoteIdentityCannotBeCanonicalized() {
        when(runtime.connect(any())).thenReturn(client);
        McpSchema.InitializeResult initializeResult = mock(McpSchema.InitializeResult.class);
        when(initializeResult.serverInfo()).thenReturn(new McpSchema.Implementation("***", "1.0"));
        when(client.getCurrentInitializationResult()).thenReturn(initializeResult);
        doAnswer(invocation -> {
            McpServerConfig config = invocation.getArgument(0);
            config.setId(10L);
            return 1;
        }).when(serverMapper).insert(any(McpServerConfig.class));

        McpServerConfig created = service.createServer(new CreateServerRequest(
                "https://mcp.example.com", "broken", null, true, null));

        assertThat(created.getServerId()).isNull();
        // Phase C replaces placeholder model with desired/observed hash
        verify(runtime).close(client);
        verify(runtime).add(org.mockito.ArgumentMatchers.eq(created),
                org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.isNotNull());
    }

    @Test
    void createSucceedsAndRegistersInRuntime() {
        when(runtime.connect(any())).thenReturn(client);
        McpSchema.InitializeResult initializeResult = mock(McpSchema.InitializeResult.class);
        when(initializeResult.serverInfo()).thenReturn(new McpSchema.Implementation("knowledge", "1.0"));
        when(client.getCurrentInitializationResult()).thenReturn(initializeResult);
        doAnswer(invocation -> {
            McpServerConfig config = invocation.getArgument(0);
            config.setId(10L);
            return 1;
        }).when(serverMapper).insert(any(McpServerConfig.class));
        when(serverMapper.updateById(any(McpServerConfig.class))).thenReturn(1);

        McpServerConfig created = service.createServer(new CreateServerRequest(
                "https://mcp.example.com", "knowledge", null, true, null));

        assertThat(created).isNotNull();
        verify(runtime).add(any(), any(), org.mockito.ArgumentMatchers.isNull());
    }
}
