package com.smart.rag.mcp.admin.service;
import com.smart.rag.mcp.admin.dto.McpConnectionStatus;
import com.smart.rag.mcp.runtime.McpConnectionStateProjector;
import com.smart.rag.mcp.admin.dto.UpdateServerRequest;
import com.smart.rag.mcp.admin.dto.UpdateToolRequest;
import com.smart.rag.mcp.admin.entity.McpSecurityConfigView;
import com.smart.rag.mcp.admin.entity.McpServerConfig;
import com.smart.rag.mcp.admin.entity.McpToolConfig;
import org.springframework.stereotype.Service;

import java.util.List;

/** Stable Admin facade used by the REST controller. */
@Service
public class McpAdminService {

    private final McpServerAdminService serverService;
    private final McpToolAdminService toolService;
    private final McpSecurityAdminService securityService;
    private final McpConnectionStateProjector projector;

    public McpAdminService(McpServerAdminService serverService,
                           McpToolAdminService toolService,
                           McpSecurityAdminService securityService,
                           McpConnectionStateProjector projector) {
        this.serverService = serverService;
        this.toolService = toolService;
        this.securityService = securityService;
        this.projector = projector;
    }

    public List<McpServerConfig> listServers() {
        return serverService.listServers();
    }

    public McpServerConfig getServer(Long id) {
        return serverService.getServer(id);
    }

    public McpConnectionStatus serverStatus(McpServerConfig config) {
        return projector.project(config);
    }

    public McpServerConfig updateServer(Long id, UpdateServerRequest request) {
        return serverService.updateServer(id, request);
    }

    public McpServerConfig createServer(CreateServerRequest request, String idempotencyKey) {
        return serverService.createServer(request, idempotencyKey);
    }

    public void deleteServer(Long id) {
        serverService.deleteServer(id);
    }

    public McpServerConfig enableServer(String serverId) {
        return serverService.enableServer(serverId);
    }

    public McpServerConfig disableServer(String serverId) {
        return serverService.disableServer(serverId);
    }

    public McpServerConfig reconnectServer(String serverId) {
        return serverService.reconnectServer(serverId);
    }

    public McpServerConfig updateBearerToken(String serverId, String bearerToken) {
        return serverService.updateBearerToken(serverId, bearerToken);
    }

    public void refreshTools(String serverId) {
        serverService.refreshTools(serverId);
    }

    public List<McpToolConfig> listTools(String serverId) {
        return toolService.listTools(serverId);
    }

    public void enableTool(Long id) {
        toolService.enableTool(id);
    }

    public void disableTool(Long id) {
        toolService.disableTool(id);
    }

    public void batchEnableTools(List<Long> ids) {
        toolService.batchEnableTools(ids);
    }

    public void batchDisableTools(List<Long> ids) {
        toolService.batchDisableTools(ids);
    }

    public void updateTool(Long id, UpdateToolRequest request) {
        toolService.updateTool(id, request);
    }

    public McpSecurityConfigView getSecurityConfig() {
        return securityService.getSecurityConfig();
    }

    public void updateSecurityConfig(McpSecurityConfigView view) {
        securityService.updateSecurityConfig(view);
    }
}
