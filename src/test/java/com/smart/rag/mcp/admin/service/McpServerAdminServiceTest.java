package com.smart.rag.mcp.admin.service;

import com.smart.rag.infrastructure.exception.ClientException;
import com.smart.rag.infrastructure.security.HostSafetyValidator;
import com.smart.rag.mcp.admin.entity.McpServerConfig;
import com.smart.rag.mcp.admin.mapper.McpServerConfigMapper;
import com.smart.rag.mcp.admin.mapper.McpToolConfigMapper;
import com.smart.rag.mcp.runtime.McpBearerTokenCodec;
import com.smart.rag.mcp.runtime.McpDesiredStateHasher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class McpServerAdminServiceTest {

    @Mock private McpServerConfigMapper serverMapper;
    @Mock private McpToolConfigMapper toolMapper;
    @Mock private TransactionTemplate transactionTemplate;
    @Mock private McpServerRuntime runtime;
    @Mock private HostSafetyValidator urlValidator;
    @Mock private McpBearerTokenCodec tokenCodec;
    @Mock private McpDesiredStateHasher desiredStateHasher;
    @Mock private McpToolAdminService toolService;

    private McpServerAdminService service;

    @BeforeEach
    void setUp() {
        service = new McpServerAdminService(serverMapper, toolMapper, transactionTemplate,
                runtime, urlValidator, tokenCodec, desiredStateHasher, toolService);
        lenient().doAnswer(invocation -> {
            java.util.function.Consumer<TransactionStatus> action = invocation.getArgument(0);
            action.accept(mock(TransactionStatus.class));
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
    }

    @Test
    void createCommitsDesiredStateWithLocalIdentityAndNoRemoteConnect() {
        when(desiredStateHasher.hash(any(), any(), anyBoolean())).thenReturn("abc123");
        doAnswer(invocation -> {
            McpServerConfig config = invocation.getArgument(0);
            config.setId(42L);
            return 1;
        }).when(serverMapper).insert(any(McpServerConfig.class));
        when(serverMapper.updateById(any(McpServerConfig.class))).thenReturn(1);

        McpServerConfig created = service.createServer(new CreateServerRequest(
                "https://mcp.example.com", "knowledge", null, true, null), "test-key-1");

        assertThat(created.getId()).isEqualTo(42L);
        assertThat(created.getServerId()).isEqualTo("mcp_42");
        assertThat(created.getDesiredStateHash()).isNotNull();
        verify(runtime, never()).connect(any());
        verify(runtime, never()).add(any(), any(), any());
    }

    @Test
    void createRejectsMissingIdempotencyKey() {
        assertThatThrownBy(() -> service.createServer(new CreateServerRequest(
                "https://mcp.example.com", "test", null, true, null), null))
                .isInstanceOf(ClientException.class);

        assertThatThrownBy(() -> service.createServer(new CreateServerRequest(
                "https://mcp.example.com", "test", null, true, null), ""))
                .isInstanceOf(ClientException.class);
    }

    @Test
    void createWithExistingKeyReturnsSameServer() {
        McpServerConfig existing = new McpServerConfig();
        existing.setId(42L);
        existing.setServerId("mcp_42");
        existing.setUrl("https://mcp.example.com");
        when(serverMapper.selectByCreateRequestKey("same-key")).thenReturn(existing);

        McpServerConfig result = service.createServer(new CreateServerRequest(
                "https://mcp.example.com", "test", null, true, null), "same-key");

        assertThat(result.getId()).isEqualTo(42L);
        assertThat(result.getServerId()).isEqualTo("mcp_42");
        verify(serverMapper, never()).insert(any(McpServerConfig.class));
    }
}
