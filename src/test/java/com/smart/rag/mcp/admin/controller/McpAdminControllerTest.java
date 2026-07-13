package com.smart.rag.mcp.admin.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smart.rag.infrastructure.exception.GlobalExceptionHandler;
import com.smart.rag.mcp.admin.dto.McpConnectionStatus;
import com.smart.rag.mcp.admin.dto.UpdateSecurityConfigRequest;
import com.smart.rag.mcp.admin.entity.McpSecurityConfigView;
import com.smart.rag.mcp.admin.entity.McpServerConfig;
import com.smart.rag.mcp.admin.service.CreateServerRequest;
import com.smart.rag.mcp.admin.service.McpAdminService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.SpringValidatorAdapter;

import jakarta.validation.Validation;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
@DisplayName("McpAdminController: route + delegation + JSON 序列化")
class McpAdminControllerTest {

    @Mock private McpAdminService service;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        var validator = new SpringValidatorAdapter(
                Validation.buildDefaultValidatorFactory().getValidator());
        mockMvc = MockMvcBuilders.standaloneSetup(new McpAdminController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setValidator(validator)
                .build();
    }

    private McpServerConfig config(long id, String serverId) {
        McpServerConfig c = new McpServerConfig();
        c.setId(id);
        c.setServerId(serverId);
        c.setUrl("https://mcp.example.com");
        c.setEnabled(true);
        return c;
    }

    @Test
    @DisplayName("GET /servers → listServers uses projector status")
    void listServers_returnsList() throws Exception {
        McpServerConfig c = config(1L, "weather");
        when(service.listServers()).thenReturn(List.of(c));
        when(service.serverStatus(c)).thenReturn(McpConnectionStatus.READY);

        mockMvc.perform(get("/api/admin/mcp/servers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].serverId").value("weather"))
                .andExpect(jsonPath("$.data[0].status").value("READY"));
    }

    @Test
    @DisplayName("POST /servers → createServer returns McpMutationResponse")
    void createServer_delegates() throws Exception {
        CreateServerRequest req = new CreateServerRequest(
                "https://mcp.example.com", "weather", null, true, null);
        McpServerConfig saved = config(10L, "mcp_10");
        when(service.createServer(any(), any())).thenReturn(saved);
        when(service.serverStatus(saved)).thenReturn(McpConnectionStatus.PENDING);

        mockMvc.perform(post("/api/admin/mcp/servers")
                        .header("Idempotency-Key", "test-key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.resourceId").value("mcp_10"))
                .andExpect(jsonPath("$.data.outcome").value("ACCEPTED"))
                .andExpect(jsonPath("$.data.status").value("PENDING"));
    }

    @Test
    @DisplayName("POST /servers/{id}/delete → deleteServer requires version body")
    void deleteServer_delegates() throws Exception {
        McpServerConfig c = config(42L, "mcp_42");
        when(service.getServer(42L)).thenReturn(c);

        mockMvc.perform(post("/api/admin/mcp/servers/42/delete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.resourceId").value("mcp_42"))
                .andExpect(jsonPath("$.data.outcome").value("SUCCESS"));
        verify(service).deleteServer(42L);
    }

    @Test
    @DisplayName("POST /servers/{serverId}/reconnect → returns ACCEPTED")
    void reconnectServer_delegates() throws Exception {
        McpServerConfig c = config(1L, "mcp_1");
        when(service.reconnectServer("mcp_1")).thenReturn(c);
        when(service.serverStatus(c)).thenReturn(McpConnectionStatus.PENDING);

        mockMvc.perform(post("/api/admin/mcp/servers/mcp_1/reconnect"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.outcome").value("ACCEPTED"))
                .andExpect(jsonPath("$.data.status").value("PENDING"));
    }

    @Test
    @DisplayName("GET /security → getSecurityConfig 委托 + JSON")
    void getSecurityConfig_returnsView() throws Exception {
        when(service.getSecurityConfig()).thenReturn(McpSecurityConfigView.defaults());

        mockMvc.perform(get("/api/admin/mcp/security"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.defaultOutputCapChars").value(4000));
    }

    @Test
    @DisplayName("POST /security → updateSecurityConfig 委托")
    void updateSecurityConfig_delegates() throws Exception {
        UpdateSecurityConfigRequest req = new UpdateSecurityConfigRequest(
                List.of("pattern"), 3000, 800, 256);
        mockMvc.perform(post("/api/admin/mcp/security")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk());
        verify(service).updateSecurityConfig(any(McpSecurityConfigView.class));
    }

    @Test
    void createServerRejectsBlankUrlBeforeService() throws Exception {
        mockMvc.perform(post("/api/admin/mcp/servers")
                        .header("Idempotency-Key", "test-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"   \",\"name\":\"test\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").isNumber());
    }

    @Test
    void updateBearerTokenAllowsNullToken() throws Exception {
        McpServerConfig c = config(1L, "mcp_1");
        when(service.updateBearerToken("mcp_1", null)).thenReturn(c);
        when(service.serverStatus(c)).thenReturn(McpConnectionStatus.PENDING);

        mockMvc.perform(post("/api/admin/mcp/servers/mcp_1/update-bearer-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bearerToken\":null}"))
                .andExpect(status().isOk());
    }

    @Test
    void batchUpdateRejectsEmptyIdsBeforeService() throws Exception {
        mockMvc.perform(post("/api/admin/mcp/tools/batch-enable")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\":[]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").isNumber());
    }
}
