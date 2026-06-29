package com.smart.rag.mcp.config;

import com.smart.rag.mcp.policy.McpToolPolicy;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.ai.mcp.McpConnectionInfo;
import org.springframework.ai.mcp.McpToolFilter;
import org.springframework.ai.mcp.McpToolNamePrefixGenerator;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * 静态 allowlist 过滤器——starter 装配边界（放 <b>config</b> 非 policy，B3：必然 import starter 类型）。
 * <p>
 * {@code implements McpToolFilter extends BiPredicate<McpConnectionInfo, McpSchema.Tool>}；starter autoconfig
 * 经 {@code ObjectProvider} 自动拾取本 bean 注入 {@code SyncMcpToolCallbackProvider}（写 bean 即生效，§7）。
 * <p>
 * <b>键反算（C1）</b>：{@code test} 看到的是<b>未前缀</b>的 {@code tool.name()}，但能拿 {@code conn} → 经注入的
 * {@link McpToolNamePrefixGenerator}（与命名 provider 同一 bean）反算前缀键 {@code prefixGen.prefixedToolName(conn, tool)}
 * → 与 yaml 键 / callback 名 1:1 → 查 {@link McpToolPolicy#explicitlyAllowed}（显式允许制 inclusion）。
 * <p>
 * <b>只读 allowlist 字段（C2）</b>：本 filter 无 Subject（签名只有 conn+tool），只能做<b>静态</b> inclusion 判定；
 * {@code roles}/risk/quota（Subject 相关）由内核 {@code McpAuthorizer} / Phase 2 guardrail 兜。
 */
@Component
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
