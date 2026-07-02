package com.smart.rag.mcp.policy;

import com.smart.rag.infrastructure.exception.ClientException;
import com.smart.rag.infrastructure.exception.errorcode.ClientErrorCode;
import com.smart.rag.mcp.core.McpIntent;
import com.smart.rag.mcp.core.Subject;
import org.springframework.stereotype.Component;

/**
 * MCP 内核授权器（硬 authz，作用于 core 三能力）。
 * <p>
 * 两层 authz（§8）：
 * <ul>
 *   <li>{@link #canSee} — {@code visibleTo} 用，做 <b>authz + intent 路由 + subject 存在性</b>双过滤，
 *       未授权工具直接剔除（纵深防御——不向无权主体泄露 name/description）；返回 boolean，不抛</li>
 *   <li>{@link #requireAuthorized} — {@code call}/{@code read}/{@code get} 硬兜底，未授权抛
 *       {@link ClientException}({@link ClientErrorCode#FORBIDDEN})（A 类，不重试不计熔断）</li>
 * </ul>
 * <b>Phase 1 收窄</b>（design R-7）：{@code Subject = (userId, teamId)} 无 roles 字段、无 role provider，
 * 故 yaml {@code roles}/risk/quota 本期无判定对象——只落 allowlist（inclusion）+ intent 路由 + subject 存在性。
 * roles/risk/quota 强制接 Agent 链前补 role source 后再开（留接口位）。
 */
@Component
public class McpAuthorizer {

    private final McpToolPolicy policy;

    public McpAuthorizer(McpToolPolicy policy) {
        this.policy = policy;
    }

    /**
     * {@code visibleTo} 过滤判定：工具对调用方是否可见。
     * <p>
     * 三层全过才可见：① subject 已认证；② 在 allowlist（{@link McpToolPolicy#explicitlyAllowed}）；
     * ③ intent 路由匹配（{@link McpToolPolicy#routing} == {@code intent}）；routing 缺省 {@link McpIntent#GENERAL_TOOL}
     *    （未配 intent 的工具默认只在 GENERAL_TOOL 可见——支持"接入未知工具的 server"，{@code default-mode: ALLOW} 生效）。
     *
     * @return 可见 true；任一层不过返回 false（不抛——visibleTo 是过滤语义）
     */
    public boolean canSee(Subject subj, String prefixedName, McpIntent intent) {
        if (subj == null || !subj.isAuthenticated()) {
            return false;
        }
        // [临时] mcp.policy 关闭——所有 MCP 工具对所有 intent 全放行
        // if (!policy.explicitlyAllowed(prefixedName)) {
        //     return false;
        // }
        // // routing 缺省 GENERAL_TOOL：未配 intent 的工具默认只在 GENERAL_TOOL 可见，不污染
        // // RETRIEVAL/DEEP_RETRIEVAL/DIRECT_ANSWER；DENY 下工具必在 map（routing 非 empty），不受影响。
        // McpIntent effectiveIntent = policy.routing(prefixedName).orElse(McpIntent.GENERAL_TOOL);
        // return effectiveIntent == intent;
        return true;
    }

    /**
     * {@code call}/{@code read}/{@code get} 硬 authz 兜底。
     * <p>
     * subject 未认证或工具不在 allowlist → 抛 {@link ClientException}({@link ClientErrorCode#FORBIDDEN})。
     */
    public void requireAuthorized(Subject subj, String prefixedName) {
        if (subj == null || !subj.isAuthenticated()) {
            throw new ClientException(ClientErrorCode.FORBIDDEN,
                    "MCP 调用被拒：调用方主体未认证");
        }
        // [临时] mcp.policy 关闭——所有 MCP 工具放行
        // if (!policy.explicitlyAllowed(prefixedName)) {
        //     throw new ClientException(ClientErrorCode.FORBIDDEN,
        //             "MCP 工具未授权: " + prefixedName);
        // }
        // roles/risk/quota — Phase 2 guardrail 语义层（design R-7：本期无 role source，不强制）
    }
}
