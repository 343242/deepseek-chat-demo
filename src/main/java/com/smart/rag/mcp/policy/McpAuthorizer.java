package com.smart.rag.mcp.policy;

import com.smart.rag.infrastructure.exception.ClientException;
import com.smart.rag.infrastructure.exception.errorcode.ClientErrorCode;
import com.smart.rag.mcp.core.McpIntent;
import com.smart.rag.mcp.core.Subject;
import org.springframework.stereotype.Component;

/**
 * MCP 内核授权器（硬 authz，作用于 core 三能力）。
 * <p>
 * <b>v4 C1 改造</b>：移除 {@code McpToolPolicy} 依赖（已删除）。authz 逻辑当前注释状态
 * （等 role source 接入后激活）。本类保留为占位。
 */
@Component
public class McpAuthorizer {

    public McpAuthorizer() {
    }

    public boolean canSee(Subject subj, String prefixedName, McpIntent intent) {
        if (subj == null || !subj.isAuthenticated()) {
            return false;
        }
        return true;
    }

    public void requireAuthorized(Subject subj, String prefixedName) {
        if (subj == null || !subj.isAuthenticated()) {
            throw new ClientException(ClientErrorCode.FORBIDDEN,
                    "MCP 调用被拒：调用方主体未认证");
        }
    }
}
