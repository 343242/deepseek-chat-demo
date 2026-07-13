package com.smart.rag.mcp.admin.service;

import com.smart.rag.infrastructure.audit.AdminAudit;
import com.smart.rag.infrastructure.exception.ClientException;
import com.smart.rag.infrastructure.exception.errorcode.ClientErrorCode;
import com.smart.rag.mcp.admin.dto.UpdateToolRequest;
import com.smart.rag.mcp.admin.entity.McpToolConfig;
import com.smart.rag.mcp.admin.mapper.McpToolConfigMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

/**
 * MCP tool admin service — direct DB reads, no cache (Phase E).
 * <p>
 * Tool list reads are direct mapper queries on indexed columns.
 * No Caffeine cache, no callbackProvider, no invalidation protocol.
 */
@Service
public class McpToolAdminService {

    private final McpToolConfigMapper toolConfigMapper;
    private final TransactionTemplate txTemplate;

    public McpToolAdminService(McpToolConfigMapper toolConfigMapper,
                               TransactionTemplate txTemplate,
                               McpToolConfigAccessor toolConfigAccessor,
                               McpSecurityConfigAccessor securityConfigAccessor) {
        this.toolConfigMapper = toolConfigMapper;
        this.txTemplate = txTemplate;
    }

    public List<McpToolConfig> listTools(String serverId) {
        return toolConfigMapper.selectByServerId(serverId);
    }

    @AdminAudit(resourceType = "mcp_tool", action = "enable", resourceIdExpr = "#toolConfigId")
    public void enableTool(Long toolConfigId) {
        setEnabled(toolConfigId, true);
    }

    @AdminAudit(resourceType = "mcp_tool", action = "disable", resourceIdExpr = "#toolConfigId")
    public void disableTool(Long toolConfigId) {
        setEnabled(toolConfigId, false);
    }

    @AdminAudit(resourceType = "mcp_tool", action = "update", resourceIdExpr = "#toolConfigId")
    public void updateTool(Long toolConfigId, UpdateToolRequest request) {
        McpToolConfig tool = requireTool(toolConfigId);
        verifyVersion(request.version(), tool.getVersion());
        if (request.enabled() != null) {
            tool.setEnabled(request.enabled());
        }
        if (request.intent() != null) {
            tool.setIntent(request.intent().trim());
        }
        if (request.risk() != null) {
            tool.setRisk(request.risk().trim());
        }
        if (request.descriptionOverride() != null) {
            tool.setDescriptionOverride(request.descriptionOverride().trim());
        }
        if (toolConfigMapper.updateById(tool) == 0) {
            throw optimisticConflict();
        }
    }

    @AdminAudit(resourceType = "mcp_tool", action = "batch_enable")
    public void batchEnableTools(List<Long> ids) {
        batchSetEnabled(ids, true);
    }

    @AdminAudit(resourceType = "mcp_tool", action = "batch_disable")
    public void batchDisableTools(List<Long> ids) {
        batchSetEnabled(ids, false);
    }

    private void batchSetEnabled(List<Long> ids, boolean enabled) {
        txTemplate.executeWithoutResult(status -> toolConfigMapper.batchUpdateEnabled(ids, enabled));
    }

    private void setEnabled(Long id, boolean enabled) {
        McpToolConfig tool = requireTool(id);
        tool.setEnabled(enabled);
        if (toolConfigMapper.updateById(tool) == 0) {
            throw optimisticConflict();
        }
    }

    private McpToolConfig requireTool(Long id) {
        McpToolConfig tool = toolConfigMapper.selectById(id);
        if (tool == null) {
            throw new ClientException(ClientErrorCode.BAD_REQUEST, "MCP 工具配置不存在");
        }
        return tool;
    }

    private static void verifyVersion(Long requested, Long current) {
        if (requested != null && !requested.equals(current)) {
            throw optimisticConflict();
        }
    }

    private static ClientException optimisticConflict() {
        return new ClientException(ClientErrorCode.OPTIMISTIC_LOCK_CONFLICT,
                "MCP 工具配置已被修改，请刷新后重试");
    }
}
