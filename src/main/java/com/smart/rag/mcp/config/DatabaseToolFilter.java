package com.smart.rag.mcp.config;

import com.smart.rag.mcp.admin.service.McpAdminService;
import com.smart.rag.mcp.mcpclient.McpConnectionInfo;
import com.smart.rag.mcp.mcpclient.McpToolFilter;
import com.smart.rag.mcp.mcpclient.McpToolNamePrefixGenerator;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * DB 驱动的工具过滤器（v4 C2：合并 strict/lenient 为单 Bean）。
 * <p>
 * <b>三态决策</b>：
 * <ul>
 *   <li>{@code isToolEnabled(name) == Boolean.TRUE} → 入库且启用，通过</li>
 *   <li>{@code isToolEnabled(name) == Boolean.FALSE} → 入库但禁用，拒绝</li>
 *   <li>{@code isToolEnabled(name) == null} → 未入库：
 *     <ul>
 *       <li>{@code strictMode=true}（默认）→ <b>deny</b>（v4 修复 1.4：避免远端新增危险工具自动放行）</li>
 *       <li>{@code strictMode=false}（dev 调试，{@code app.mcp.strict-tool-filter=false}）→ allow</li>
 *     </ul>
 *   </li>
 * </ul>
 */
@Component
public class DatabaseToolFilter implements McpToolFilter {

    private final McpAdminService adminService;
    private final McpToolNamePrefixGenerator prefixGen;
    private final boolean strictMode;

    public DatabaseToolFilter(McpAdminService adminService,
                              McpToolNamePrefixGenerator prefixGen,
                              @Value("${app.mcp.strict-tool-filter:true}") boolean strictMode) {
        this.adminService = adminService;
        this.prefixGen = prefixGen;
        this.strictMode = strictMode;
    }

    @Override
    public boolean test(McpConnectionInfo conn, McpSchema.Tool tool) {
        if (conn == null || tool == null || tool.name() == null) {
            return false;
        }
        String prefixed = prefixGen.prefixedToolName(conn, tool);
        Boolean enabled = adminService.isToolEnabled(prefixed);
        if (enabled != null) {
            return enabled;
        }
        return !strictMode;
    }
}
