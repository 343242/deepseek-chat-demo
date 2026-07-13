package com.smart.rag.mcp.admin.controller;

import com.smart.rag.infrastructure.response.GlobalResponse;
import com.smart.rag.mcp.admin.dto.BatchToolUpdateRequest;
import com.smart.rag.mcp.admin.dto.McpConnectionStatus;
import com.smart.rag.mcp.admin.dto.McpMutationOutcome;
import com.smart.rag.mcp.admin.dto.McpMutationResponse;
import com.smart.rag.mcp.admin.dto.ServerConfigResponse;
import com.smart.rag.mcp.admin.dto.ToolConfigResponse;
import com.smart.rag.mcp.admin.dto.UpdateBearerTokenRequest;
import com.smart.rag.mcp.admin.dto.UpdateSecurityConfigRequest;
import com.smart.rag.mcp.admin.dto.UpdateServerRequest;
import com.smart.rag.mcp.admin.dto.UpdateToolRequest;
import com.smart.rag.mcp.admin.dto.VersionRequest;
import com.smart.rag.mcp.admin.entity.McpSecurityConfigView;
import com.smart.rag.mcp.admin.entity.McpServerConfig;
import com.smart.rag.mcp.admin.entity.McpToolConfig;
import com.smart.rag.mcp.admin.service.CreateServerRequest;
import com.smart.rag.mcp.admin.service.McpAdminService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import jakarta.validation.Valid;

import java.util.List;

/**
 * MCP Admin REST API (PRD §R3).
 * <p>
 * All write operations require ADMIN role. Exception handling via GlobalExceptionHandler.
 */
@RestController
@RequestMapping("/api/admin/mcp")
@PreAuthorize("hasRole('ADMIN')")
public class McpAdminController {

    private final McpAdminService service;

    public McpAdminController(McpAdminService service) {
        this.service = service;
    }

    // ==================== Server ====================

    @GetMapping("/servers")
    public GlobalResponse<List<ServerConfigResponse>> listServers() {
        List<ServerConfigResponse> result = service.listServers().stream()
                .map(c -> ServerConfigResponse.from(c, service.serverStatus(c)))
                .toList();
        return GlobalResponse.ok(result);
    }

    @GetMapping("/servers/{id}")
    public GlobalResponse<ServerConfigResponse> getServer(@PathVariable Long id) {
        McpServerConfig c = service.getServer(id);
        return GlobalResponse.ok(ServerConfigResponse.from(c, service.serverStatus(c)));
    }

    @PostMapping("/servers")
    public GlobalResponse<McpMutationResponse> createServer(
            @Valid @RequestBody CreateServerRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        McpServerConfig c = service.createServer(request, idempotencyKey);
        McpConnectionStatus status = service.serverStatus(c);
        return GlobalResponse.ok(McpMutationResponse.accepted(c.getServerId(), status));
    }

    @PostMapping("/servers/{id}/update")
    public GlobalResponse<McpMutationResponse> updateServer(
            @PathVariable Long id,
            @Valid @RequestBody UpdateServerRequest request) {
        McpServerConfig c = service.updateServer(id, request);
        McpConnectionStatus status = service.serverStatus(c);
        McpMutationOutcome outcome = request.url() != null && !request.url().isBlank()
                ? McpMutationOutcome.ACCEPTED : McpMutationOutcome.SUCCESS;
        return GlobalResponse.ok(new McpMutationResponse(
                c.getServerId(), outcome, status, null, null));
    }

    @PostMapping("/servers/{id}/delete")
    public GlobalResponse<McpMutationResponse> deleteServer(
            @PathVariable Long id,
            @Valid @RequestBody VersionRequest request) {
        McpServerConfig c = service.getServer(id);
        service.deleteServer(id);
        return GlobalResponse.ok(McpMutationResponse.successNullStatus(c.getServerId()));
    }

    @PostMapping("/servers/{serverId}/enable")
    public GlobalResponse<McpMutationResponse> enableServer(
            @PathVariable String serverId,
            @Valid @RequestBody VersionRequest request) {
        McpServerConfig c = service.enableServer(serverId);
        return GlobalResponse.ok(McpMutationResponse.accepted(serverId, service.serverStatus(c)));
    }

    @PostMapping("/servers/{serverId}/disable")
    public GlobalResponse<McpMutationResponse> disableServer(
            @PathVariable String serverId,
            @Valid @RequestBody VersionRequest request) {
        McpServerConfig c = service.disableServer(serverId);
        return GlobalResponse.ok(McpMutationResponse.success(serverId, service.serverStatus(c)));
    }

    @PostMapping("/servers/{serverId}/reconnect")
    public GlobalResponse<McpMutationResponse> reconnectServer(@PathVariable String serverId) {
        McpServerConfig c = service.reconnectServer(serverId);
        return GlobalResponse.ok(McpMutationResponse.accepted(serverId, service.serverStatus(c)));
    }

    @PostMapping("/servers/{serverId}/update-bearer-token")
    public GlobalResponse<McpMutationResponse> updateBearerToken(
            @PathVariable String serverId,
            @Valid @RequestBody UpdateBearerTokenRequest request) {
        McpServerConfig c = service.updateBearerToken(serverId, request.bearerToken());
        return GlobalResponse.ok(McpMutationResponse.accepted(serverId, service.serverStatus(c)));
    }

    // ==================== Tool ====================

    @GetMapping("/servers/{serverId}/tools")
    public GlobalResponse<List<ToolConfigResponse>> listTools(@PathVariable String serverId) {
        List<ToolConfigResponse> tools = service.listTools(serverId).stream()
                .map(ToolConfigResponse::from)
                .toList();
        return GlobalResponse.ok(tools);
    }

    @PostMapping("/tools/{id}/enable")
    public GlobalResponse<Void> enableTool(@PathVariable Long id) {
        service.enableTool(id);
        return GlobalResponse.ok(null);
    }

    @PostMapping("/tools/{id}/disable")
    public GlobalResponse<Void> disableTool(@PathVariable Long id) {
        service.disableTool(id);
        return GlobalResponse.ok(null);
    }

    @PostMapping("/tools/batch-enable")
    public GlobalResponse<Void> batchEnableTools(@Valid @RequestBody BatchToolUpdateRequest request) {
        service.batchEnableTools(request.ids());
        return GlobalResponse.ok(null);
    }

    @PostMapping("/tools/batch-disable")
    public GlobalResponse<Void> batchDisableTools(@Valid @RequestBody BatchToolUpdateRequest request) {
        service.batchDisableTools(request.ids());
        return GlobalResponse.ok(null);
    }

    @PostMapping("/tools/{id}/update")
    public GlobalResponse<Void> updateTool(@PathVariable Long id,
                                           @Valid @RequestBody UpdateToolRequest request) {
        service.updateTool(id, request);
        return GlobalResponse.ok(null);
    }

    // ==================== Security ====================

    @GetMapping("/security")
    public GlobalResponse<McpSecurityConfigView> getSecurityConfig() {
        return GlobalResponse.ok(service.getSecurityConfig());
    }

    @PostMapping("/security")
    public GlobalResponse<Void> updateSecurityConfig(
            @Valid @RequestBody UpdateSecurityConfigRequest request) {
        service.updateSecurityConfig(request.toView());
        return GlobalResponse.ok(null);
    }
}
