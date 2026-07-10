package com.smart.rag.mcp.admin.controller;

import com.smart.rag.infrastructure.response.GlobalResponse;
import com.smart.rag.mcp.admin.dto.BatchToolUpdateRequest;
import com.smart.rag.mcp.admin.dto.ServerConfigResponse;
import com.smart.rag.mcp.admin.dto.ToolConfigResponse;
import com.smart.rag.mcp.admin.dto.UpdateBearerTokenRequest;
import com.smart.rag.mcp.admin.dto.UpdateSecurityConfigRequest;
import com.smart.rag.mcp.admin.dto.UpdateServerRequest;
import com.smart.rag.mcp.admin.dto.UpdateToolRequest;
import com.smart.rag.mcp.admin.entity.McpServerConfig;
import com.smart.rag.mcp.admin.entity.McpToolConfig;
import com.smart.rag.mcp.admin.service.CreateServerRequest;
import com.smart.rag.mcp.admin.service.McpAdminService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import jakarta.validation.Valid;

import java.util.List;
import java.util.Map;

/**
 * MCP Admin REST API——所有写操作仅 ADMIN 角色（类级 {@link PreAuthorize}）。
 * <p>
 * <b>仅 GET / POST 方法</b>（design R5.1）；异常经 GlobalExceptionHandler 统一 HTTP 200 + 业务码。
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
                .map(c -> ServerConfigResponse.from(c,
                        c.getServerId() != null ? service.serverHealth(c.getServerId()) : "UNKNOWN"))
                .toList();
        return GlobalResponse.ok(result);
    }

    @GetMapping("/servers/{id}")
    public GlobalResponse<ServerConfigResponse> getServer(@PathVariable Long id) {
        McpServerConfig c = service.getServer(id);
        return GlobalResponse.ok(ServerConfigResponse.from(c,
                c.getServerId() != null ? service.serverHealth(c.getServerId()) : "UNKNOWN"));
    }

    @PostMapping("/servers")
    public GlobalResponse<ServerConfigResponse> createServer(@Valid @RequestBody CreateServerRequest request) {
        McpServerConfig c = service.createServer(request);
        return GlobalResponse.ok(ServerConfigResponse.from(c,
                c.getServerId() != null ? service.serverHealth(c.getServerId()) : "UNKNOWN"));
    }

    @PostMapping("/servers/{id}/update")
    public GlobalResponse<Void> updateServer(@PathVariable Long id, @Valid @RequestBody UpdateServerRequest request) {
        service.updateServer(id, request);
        return GlobalResponse.ok(null);
    }

    @PostMapping("/servers/{id}/delete")
    public GlobalResponse<Void> deleteServer(@PathVariable Long id) {
        service.deleteServer(id);
        return GlobalResponse.ok(null);
    }

    @PostMapping("/servers/{serverId}/enable")
    public GlobalResponse<Void> enableServer(@PathVariable String serverId) {
        service.enableServer(serverId);
        return GlobalResponse.ok(null);
    }

    @PostMapping("/servers/{serverId}/disable")
    public GlobalResponse<Void> disableServer(@PathVariable String serverId) {
        service.disableServer(serverId);
        return GlobalResponse.ok(null);
    }

    @PostMapping("/servers/{serverId}/reconnect")
    public GlobalResponse<Void> reconnectServer(@PathVariable String serverId) {
        service.reconnectServer(serverId);
        return GlobalResponse.ok(null);
    }

    @PostMapping("/servers/{serverId}/update-bearer-token")
    public GlobalResponse<Void> updateBearerToken(@PathVariable String serverId,
                                                   @Valid @RequestBody UpdateBearerTokenRequest request) {
        service.updateBearerToken(serverId, request.bearerToken());
        return GlobalResponse.ok(null);
    }

    // ==================== Tool ====================

    @GetMapping("/servers/{serverId}/tools")
    public GlobalResponse<List<ToolConfigResponse>> listTools(@PathVariable String serverId) {
        List<ToolConfigResponse> result = service.listTools(serverId).stream()
                .map(ToolConfigResponse::from).toList();
        return GlobalResponse.ok(result);
    }

    @PostMapping("/servers/{serverId}/refresh-tools")
    public GlobalResponse<Void> refreshTools(@PathVariable String serverId) {
        service.refreshTools(serverId);
        return GlobalResponse.ok(null);
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
    public GlobalResponse<Void> updateTool(@PathVariable Long id, @Valid @RequestBody UpdateToolRequest request) {
        service.updateTool(id, request);
        return GlobalResponse.ok(null);
    }

    // ==================== Security config ====================

    @GetMapping("/security")
    public GlobalResponse<UpdateSecurityConfigRequest> getSecurityConfig() {
        var v = service.getSecurityConfig();
        return GlobalResponse.ok(new UpdateSecurityConfigRequest(
                v.sensitiveArgPatterns(),
                v.defaultOutputCapChars(),
                v.highRiskOutputCapChars(),
                v.toolDescCharLimit()));
    }

    @PostMapping("/security/update")
    public GlobalResponse<Void> updateSecurityConfig(@Valid @RequestBody UpdateSecurityConfigRequest request) {
        service.updateSecurityConfig(request.toView());
        return GlobalResponse.ok(null);
    }

    // ==================== Health（聚合） ====================

    @GetMapping("/health")
    public GlobalResponse<Map<String, String>> health() {
        Map<String, String> result = new java.util.LinkedHashMap<>();
        for (McpServerConfig c : service.listServers()) {
            String sid = c.getServerId();
            result.put(sid != null ? sid : ("id-" + c.getId()),
                    sid != null ? service.serverHealth(sid) : "UNREACHABLE");
        }
        return GlobalResponse.ok(result);
    }
}
