package com.smart.rag.mcp.admin.service;

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

    public McpAdminService(McpServerAdminService serverService,
                           McpToolAdminService toolService,
                           McpSecurityAdminService securityService) {
        this.serverService = serverService;
        this.toolService = toolService;
        this.securityService = securityService;
    }

    public List<McpServerConfig> listServers() {
        return serverService.listServers();
    }

    public McpServerConfig getServer(Long id) {
        return serverService.getServer(id);
    }

    public String serverHealth(String serverId) {
        return serverService.serverHealth(serverId);
    }

    public void updateServer(Long id, UpdateServerRequest request) {
        serverService.updateServer(id, request);
    }

    public McpServerConfig createServer(CreateServerRequest request, String idempotencyKey) {
        return serverService.createServer(request, idempotencyKey);
    }

    public void deleteServer(Long id) {
        serverService.deleteServer(id);
    }

    public void enableServer(String serverId) {
        serverService.enableServer(serverId);
    }

    public void disableServer(String serverId) {
        serverService.disableServer(serverId);
    }

    public void reconnectServer(String serverId) {
        serverService.reconnectServer(serverId);
    }

    public void updateBearerToken(String serverId, String bearerToken) {
        serverService.updateBearerToken(serverId, bearerToken);
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
