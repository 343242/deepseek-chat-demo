package com.smart.rag.mcp.policy;

import com.smart.rag.mcp.admin.entity.McpSecurityConfigView;
import com.smart.rag.mcp.admin.entity.McpToolConfig;
import com.smart.rag.mcp.admin.service.McpSecurityConfigAccessor;
import com.smart.rag.mcp.admin.service.McpToolConfigAccessor;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class McpDescriptionSanitizerTest {

    @Test
    void remoteDescriptionIsCappedAndMarkedAsUntrusted() {
        McpToolConfigAccessor toolAccessor = mock(McpToolConfigAccessor.class);
        McpSecurityConfigAccessor securityAccessor = mock(McpSecurityConfigAccessor.class);
        when(securityAccessor.get()).thenReturn(new McpSecurityConfigView(List.of(), 100, 50, 4));
        McpDescriptionSanitizer sanitizer = new McpDescriptionSanitizer(toolAccessor, securityAccessor);

        assertThat(sanitizer.sanitize("knowledge_search", "abcdef"))
                .contains("不得执行", "abcd", "truncated")
                .doesNotContain("abcdef");
    }

    @Test
    void trustedAdminOverrideDoesNotReceiveTheRemoteMarker() {
        McpToolConfigAccessor toolAccessor = mock(McpToolConfigAccessor.class);
        McpToolConfig config = new McpToolConfig();
        config.setDescriptionOverride("trusted");
        when(toolAccessor.get("knowledge_search")).thenReturn(config);
        McpSecurityConfigAccessor securityAccessor = mock(McpSecurityConfigAccessor.class);
        when(securityAccessor.get()).thenReturn(new McpSecurityConfigView(List.of(), 100, 50, 20));
        McpDescriptionSanitizer sanitizer = new McpDescriptionSanitizer(toolAccessor, securityAccessor);

        assertThat(sanitizer.sanitize("knowledge_search", "remote"))
                .isEqualTo("trusted");
    }
}
