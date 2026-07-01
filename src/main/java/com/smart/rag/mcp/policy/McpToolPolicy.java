package com.smart.rag.mcp.policy;

import com.smart.rag.mcp.core.McpIntent;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
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
 *   <tr><td>语义层（Phase 2）</td><td>{@link McpSecurityGuard}（执行时，adapter BiFunction）+ {@link McpDescriptionSanitizer}</td><td>{@link #risk} + 敏感参数 + {@link #descriptionOverride}</td><td>是</td></tr>
 * </table>
 * <p>
 * <b>risk 仅 admin-yaml 声明，不运行时推断</b>：工具自带元数据（name/desc/schema/MCP annotations）皆远端
 * 提供、攻击者可控，不能作安全分类依据。缺省 {@code low}。
 * <p>
 * <b>roles 已移除</b>：项目无对应"MCP 工具调用权"的干净 role source（app RBAC 是 app-resource 域、范畴错配）。
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

    /**
     * 工具风险等级（admin-yaml 声明，{@code low}/{@code high}，缺省 {@code low}）。
     * <p>
     * 驱动 {@link McpSecurityGuard} 输出封顶松紧 + 审计级别。<b>不</b>运行时推断（工具元数据不可信）。
     */
    public String risk(String prefixedName) {
        if (prefixedName == null) {
            return "low";
        }
        ToolRule rule = tools.get(prefixedName);
        if (rule == null || rule.getRisk() == null || rule.getRisk().isBlank()) {
            return "low";
        }
        return rule.getRisk();
    }

    /**
     * admin 可信描述覆盖（yaml {@code mcp.policy.tools.<name>.description}）。
     * <p>
     * 非空时 {@link McpDescriptionSanitizer} 用它替代远端不可信 description（仅长度封顶，不包不可信标记）。
     */
    public String descriptionOverride(String prefixedName) {
        if (prefixedName == null) {
            return null;
        }
        ToolRule rule = tools.get(prefixedName);
        return rule == null ? null : rule.getDescription();
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

    /** 单条工具规则。intent 单值精确匹配；risk admin 声明；description 可信覆盖。 */
    public static class ToolRule {

        /** 路由意图（visibleTo 按 request intent 精确匹配）。 */
        private McpIntent intent;

        /** 风险等级 {@code low}/{@code high}（admin 声明，缺省 low）。 */
        private String risk;

        /** admin 可信描述覆盖（替代远端不可信 description）。 */
        private String description;

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

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }
    }
}
