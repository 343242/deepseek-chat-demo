package com.smart.rag.mcp.config;

import com.smart.rag.mcp.policy.McpToolPolicy;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.mcp.McpConnectionInfo;
import org.springframework.ai.mcp.McpToolNamePrefixGenerator;
import org.springframework.ai.mcp.McpToolUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("AllowlistMcpToolFilter: 前缀键反算 + 显式允许制（C1/AC4）")
class AllowlistMcpToolFilterTest {

    @Mock
    private McpConnectionInfo conn;

    @Mock
    private McpSchema.Tool tool;

    /** 复刻生产 prefixGen 逻辑（同源 McpToolUtils.format），保证反算键与 callback 名 1:1。 */
    private static McpToolNamePrefixGenerator prefixGen() {
        return (c, t) -> "srv_" + McpToolUtils.format(t.name());
    }

    @Test
    @DisplayName("policy 显式允许反算键 → 通过")
    void allows_whenPolicyExplicitlyAllows_prefixedKey() {
        when(tool.name()).thenReturn("search");
        McpToolPolicy policy = new McpToolPolicy();
        policy.getTools().put("srv_search", new McpToolPolicy.ToolRule());
        AllowlistMcpToolFilter filter = new AllowlistMcpToolFilter(policy, prefixGen());
        assertTrue(filter.test(conn, tool));
    }

    @Test
    @DisplayName("未在 allowlist → 拒绝（默认拒绝）")
    void denies_whenNotInAllowlist() {
        when(tool.name()).thenReturn("secret");
        AllowlistMcpToolFilter filter = new AllowlistMcpToolFilter(new McpToolPolicy(), prefixGen());
        assertFalse(filter.test(conn, tool));
    }

    @Test
    @DisplayName("null 入参 → 拒绝（防御）")
    void denies_nullInputs() {
        AllowlistMcpToolFilter filter = new AllowlistMcpToolFilter(new McpToolPolicy(), prefixGen());
        assertFalse(filter.test(null, tool));
        assertFalse(filter.test(conn, null));
    }
}
