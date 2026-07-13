package com.smart.rag.mcp.admin.service;

import com.smart.rag.mcp.admin.entity.McpToolConfig;
import com.smart.rag.mcp.admin.mapper.McpToolConfigMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class McpToolAdminServiceTest {

    @Mock private McpToolConfigMapper mapper;
    @Mock private TransactionTemplate transactionTemplate;

    private McpToolAdminService service;

    @BeforeEach
    void setUp() {
        service = new McpToolAdminService(mapper, transactionTemplate);
        lenient().doAnswer(invocation -> {
            java.util.function.Consumer<TransactionStatus> action = invocation.getArgument(0);
            action.accept(mock(TransactionStatus.class));
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
    }

    @Test
    void listToolsReturnsDirectDBRead() {
        McpToolConfig tool = new McpToolConfig();
        tool.setPrefixedToolName("mcp_1_search");
        when(mapper.selectByServerId("mcp_1")).thenReturn(List.of(tool));

        List<McpToolConfig> result = service.listTools("mcp_1");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getPrefixedToolName()).isEqualTo("mcp_1_search");
    }

    @Test
    void enableToolUpdatesDbDirectly() {
        McpToolConfig tool = new McpToolConfig();
        tool.setId(1L);
        tool.setEnabled(false);
        tool.setVersion(0L);
        when(mapper.selectById(1L)).thenReturn(tool);
        when(mapper.updateById(any(McpToolConfig.class))).thenReturn(1);

        service.enableTool(1L);

        assertThat(tool.getEnabled()).isTrue();
        verify(mapper).updateById(tool);
    }

    @Test
    void batchEnableExecutesOneTransaction() {
        service.batchEnableTools(List.of(1L, 2L, 3L));

        verify(mapper).batchUpdateEnabled(List.of(1L, 2L, 3L), true);
    }
}
