package com.smart.rag.mcp.config;

import com.smart.rag.mcp.mcpclient.McpConnectionInfo;
import com.smart.rag.mcp.mcpclient.McpToolFilter;
import com.smart.rag.mcp.mcpclient.McpToolNamePrefixGenerator;
import com.smart.rag.mcp.policy.McpToolPolicy;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * 静态 allowlist 过滤器（v4：Phase 8 删除前临时关闭——避免与 {@link DatabaseToolFilter} 多候选冲突）。
 * <p>
 * 设 {@code app.mcp.legacy-allowlist-filter=true} 可临时恢复 yaml 驱动模式（仅 dev 调试用）。
 */
@Component
@ConditionalOnProperty(prefix = "app.mcp", name = "legacy-allowlist-filter", havingValue = "true")
public class AllowlistMcpToolFilter implements McpToolFilter {

    private final McpToolPolicy policy;
    private final McpToolNamePrefixGenerator prefixGen;

    public AllowlistMcpToolFilter(McpToolPolicy policy, McpToolNamePrefixGenerator prefixGen) {
        this.policy = Objects.requireNonNull(policy, "policy");
        this.prefixGen = Objects.requireNonNull(prefixGen, "prefixGen");
    }

    @Override
    public boolean test(McpConnectionInfo conn, McpSchema.Tool tool) {
        if (conn == null || tool == null || tool.name() == null) {
            return false;
        }
        String prefixed = prefixGen.prefixedToolName(conn, tool);
        return policy.explicitlyAllowed(prefixed);
    }
}
