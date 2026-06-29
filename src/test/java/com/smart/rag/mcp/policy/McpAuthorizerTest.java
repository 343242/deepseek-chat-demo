package com.smart.rag.mcp.policy;

import com.smart.rag.infrastructure.exception.ClientException;
import com.smart.rag.mcp.core.McpIntent;
import com.smart.rag.mcp.core.Subject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("McpAuthorizer: visibleTo 双过滤 + call 硬兜底（AC3/AC4）")
class McpAuthorizerTest {

    private static McpAuthorizer authorizerWith(String name, McpIntent intent) {
        McpToolPolicy p = new McpToolPolicy();
        McpToolPolicy.ToolRule r = new McpToolPolicy.ToolRule();
        r.setIntent(intent);
        p.getTools().put(name, r);
        return new McpAuthorizer(p);
    }

    private static final Subject AUTHED = new Subject(1L, 1L);
    private static final Subject ANON = new Subject(0L, null);

    @Test
    @DisplayName("canSee：未认证主体 → 不可见")
    void canSee_unauthenticated_false() {
        assertFalse(authorizerWith("t", McpIntent.RETRIEVAL).canSee(ANON, "t", McpIntent.RETRIEVAL));
    }

    @Test
    @DisplayName("canSee：未在 allowlist → 不可见（AC4 默认拒绝）")
    void canSee_unlisted_false() {
        McpAuthorizer a = new McpAuthorizer(new McpToolPolicy());
        assertFalse(a.canSee(AUTHED, "t", McpIntent.RETRIEVAL));
    }

    @Test
    @DisplayName("canSee：intent 不匹配 → 不可见")
    void canSee_intentMismatch_false() {
        assertFalse(authorizerWith("t", McpIntent.RETRIEVAL).canSee(AUTHED, "t", McpIntent.GENERAL_TOOL));
    }

    @Test
    @DisplayName("canSee：subject + allowlist + intent 全过 → 可见")
    void canSee_allMatch_true() {
        assertTrue(authorizerWith("t", McpIntent.RETRIEVAL).canSee(AUTHED, "t", McpIntent.RETRIEVAL));
    }

    @Test
    @DisplayName("requireAuthorized：未认证 → 抛 ClientException（AC3）")
    void requireAuthorized_unauthenticated_throws() {
        assertThrows(ClientException.class,
                () -> authorizerWith("t", McpIntent.RETRIEVAL).requireAuthorized(ANON, "t"));
    }

    @Test
    @DisplayName("requireAuthorized：未在 allowlist → 抛 ClientException（AC3/AC4）")
    void requireAuthorized_unlisted_throws() {
        McpAuthorizer a = new McpAuthorizer(new McpToolPolicy());
        assertThrows(ClientException.class, () -> a.requireAuthorized(AUTHED, "t"));
    }

    @Test
    @DisplayName("requireAuthorized：全过 → 不抛")
    void requireAuthorized_ok() {
        assertDoesNotThrow(() -> authorizerWith("t", McpIntent.RETRIEVAL).requireAuthorized(AUTHED, "t"));
    }
}
