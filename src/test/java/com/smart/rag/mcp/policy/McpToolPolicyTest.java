package com.smart.rag.mcp.policy;

import com.smart.rag.mcp.core.McpIntent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("McpToolPolicy: 默认拒绝 / 显式允许 / 路由查询")
class McpToolPolicyTest {

    private static McpToolPolicy policyWith(String name, McpIntent intent) {
        McpToolPolicy p = new McpToolPolicy();
        McpToolPolicy.ToolRule r = new McpToolPolicy.ToolRule();
        r.setIntent(intent);
        p.getTools().put(name, r);
        return p;
    }

    @Test
    @DisplayName("默认 DENY：未列出工具一律不允许（fail-secure）")
    void defaultDeny_excludesUnlisted() {
        McpToolPolicy p = new McpToolPolicy();
        assertFalse(p.explicitlyAllowed("anything"));
        assertFalse(p.explicitlyAllowed(null));
        assertFalse(p.explicitlyAllowed(""));
    }

    @Test
    @DisplayName("默认 DENY：列出工具允许，其他不允许")
    void defaultDeny_includesListedOnly() {
        McpToolPolicy p = policyWith("knowledge_search", McpIntent.RETRIEVAL);
        assertTrue(p.explicitlyAllowed("knowledge_search"));
        assertFalse(p.explicitlyAllowed("other"));
    }

    @Test
    @DisplayName("ALLOW 模式：全部允许（map 仅作覆盖）")
    void allowMode_allAllowed() {
        McpToolPolicy p = new McpToolPolicy();
        p.setDefaultMode(McpToolPolicy.DefaultMode.ALLOW);
        assertTrue(p.explicitlyAllowed("anything"));
    }

    @Test
    @DisplayName("routing：列出工具返回 intent，未列出返回 empty")
    void routing() {
        McpToolPolicy p = policyWith("knowledge_search", McpIntent.RETRIEVAL);
        assertEquals(McpIntent.RETRIEVAL, p.routing("knowledge_search").orElseThrow());
        assertTrue(p.routing("unlisted").isEmpty());
    }

    @Test
    @DisplayName("risk：缺省 low；admin 声明 high（admin-yaml-only，不推断）")
    void risk() {
        McpToolPolicy p = new McpToolPolicy();
        assertEquals("low", p.risk("unlisted"), "缺省 low");
        assertEquals("low", p.risk(null));
        McpToolPolicy.ToolRule r = new McpToolPolicy.ToolRule();
        r.setRisk("high");
        p.getTools().put("ops_x", r);
        assertEquals("high", p.risk("ops_x"));
    }

    @Test
    @DisplayName("descriptionOverride：admin 可信覆盖；未设返回 null")
    void descriptionOverride() {
        McpToolPolicy p = new McpToolPolicy();
        assertNull(p.descriptionOverride("unlisted"));
        McpToolPolicy.ToolRule r = new McpToolPolicy.ToolRule();
        r.setDescription("可信描述");
        p.getTools().put("ops_x", r);
        assertEquals("可信描述", p.descriptionOverride("ops_x"));
    }
}
