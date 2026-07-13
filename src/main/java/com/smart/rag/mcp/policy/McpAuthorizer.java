package com.smart.rag.mcp.policy;

import com.smart.rag.infrastructure.exception.ClientException;
import com.smart.rag.infrastructure.exception.errorcode.ClientErrorCode;
import com.smart.rag.mcp.admin.entity.McpToolConfig;
import com.smart.rag.mcp.admin.service.McpToolConfigAccessor;
import com.smart.rag.mcp.core.McpIntent;
import com.smart.rag.mcp.core.Subject;
import org.springframework.stereotype.Component;

/**
 * MCP 内核授权器（硬 authz，作用于 core 三能力）。
 * <p>
 * 授权边界为“已认证主体 + ADMIN 管理的全局工具 allowlist + intent”。
 * 当前产品没有 per-user MCP RBAC 数据模型，本类不虚构角色映射。
 */
@Component
public class McpAuthorizer {

    private final McpToolConfigAccessor toolConfigAccessor;

    public McpAuthorizer(McpToolConfigAccessor toolConfigAccessor) {
        this.toolConfigAccessor = toolConfigAccessor;
    }

    public boolean canSee(Subject subj, String prefixedName, McpIntent intent) {
        if (!isAuthenticated(subj) || prefixedName == null || prefixedName.isBlank()) {
            return false;
        }
        McpToolConfig config = toolConfigAccessor.get(prefixedName);
        if (config == null || !Boolean.TRUE.equals(config.getEnabled())) {
            return false;
        }
        return configuredIntent(config) == effectiveIntent(intent);
    }

    public McpToolConfig requireAuthorized(Subject subj, String prefixedName) {
        if (!isAuthenticated(subj) || prefixedName == null || prefixedName.isBlank()) {
            throw new ClientException(ClientErrorCode.FORBIDDEN,
                    "MCP 工具调用未获授权");
        }
        McpToolConfig config = toolConfigAccessor.get(prefixedName);
        if (config == null || !Boolean.TRUE.equals(config.getEnabled())
                || !Boolean.TRUE.equals(config.getPresent())) {
            throw new ClientException(ClientErrorCode.FORBIDDEN, "MCP 工具调用未获授权");
        }
        return config;
    }

    private static boolean isAuthenticated(Subject subject) {
        return subject != null && subject.isAuthenticated();
    }

    private static McpIntent configuredIntent(McpToolConfig config) {
        String value = config.getIntent();
        if (value == null || value.isBlank()) {
            return McpIntent.GENERAL_TOOL;
        }
        try {
            return McpIntent.valueOf(value);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static McpIntent effectiveIntent(McpIntent intent) {
        return intent == null ? McpIntent.GENERAL_TOOL : intent;
    }
}
