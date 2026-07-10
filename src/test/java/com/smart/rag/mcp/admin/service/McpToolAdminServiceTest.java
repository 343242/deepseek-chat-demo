package com.smart.rag.mcp.admin.service;

import com.smart.rag.infrastructure.exception.RemoteException;
import com.smart.rag.mcp.admin.entity.McpSecurityConfigView;
import com.smart.rag.mcp.admin.entity.McpToolConfig;
import com.smart.rag.mcp.admin.mapper.McpToolConfigMapper;
import com.smart.rag.mcp.core.McpServerRegistry;
import com.smart.rag.mcp.core.ServerId;
import com.smart.rag.mcp.mcpclient.SyncMcpToolCallbackProvider;
import com.smart.rag.mcp.runtime.McpServerImpl;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class McpToolAdminServiceTest {

    @Mock private McpToolConfigMapper mapper;
    @Mock private TransactionTemplate transactionTemplate;
    @Mock private McpServerRegistry registry;
    @Mock private SyncMcpToolCallbackProvider callbackProvider;
    @Mock private McpToolConfigAccessor toolConfigAccessor;
    @Mock private McpSecurityConfigAccessor securityConfigAccessor;
    @Mock private McpServerImpl server;
    @Captor private ArgumentCaptor<List<McpToolConfig>> rows;

    private McpToolAdminService service;

    @BeforeEach
    void setUp() {
        service = new McpToolAdminService(mapper, transactionTemplate, registry,
                callbackProvider, toolConfigAccessor, securityConfigAccessor);
    }

    @Test
    void refreshPropagatesRemoteFailureAndDoesNotMutateDatabase() {
        when(registry.find(new ServerId("knowledge"))).thenReturn(Optional.of(server));
        when(server.listToolsFromRemote()).thenThrow(new RemoteException(
                com.smart.rag.infrastructure.exception.errorcode.RemoteErrorCode.MCP_SERVER_UNREACHABLE,
                "远端失败"));

        assertThatThrownBy(() -> service.refreshTools("knowledge"))
                .isInstanceOf(RemoteException.class);

        verify(mapper, never()).batchUpsert(any());
        verify(callbackProvider, never()).invalidateCache();
    }

    @Test
    void refreshCapsDescriptionsAndUsesOneBatchUpsert() {
        when(registry.find(new ServerId("knowledge"))).thenReturn(Optional.of(server));
        when(server.listToolsFromRemote()).thenReturn(List.of(
                tool("search", "123456789"), tool("lookup", "abcdefghi")));
        when(securityConfigAccessor.get()).thenReturn(new McpSecurityConfigView(List.of(), 4000, 1000, 5));
        doAnswer(invocation -> {
            java.util.function.Consumer<TransactionStatus> action = invocation.getArgument(0);
            action.accept(org.mockito.Mockito.mock(TransactionStatus.class));
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());

        service.refreshTools("knowledge");

        verify(mapper).batchUpsert(rows.capture());
        assertThat(rows.getValue()).hasSize(2)
                .allSatisfy(row -> {
                    assertThat(row.getDescription()).hasSize(5);
                    assertThat(row.getEnabled()).isFalse();
                    assertThat(row.getIntent()).isEqualTo("GENERAL_TOOL");
                });
        verify(callbackProvider).invalidateCache();
        verify(mapper, never()).selectByPrefixedName(any());
    }

    @Test
    void batchEnableInvalidatesCachedToolLists() {
        McpToolConfig disabled = new McpToolConfig();
        disabled.setEnabled(false);
        McpToolConfig enabled = new McpToolConfig();
        enabled.setEnabled(true);
        when(mapper.selectByServerId("knowledge"))
                .thenReturn(List.of(disabled), List.of(enabled));
        doAnswer(invocation -> {
            java.util.function.Consumer<TransactionStatus> action = invocation.getArgument(0);
            action.accept(org.mockito.Mockito.mock(TransactionStatus.class));
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());

        assertThat(service.listTools("knowledge").getFirst().getEnabled()).isFalse();
        service.batchEnableTools(List.of(1L));

        assertThat(service.listTools("knowledge").getFirst().getEnabled()).isTrue();
        verify(mapper, times(2)).selectByServerId("knowledge");
    }

    private static McpSchema.Tool tool(String name, String description) {
        return McpSchema.Tool.builder()
                .name(name)
                .description(description)
                .inputSchema(Map.of("type", "object"))
                .build();
    }
}
