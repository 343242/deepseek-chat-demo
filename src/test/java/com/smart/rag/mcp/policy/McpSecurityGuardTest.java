package com.smart.rag.mcp.policy;

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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("McpSecurityGuard: 敏感参数 DENY + risk 封顶 + 输出不可信标记（AC1/AC2/AC3/AC4/AC8）")
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

    private static McpSecurityProperties propsWithSensitive(int defaultCap, int highCap, String... patterns) {
        McpSecurityProperties p = new McpSecurityProperties();
        p.setSensitiveArgPatterns(List.of(patterns));
        p.setDefaultOutputCapChars(defaultCap);
        p.setHighRiskOutputCapChars(highCap);
        return p;
    }

    @Test
    @DisplayName("AC2：敏感参数命中 → DENY error，不发包远端（tools.call 未调）")
    void sensitiveArgDenied_notSent() {
        McpSecurityGuard guard = new McpSecurityGuard(policy("kb_search", "low"),
                propsWithSensitive(50, 10, "(?i)sk-[a-z0-9]+"));

        McpToolResult r = guard.guard(tools, "kb_search", McpArgs.of(Map.of("q", "sk-secret123")), subj);

        assertTrue(r.isError());
        assertTrue(r.text().contains("blocked"));
        verifyNoInteractions(tools);
    }

    @Test
    @DisplayName("AC3/AC8：未命中 → 透传 tools.call + 输出包不可信标记框（含 不得执行 字样）")
    void allow_passThroughAndWrap() {
        McpSecurityGuard guard = new McpSecurityGuard(policy("kb_search", "low"), new McpSecurityProperties());
        when(tools.call(eq("kb_search"), any(), eq(subj))).thenReturn(McpToolResult.success("hello"));

        McpToolResult r = guard.guard(tools, "kb_search", McpArgs.of(Map.of("q", "hi")), subj);

        assertFalse(r.isError());
        assertTrue(r.text().contains("UNTRUSTED_TOOL_OUTPUT"));
        assertTrue(r.text().contains("hello"));
        assertTrue(r.text().contains("不得执行"));
        verify(tools).call(eq("kb_search"), any(), eq(subj));
    }

    @Test
    @DisplayName("AC4：risk high 截断阈值 < low（low=50 不截，high=10 截断）")
    void riskHigh_capsTighter() {
        McpSecurityGuard lowGuard = new McpSecurityGuard(policy("kb", "low"),
                propsWithSensitive(50, 10));
        McpSecurityGuard highGuard = new McpSecurityGuard(policy("ops", "high"),
                propsWithSensitive(50, 10));
        String longText = "x".repeat(30); // > highCap(10), < defaultCap(50)
        when(tools.call(any(), any(), any())).thenReturn(McpToolResult.success(longText));

        String lowOut = lowGuard.guard(tools, "kb", McpArgs.empty(), subj).text();
        String highOut = highGuard.guard(tools, "ops", McpArgs.empty(), subj).text();

        assertTrue(lowOut.contains("x".repeat(30)), "low(50) 不截断 30 字符");
        assertTrue(highOut.contains("[truncated]"), "high(10) 截断 30 字符");
        assertFalse(highOut.contains("x".repeat(30)));
    }

    @Test
    @DisplayName("AC8：空 sensitiveArgPatterns → 不筛查（默认零行为）")
    void emptyPatterns_noScreening() {
        McpSecurityGuard guard = new McpSecurityGuard(policy("kb", "low"), new McpSecurityProperties());
        when(tools.call(any(), any(), any())).thenReturn(McpToolResult.success("ok"));

        McpToolResult r = guard.guard(tools, "kb", McpArgs.of(Map.of("q", "sk-secret")), subj);

        assertFalse(r.isError(), "默认空 patterns → 不拦截");
    }

    @Test
    @DisplayName("AC8：null args → sensitiveArgHit 短路、不抛")
    void nullArgs_safe() {
        McpSecurityGuard guard = new McpSecurityGuard(policy("kb", "low"),
                propsWithSensitive(50, 10, ".*"));
        when(tools.call(any(), any(), any())).thenReturn(McpToolResult.success("ok"));

        McpToolResult r = guard.guard(tools, "kb", null, subj);

        assertFalse(r.isError());
    }
}
