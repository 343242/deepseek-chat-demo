package com.smart.rag.mcp.admin.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smart.rag.infrastructure.exception.ClientException;
import com.smart.rag.mcp.admin.entity.McpSecurityConfigView;
import com.smart.rag.mcp.admin.mapper.McpSecurityConfigMapper;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class McpSecurityAdminServiceTest {

    private final McpSecurityConfigMapper mapper = mock(McpSecurityConfigMapper.class);
    private final TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
    private final McpSecurityConfigAccessor accessor = mock(McpSecurityConfigAccessor.class);
    private final McpSecurityAdminService service = new McpSecurityAdminService(
            mapper, transactionTemplate, accessor, new ObjectMapper(), new McpSecurityConfigValidator());

    @Test
    void updateRejectsMalformedRegexBeforePersistence() {
        McpSecurityConfigView view = new McpSecurityConfigView(List.of("[broken"), 4000, 1000, 500);

        assertThatThrownBy(() -> service.updateSecurityConfig(view))
                .isInstanceOf(ClientException.class)
                .hasMessageContaining("正则");
        verifyNoInteractions(mapper, transactionTemplate, accessor);
    }

    @Test
    void updateRejectsHighRiskCapAboveDefaultBeforePersistence() {
        McpSecurityConfigView view = new McpSecurityConfigView(List.of(), 1000, 2000, 500);

        assertThatThrownBy(() -> service.updateSecurityConfig(view))
                .isInstanceOf(ClientException.class)
                .hasMessageContaining("高风险");
        verifyNoInteractions(mapper, transactionTemplate, accessor);
    }

    @Test
    void updateRejectsNonPositiveLimitsBeforePersistence() {
        McpSecurityConfigView view = new McpSecurityConfigView(List.of(), 0, 0, 0);

        assertThatThrownBy(() -> service.updateSecurityConfig(view))
                .isInstanceOf(ClientException.class);
        verifyNoInteractions(mapper, transactionTemplate, accessor);
    }
}
