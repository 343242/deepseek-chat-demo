package com.smart.rag.mcp.policy;

import com.smart.rag.infrastructure.exception.ClientException;
import com.smart.rag.mcp.admin.entity.McpToolConfig;
import com.smart.rag.mcp.admin.service.McpToolConfigAccessor;
import com.smart.rag.mcp.core.McpIntent;
import com.smart.rag.mcp.core.Subject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class McpAuthorizerTest {

    private static final Subject AUTHENTICATED = new Subject(1L, null);
    private static final Subject ANONYMOUS = new Subject(0L, null);
    private static final String TOOL = "knowledge_search";

    @Mock
    private McpToolConfigAccessor accessor;

    private McpAuthorizer authorizer;

    @BeforeEach
    void setUp() {
        authorizer = new McpAuthorizer(accessor);
    }

    @Test
    void canSeeRequiresAuthenticatedSubjectAndKnownEnabledTool() {
        assertFalse(authorizer.canSee(ANONYMOUS, TOOL, McpIntent.RETRIEVAL));

        when(accessor.get(TOOL)).thenReturn(null);
        assertFalse(authorizer.canSee(AUTHENTICATED, TOOL, McpIntent.RETRIEVAL));

        when(accessor.get(TOOL)).thenReturn(config(false, "RETRIEVAL"));
        assertFalse(authorizer.canSee(AUTHENTICATED, TOOL, McpIntent.RETRIEVAL));

        when(accessor.get(TOOL)).thenReturn(config(true, "RETRIEVAL"));
        assertTrue(authorizer.canSee(AUTHENTICATED, TOOL, McpIntent.RETRIEVAL));
    }

    @Test
    void canSeeRequiresMatchingIntentAndDefaultsNullToGeneralTool() {
        when(accessor.get(TOOL)).thenReturn(config(true, "RETRIEVAL"));
        assertFalse(authorizer.canSee(AUTHENTICATED, TOOL, McpIntent.GENERAL_TOOL));

        when(accessor.get(TOOL)).thenReturn(config(true, null));
        assertTrue(authorizer.canSee(AUTHENTICATED, TOOL, null));
        assertTrue(authorizer.canSee(AUTHENTICATED, TOOL, McpIntent.GENERAL_TOOL));
        assertFalse(authorizer.canSee(AUTHENTICATED, TOOL, McpIntent.RETRIEVAL));
    }

    @Test
    void requireAuthorizedRechecksCurrentEnabledState() {
        when(accessor.get(TOOL)).thenReturn(config(true, "RETRIEVAL"));
        assertDoesNotThrow(() -> authorizer.requireAuthorized(AUTHENTICATED, TOOL));

        when(accessor.get(TOOL)).thenReturn(config(false, "RETRIEVAL"));
        assertThrows(ClientException.class,
                () -> authorizer.requireAuthorized(AUTHENTICATED, TOOL));

        when(accessor.get(TOOL)).thenReturn(null);
        assertThrows(ClientException.class,
                () -> authorizer.requireAuthorized(AUTHENTICATED, TOOL));
        assertThrows(ClientException.class,
                () -> authorizer.requireAuthorized(ANONYMOUS, TOOL));
    }

    private static McpToolConfig config(boolean enabled, String intent) {
        McpToolConfig config = new McpToolConfig();
        config.setPrefixedToolName(TOOL);
        config.setEnabled(enabled);
        config.setIntent(intent);
        return config;
    }
}
