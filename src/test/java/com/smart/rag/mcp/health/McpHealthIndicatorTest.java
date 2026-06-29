package com.smart.rag.mcp.health;

import com.smart.rag.mcp.core.McpServer;
import com.smart.rag.mcp.core.McpServerHealth;
import com.smart.rag.mcp.core.McpServerRegistry;
import com.smart.rag.mcp.core.ServerId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("McpHealthIndicator: 可选子系统不击穿 liveness（M4）")
class McpHealthIndicatorTest {

    @Mock private McpServerRegistry registry;
    @Mock private McpServer serverA;
    @Mock private McpServer serverB;

    private McpHealthIndicator indicator;

    @BeforeEach
    void setUp() {
        indicator = new McpHealthIndicator(registry);
    }

    @Test
    @DisplayName("无 server → UP + servers:0")
    void emptyServers_up() {
        when(registry.list()).thenReturn(List.of());

        Health h = indicator.health();

        assertEquals(Status.UP, h.getStatus());
        assertEquals(0, h.getDetails().get("servers"));
    }

    @Test
    @DisplayName("部分 down → UP，per-server 状态入 details，不打 allDown")
    void mixedServers_up() {
        when(serverA.id()).thenReturn(new ServerId("knowledge"));
        when(serverA.health()).thenReturn(McpServerHealth.alive());
        when(serverB.id()).thenReturn(new ServerId("ops"));
        when(serverB.health()).thenReturn(McpServerHealth.down("熔断打开"));
        when(registry.list()).thenReturn(List.of(serverA, serverB));

        Health h = indicator.health();

        assertEquals(Status.UP, h.getStatus());
        assertEquals(2, h.getDetails().get("serverCount"));
        assertNotNull(h.getDetails().get("knowledge"));
        assertNotNull(h.getDetails().get("ops"));
        assertFalse(h.getDetails().containsKey("allDown"), "非全 down 不应标 allDown");
    }

    @Test
    @DisplayName("全部 down → 仍 UP（可选子系统不击穿应用 liveness，M4）+ allDown=true")
    void allDown_stillUp_notBreakLiveness() {
        when(serverA.id()).thenReturn(new ServerId("knowledge"));
        when(serverA.health()).thenReturn(McpServerHealth.down("initialize 失败"));
        when(serverB.id()).thenReturn(new ServerId("ops"));
        when(serverB.health()).thenReturn(McpServerHealth.down("熔断打开"));
        when(registry.list()).thenReturn(List.of(serverA, serverB));

        Health h = indicator.health();

        assertEquals(Status.UP, h.getStatus(),
                "全部 MCP server down 不应把应用标 DOWN（避免外部故障触发编排重启，M4）");
        assertEquals(Boolean.TRUE, h.getDetails().get("allDown"));
        assertEquals(2, h.getDetails().get("serverCount"));
    }
}
