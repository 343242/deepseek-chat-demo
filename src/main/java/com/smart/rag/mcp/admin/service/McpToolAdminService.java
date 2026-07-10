package com.smart.rag.mcp.admin.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.smart.rag.infrastructure.audit.AdminAudit;
import com.smart.rag.infrastructure.exception.ClientException;
import com.smart.rag.infrastructure.exception.RemoteException;
import com.smart.rag.infrastructure.exception.errorcode.ClientErrorCode;
import com.smart.rag.infrastructure.exception.errorcode.RemoteErrorCode;
import com.smart.rag.mcp.admin.dto.UpdateToolRequest;
import com.smart.rag.mcp.admin.entity.McpToolConfig;
import com.smart.rag.mcp.admin.mapper.McpToolConfigMapper;
import com.smart.rag.mcp.core.McpServer;
import com.smart.rag.mcp.core.McpServerRegistry;
import com.smart.rag.mcp.core.ServerId;
import com.smart.rag.mcp.mcpclient.McpToolUtils;
import com.smart.rag.mcp.mcpclient.SyncMcpToolCallbackProvider;
import com.smart.rag.mcp.runtime.McpServerImpl;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.List;

@Service
public class McpToolAdminService {

    private final McpToolConfigMapper toolConfigMapper;
    private final TransactionTemplate txTemplate;
    private final McpServerRegistry registry;
    private final SyncMcpToolCallbackProvider callbackProvider;
    private final McpToolConfigAccessor toolConfigAccessor;
    private final McpSecurityConfigAccessor securityConfigAccessor;
    private final Cache<String, List<McpToolConfig>> toolListCache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(10)).maximumSize(100).build();

    public McpToolAdminService(McpToolConfigMapper toolConfigMapper,
                               TransactionTemplate txTemplate,
                               McpServerRegistry registry,
                               SyncMcpToolCallbackProvider callbackProvider,
                               McpToolConfigAccessor toolConfigAccessor,
                               McpSecurityConfigAccessor securityConfigAccessor) {
        this.toolConfigMapper = toolConfigMapper;
        this.txTemplate = txTemplate;
        this.registry = registry;
        this.callbackProvider = callbackProvider;
        this.toolConfigAccessor = toolConfigAccessor;
        this.securityConfigAccessor = securityConfigAccessor;
    }

    @AdminAudit(resourceType = "mcp_tool", action = "refresh_tools", resourceIdExpr = "#serverId")
    public void refreshTools(String serverId) {
        McpServer server = registry.find(new ServerId(serverId))
                .orElseThrow(() -> new ClientException(ClientErrorCode.BAD_REQUEST, "MCP Server 不存在"));
        if (!(server instanceof McpServerImpl implementation) || implementation.initError() != null) {
            throw new RemoteException(RemoteErrorCode.MCP_SERVER_UNREACHABLE,
                    "MCP Server 当前不可用，无法刷新工具");
        }
        List<McpSchema.Tool> tools = implementation.listToolsFromRemote();
        int descriptionLimit = securityConfigAccessor.get().toolDescCharLimit();
        List<McpToolConfig> rows = tools.stream()
                .map(tool -> toConfig(serverId, tool, descriptionLimit))
                .toList();
        if (!rows.isEmpty()) {
            txTemplate.executeWithoutResult(status -> toolConfigMapper.batchUpsert(rows));
        }
        invalidate(serverId);
    }

    public List<McpToolConfig> listTools(String serverId) {
        return toolListCache.get(serverId, toolConfigMapper::selectByServerId);
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
        update(tool);
    }

    @AdminAudit(resourceType = "mcp_tool", action = "batch_enable")
    public void batchEnableTools(List<Long> ids) {
        batchSetEnabled(ids, true);
    }

    @AdminAudit(resourceType = "mcp_tool", action = "batch_disable")
    public void batchDisableTools(List<Long> ids) {
        batchSetEnabled(ids, false);
    }

    public void invalidate(String serverId) {
        toolListCache.invalidate(serverId);
        toolConfigAccessor.invalidateAll();
        callbackProvider.invalidateCache();
    }

    private void batchSetEnabled(List<Long> ids, boolean enabled) {
        txTemplate.executeWithoutResult(status -> toolConfigMapper.batchUpdateEnabled(ids, enabled));
        toolListCache.invalidateAll();
        toolConfigAccessor.invalidateAll();
        callbackProvider.invalidateCache();
    }

    private void setEnabled(Long id, boolean enabled) {
        McpToolConfig tool = requireTool(id);
        tool.setEnabled(enabled);
        update(tool);
    }

    private void update(McpToolConfig tool) {
        if (toolConfigMapper.updateById(tool) == 0) {
            throw optimisticConflict();
        }
        toolConfigAccessor.invalidate(tool.getPrefixedToolName());
        toolListCache.invalidate(tool.getServerId());
        callbackProvider.invalidateCache();
    }

    private McpToolConfig requireTool(Long id) {
        McpToolConfig tool = toolConfigMapper.selectById(id);
        if (tool == null) {
            throw new ClientException(ClientErrorCode.BAD_REQUEST, "MCP 工具配置不存在");
        }
        return tool;
    }

    private static McpToolConfig toConfig(String serverId, McpSchema.Tool tool, int descriptionLimit) {
        McpToolConfig row = new McpToolConfig();
        row.setServerId(serverId);
        row.setToolName(tool.name());
        row.setPrefixedToolName(McpToolUtils.prefixedToolName(serverId, tool.name()));
        row.setDescription(cap(tool.description(), descriptionLimit));
        row.setEnabled(false);
        row.setIntent("GENERAL_TOOL");
        row.setRisk("low");
        return row;
    }

    private static String cap(String value, int limit) {
        if (value == null || value.length() <= limit) {
            return value;
        }
        return value.substring(0, limit);
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
