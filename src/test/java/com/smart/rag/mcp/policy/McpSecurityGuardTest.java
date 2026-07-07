package com.smart.rag.mcp.policy;

import com.smart.rag.mcp.admin.entity.McpSecurityConfigView;
import com.smart.rag.mcp.admin.service.McpSecurityConfigAccessor;
import com.smart.rag.mcp.core.McpArgs;
import com.smart.rag.mcp.core.McpToolResult;
import com.smart.rag.mcp.core.McpTools;
import com.smart.rag.mcp.core.Subject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("McpSecurityGuard v4: accessor 驱动 + 敏感参数 DENY + risk 封顶（AC1/AC2/AC3/AC4/AC8）")
class McpSecurityGuardTest {

    private final Subject subj = new Subject(1L, 1L);

    @Mock
    private McpTools tools;

    private static McpToolPolicy policy(String name, String risk) {
        McpToolPolicy p = new McpToolPolicy();
        McpToolPolicy.ToolRule r = new McpToolPolicy.ToolRule();
        r.setRisk(risk);
        p.getTools().put(name, r);
        return p;
    }

    private static McpSecurityConfigAccessor accessorWithPatterns(String... patterns) {
        McpSecurityConfigAccessor a = mock(McpSecurityConfigAccessor.class);
        List<Pattern> compiled = java.util.stream.Stream.of(patterns).map(Pattern::compile).toList();
        lenient().when(a.patterns()).thenReturn(compiled);
        lenient().when(a.get()).thenReturn(McpSecurityConfigView.defaults());
        return a;
    }

    private static McpSecurityConfigAccessor accessorWithCaps(int defaultCap, int highCap) {
        McpSecurityConfigAccessor a = mock(McpSecurityConfigAccessor.class);
        lenient().when(a.patterns()).thenReturn(List.of());
        lenient().when(a.get()).thenReturn(new McpSecurityConfigView(List.of(), defaultCap, highCap, 500));
        return a;
    }

    @Test
    @DisplayName("AC2：敏感参数命中 → DENY error，不发包远端（tools.call 未调）")
    void sensitiveArgDenied_notSent() {
        McpSecurityGuard guard = new McpSecurityGuard(policy("kb_search", "low"),
                accessorWithPatterns("(?i)sk-[a-z0-9]+"));

        McpToolResult r = guard.guard(tools, "kb_search", McpArgs.of(Map.of("q", "sk-secret123")), subj);

        assertTrue(r.isError());
        assertTrue(r.text().contains("blocked"));
        verifyNoInteractions(tools);
    }

    @Test
    @DisplayName("AC3/AC8：未命中 → 透传 tools.call + 输出包不可信标记框")
    void allow_passThroughAndWrap() {
        McpSecurityGuard guard = new McpSecurityGuard(policy("kb_search", "low"), accessorWithCaps(50, 10));
        when(tools.call(eq("kb_search"), any(), eq(subj))).thenReturn(McpToolResult.success("hello"));

        McpToolResult r = guard.guard(tools, "kb_search", McpArgs.of(Map.of("q", "hi")), subj);

        assertFalse(r.isError());
        assertTrue(r.text().contains("UNTRUSTED_TOOL_OUTPUT"));
        assertTrue(r.text().contains("hello"));
        verify(tools).call(eq("kb_search"), any(), eq(subj));
    }

    @Test
    @DisplayName("AC4：risk high 截断阈值 < low")
    void riskHigh_capsTighter() {
        McpSecurityGuard lowGuard = new McpSecurityGuard(policy("kb", "low"), accessorWithCaps(50, 10));
        McpSecurityGuard highGuard = new McpSecurityGuard(policy("ops", "high"), accessorWithCaps(50, 10));
        String longText = "x".repeat(30);
        when(tools.call(any(), any(), any())).thenReturn(McpToolResult.success(longText));

        String lowOut = lowGuard.guard(tools, "kb", McpArgs.empty(), subj).text();
        String highOut = highGuard.guard(tools, "ops", McpArgs.empty(), subj).text();

        assertTrue(lowOut.contains("x".repeat(30)), "low(50) 不截断 30 字符");
        assertTrue(highOut.contains("[truncated]"), "high(10) 截断 30 字符");
    }

    @Test
    @DisplayName("AC8：空 patterns → 不筛查")
    void emptyPatterns_noScreening() {
        McpSecurityGuard guard = new McpSecurityGuard(policy("kb", "low"), accessorWithCaps(50, 10));
        when(tools.call(any(), any(), any())).thenReturn(McpToolResult.success("ok"));

        McpToolResult r = guard.guard(tools, "kb", McpArgs.of(Map.of("q", "sk-secret")), subj);

        assertFalse(r.isError());
    }

    @Test
    @DisplayName("AC8：null args → sensitiveArgHit 短路、不抛")
    void nullArgs_safe() {
        McpSecurityGuard guard = new McpSecurityGuard(policy("kb", "low"),
                accessorWithPatterns(".*"));
        when(tools.call(any(), any(), any())).thenReturn(McpToolResult.success("ok"));

        McpToolResult r = guard.guard(tools, "kb", null, subj);

        assertFalse(r.isError());
    }
}
