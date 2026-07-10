package com.smart.rag.mcp.policy;

import com.smart.rag.infrastructure.exception.ClientException;
import com.smart.rag.mcp.admin.entity.McpSecurityConfigView;
import com.smart.rag.mcp.admin.entity.McpToolConfig;
import com.smart.rag.mcp.admin.service.McpSecurityConfigAccessor;
import com.smart.rag.mcp.admin.service.McpToolConfigAccessor;
import com.smart.rag.mcp.core.McpArgs;
import com.smart.rag.mcp.core.McpToolResult;
import com.smart.rag.mcp.core.McpTools;
import com.smart.rag.mcp.core.Subject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class McpSecurityGuardTest {

    @Test
    void unauthenticatedSubjectIsRejectedBeforeSensitiveArgumentsAreInspected() {
        McpToolConfigAccessor toolAccessor = mock(McpToolConfigAccessor.class);
        McpSecurityConfigAccessor securityAccessor = mock(McpSecurityConfigAccessor.class);
        when(securityAccessor.patterns()).thenReturn(List.of(Pattern.compile("secret")));
        McpTools tools = mock(McpTools.class);
        McpSecurityGuard guard = new McpSecurityGuard(toolAccessor, securityAccessor);

        assertThatThrownBy(() -> guard.guard(new McpSecurityGuard.Invocation(
                tools, "knowledge_search", McpArgs.of(Map.of("token", "secret")), null)))
                .isInstanceOf(ClientException.class);

        verify(tools, never()).call(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void highRiskOutputIsCappedAndMarkedAsUntrusted() {
        McpToolConfigAccessor toolAccessor = mock(McpToolConfigAccessor.class);
        McpToolConfig config = new McpToolConfig();
        config.setRisk("high");
        when(toolAccessor.get("knowledge_search")).thenReturn(config);
        McpSecurityConfigAccessor securityAccessor = mock(McpSecurityConfigAccessor.class);
        when(securityAccessor.patterns()).thenReturn(List.of());
        when(securityAccessor.get()).thenReturn(new McpSecurityConfigView(List.of(), 10, 4, 100));
        McpTools tools = mock(McpTools.class);
        Subject subject = new Subject(7L, null);
        McpArgs args = McpArgs.empty();
        when(tools.call("knowledge_search", args, subject))
                .thenReturn(McpToolResult.success("abcdef"));
        McpSecurityGuard guard = new McpSecurityGuard(toolAccessor, securityAccessor);

        McpToolResult result = guard.guard(new McpSecurityGuard.Invocation(
                tools, "knowledge_search", args, subject));

        assertThat(result.text()).contains("UNTRUSTED_TOOL_OUTPUT", "abcd", "truncated")
                .doesNotContain("abcdef");
        assertThat(result.isError()).isFalse();
    }
}
