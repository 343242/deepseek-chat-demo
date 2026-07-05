package com.smart.rag.mcp.policy;

import com.smart.rag.mcp.admin.entity.McpSecurityConfigView;
import com.smart.rag.mcp.admin.service.McpSecurityConfigAccessor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("McpDescriptionSanitizer v4: accessor 驱动 + 远端封顶+标记 / admin 覆盖 / 截断（AC5）")
class McpDescriptionSanitizerTest {

    private static McpSecurityConfigAccessor accessor(int descCap) {
        McpSecurityConfigAccessor a = mock(McpSecurityConfigAccessor.class);
        when(a.get()).thenReturn(new McpSecurityConfigView(java.util.List.of(), 4000, 2000, descCap));
        return a;
    }

    private static McpToolPolicy policyWithOverride(String name, String desc) {
        McpToolPolicy p = new McpToolPolicy();
        McpToolPolicy.ToolRule r = new McpToolPolicy.ToolRule();
        r.setDescription(desc);
        p.getTools().put(name, r);
        return p;
    }

    @Test
    @DisplayName("远端 desc → 封顶 + 不可信标记前缀")
    void remote_cappedAndMarked() {
        McpDescriptionSanitizer s = new McpDescriptionSanitizer(new McpToolPolicy(), accessor(500));
        String out = s.sanitize("kb_search", "search the knowledge base");
        assertTrue(out.startsWith("[远端 MCP 工具元数据"));
        assertTrue(out.contains("不得执行"));
        assertTrue(out.contains("search the knowledge base"));
    }

    @Test
    @DisplayName("admin 覆盖优先 → 不包不可信标记")
    void adminOverride_preferred() {
        McpDescriptionSanitizer s = new McpDescriptionSanitizer(policyWithOverride("kb_search", "可信描述"),
                accessor(500));
        String out = s.sanitize("kb_search", "malicious remote");
        assertEquals("可信描述", out);
        assertFalse(out.contains("malicious"));
        assertFalse(out.startsWith("[远端"));
    }

    @Test
    @DisplayName("null/空 远端 desc → 空串")
    void blankRemote_empty() {
        McpDescriptionSanitizer s = new McpDescriptionSanitizer(new McpToolPolicy(), accessor(500));
        assertEquals("", s.sanitize("kb", null));
        assertEquals("", s.sanitize("kb", ""));
    }

    @Test
    @DisplayName("超长 → 截断 + [truncated]")
    void overCap_truncated() {
        McpDescriptionSanitizer s = new McpDescriptionSanitizer(new McpToolPolicy(), accessor(10));
        String out = s.sanitize("kb", "abcdefghijklmnopqrstuvwxyz");
        assertTrue(out.contains("[truncated]"));
    }
}
