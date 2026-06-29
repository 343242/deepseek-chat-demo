package com.smart.rag.mcp.policy;

import com.smart.rag.mcp.core.McpIntent;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * MCP 工具级策略（纯规则数据，{@code mcp.policy}）。
 * <p>
 * 一条 {@link ToolRule} 带多字段，但<b>各层只读自己那部分</b>（C2 字段分工）：
 * <table>
 *   <tr><th>层</th><th>落点</th><th>读哪个字段</th><th>有 Subject?</th></tr>
 *   <tr><td>静态 allowlist</td><td>{@code AllowlistMcpToolFilter}（config，provider 内全局）</td><td>{@link #explicitlyAllowed}（inclusion）</td><td>否</td></tr>
 *   <tr><td>硬 authz</td><td>{@link McpAuthorizer}（内核 visibleTo/call/read/get）</td><td>{@link #routing} + subject 存在性</td><td>是</td></tr>
 *   <tr><td>语义层（Phase 2）</td><td>{@code GuardrailEnforcingToolCallAdvisor}</td><td>{@code risk} + {@code quota} + 敏感参数</td><td>是</td></tr>
 * </table>
 * {@code roles}/risk/quota 本期<b>无判定对象</b>（无 role provider，design R-7），仅留接口位，
 * 接 Agent 链前必须补 role source。
 * <p>
 * 键 = <b>前缀全名</b>（{@code knowledge_search}，{@code _} 分隔），与 {@code AllowlistMcpToolFilter}
 * 经 {@code prefixGen} 反算的键 1:1（C1）。
 */
@Component
@ConfigurationProperties(prefix = "mcp.policy")
public class McpToolPolicy {

    /** 工具规则表，键 = 前缀全名。 */
    private Map<String, ToolRule> tools = new HashMap<>();

    /** 默认放行模式；{@code DENY}（默认）= 显式允许制，{@code ALLOW} = 全放行（map 仅作覆盖）。 */
    private DefaultMode defaultMode = DefaultMode.DENY;

    public enum DefaultMode { ALLOW, DENY }

    /**
     * 显式允许判定（静态 allowlist inclusion）。
     * <p>
     * {@code DENY} 模式：仅 {@link #tools} 中列出的工具允许；{@code ALLOW} 模式：全部允许（map 提供路由/风险覆盖）。
     */
    public boolean explicitlyAllowed(String prefixedName) {
        if (prefixedName == null || prefixedName.isBlank()) {
            return false;
        }
        if (defaultMode == DefaultMode.ALLOW) {
            return true;
        }
        return tools.containsKey(prefixedName);
    }

    /** 工具的路由意图（{@link ToolRule#getIntent()}）；不在 map 或 intent 缺省返回 empty。 */
    public Optional<McpIntent> routing(String prefixedName) {
        if (prefixedName == null) {
            return Optional.empty();
        }
        ToolRule rule = tools.get(prefixedName);
        return rule == null ? Optional.empty() : Optional.ofNullable(rule.getIntent());
    }

    /** 工具所需 roles（Phase 2 用，本期无 source 不强制）。 */
    public List<String> roles(String prefixedName) {
        if (prefixedName == null) {
            return List.of();
        }
        ToolRule rule = tools.get(prefixedName);
        return rule == null || rule.getRoles() == null ? List.of() : rule.getRoles();
    }

    public Map<String, ToolRule> getTools() {
        return tools;
    }

    public void setTools(Map<String, ToolRule> tools) {
        this.tools = tools == null ? new HashMap<>() : tools;
    }

    public DefaultMode getDefaultMode() {
        return defaultMode;
    }

    public void setDefaultMode(DefaultMode defaultMode) {
        this.defaultMode = defaultMode == null ? DefaultMode.DENY : defaultMode;
    }

    /** 单条工具规则。intent 本期单值精确匹配（多 intent/超集路由留接线切片）。 */
    public static class ToolRule {

        /** 路由意图（visibleTo 按 request intent 精确匹配）。 */
        private McpIntent intent;

        /** 风险等级 {@code low}/{@code high}（Phase 2 guardrail 读）。 */
        private String risk;

        /** 所需 roles（Phase 2，本期无 source）。 */
        private List<String> roles;

        /** 调用配额（Phase 2 guardrail 读）。 */
        private Integer quota;

        public McpIntent getIntent() {
            return intent;
        }

        public void setIntent(McpIntent intent) {
            this.intent = intent;
        }

        public String getRisk() {
            return risk;
        }

        public void setRisk(String risk) {
            this.risk = risk;
        }

        public List<String> getRoles() {
            return roles;
        }

        public void setRoles(List<String> roles) {
            this.roles = roles;
        }

        public Integer getQuota() {
            return quota;
        }

        public void setQuota(Integer quota) {
            this.quota = quota;
        }
    }
}
