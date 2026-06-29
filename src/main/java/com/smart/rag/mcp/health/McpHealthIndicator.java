package com.smart.rag.mcp.health;

import com.smart.rag.mcp.core.McpServer;
import com.smart.rag.mcp.core.McpServerHealth;
import com.smart.rag.mcp.core.McpServerRegistry;
import org.springframework.boot.actuate.health.AbstractHealthIndicator;
import org.springframework.boot.actuate.health.Health;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * MCP 子系统健康指标——聚合所有 {@link McpServer} 的 {@link McpServerHealth}（熔断器只读投影），
 * 供 actuator {@code /health} 消费（§11.4 health 出口）。
 * <p>
 * MCP 是<b>可选</b>出站第三方子系统：<b>永不</b>把应用标 DOWN（避免外部 MCP 故障触发编排重启，M4）——
 * 即使全部 server down，也只 UP + {@code allDown=true} detail，不污染应用 liveness；
 * per-server 状态（含 down）入 details。0 server（未配置连接）→ UP + {@code servers: 0}。
 */
@Component
public class McpHealthIndicator extends AbstractHealthIndicator {

    private final McpServerRegistry registry;

    public McpHealthIndicator(McpServerRegistry registry) {
        this.registry = registry;
    }

    @Override
    protected void doHealthCheck(Health.Builder builder) {
        List<McpServer> servers = registry.list();
        if (servers.isEmpty()) {
            builder.up().withDetail("servers", 0);
            return;
        }
        boolean allDown = true;
        for (McpServer server : servers) {
            McpServerHealth h = server.health();
            String detail = h.detail() != null
                    ? h.status().name() + ": " + h.detail()
                    : h.status().name();
            builder.withDetail(server.id().value(), detail);
            if (h.status() != McpServerHealth.Status.DOWN) {
                allDown = false;
            }
        }
        builder.withDetail("serverCount", servers.size());
        // MCP 可选：永不标应用 DOWN（M4）——全部 down 仅入 detail，不击穿 liveness；
        // 需按 MCP 健康做流量调度时，消费侧读 per-server details 或另起 health group。
        if (allDown) {
            builder.up().withDetail("allDown", true)
                    .withDetail("reason", "all configured MCP servers are down (app stays UP: MCP is optional)");
        } else {
            builder.up();
        }
    }
}
