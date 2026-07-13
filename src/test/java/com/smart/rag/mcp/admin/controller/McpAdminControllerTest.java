package com.smart.rag.mcp.admin.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smart.rag.infrastructure.response.GlobalResponse;
import com.smart.rag.mcp.admin.dto.UpdateSecurityConfigRequest;
import com.smart.rag.mcp.admin.entity.McpSecurityConfigView;
import com.smart.rag.mcp.admin.entity.McpServerConfig;
import com.smart.rag.mcp.admin.service.CreateServerRequest;
import com.smart.rag.mcp.admin.service.McpAdminService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("McpAdminController: route + delegation + JSON 序列化")
class McpAdminControllerTest {

    @Mock
    private McpAdminService service;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private MockMvc mockMvc;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        McpAdminController controller = new McpAdminController(service);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    @DisplayName("GET /servers → listServers 委托 + 200")
    void listServers_returnsList() throws Exception {
        McpServerConfig config = new McpServerConfig();
        config.setId(1L);
        config.setServerId("weather");
        config.setUrl("https://mcp.example.com");
        config.setEnabled(true);
        when(service.listServers()).thenReturn(List.of(config));
        when(service.serverHealth("weather")).thenReturn("ALIVE");

        mockMvc.perform(get("/api/admin/mcp/servers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].serverId").value("weather"))
                .andExpect(jsonPath("$.data[0].health").value("ALIVE"));
    }

    @Test
    @DisplayName("POST /servers → createServer 委托")
    void createServer_delegates() throws Exception {
        CreateServerRequest req = new CreateServerRequest(
                "https://mcp.example.com", "weather", null, true, null);
        McpServerConfig saved = new McpServerConfig();
        saved.setId(10L);
        saved.setServerId("weather");
        saved.setUrl("https://mcp.example.com");
        saved.setEnabled(true);
        when(service.createServer(any(), any())).thenReturn(saved);
        when(service.serverHealth("weather")).thenReturn("ALIVE");

        mockMvc.perform(post("/api/admin/mcp/servers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(10));
    }

    @Test
    @DisplayName("POST /servers/{id}/delete → deleteServer 委托")
    void deleteServer_delegates() throws Exception {
        mockMvc.perform(post("/api/admin/mcp/servers/42/delete"))
                .andExpect(status().isOk());
        verify(service).deleteServer(42L);
    }

    @Test
    @DisplayName("POST /servers/{serverId}/reconnect → reconnectServer 委托")
    void reconnectServer_delegates() throws Exception {
        mockMvc.perform(post("/api/admin/mcp/servers/weather/reconnect"))
                .andExpect(status().isOk());
        verify(service).reconnectServer("weather");
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
    @DisplayName("POST /security/update → updateSecurityConfig 委托")
    void updateSecurityConfig_delegates() throws Exception {
        UpdateSecurityConfigRequest req = new UpdateSecurityConfigRequest(
                List.of("pattern"), 3000, 800, 256);
        mockMvc.perform(post("/api/admin/mcp/security/update")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk());
        verify(service).updateSecurityConfig(any(McpSecurityConfigView.class));
    }

    @Test
    @DisplayName("GET /health → 聚合各 server 健康状态")
    void health_aggregated() throws Exception {
        McpServerConfig c1 = new McpServerConfig();
        c1.setId(1L);
        c1.setServerId("weather");
        McpServerConfig c2 = new McpServerConfig();
        c2.setId(2L);
        c2.setServerId("tavily");
        when(service.listServers()).thenReturn(List.of(c1, c2));
        when(service.serverHealth("weather")).thenReturn("ALIVE");
        when(service.serverHealth("tavily")).thenReturn("DOWN");

        mockMvc.perform(get("/api/admin/mcp/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.weather").value("ALIVE"))
                .andExpect(jsonPath("$.data.tavily").value("DOWN"));
    }

    @Test
    void createServerRejectsBlankUrlBeforeService() throws Exception {
        mockMvc.perform(post("/api/admin/mcp/servers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"   \",\"name\":\"test\"}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(service);
    }

    @Test
    void updateBearerTokenRejectsBlankValueBeforeService() throws Exception {
        mockMvc.perform(post("/api/admin/mcp/servers/knowledge/update-bearer-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bearerToken\":\"\"}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(service);
    }

    @Test
    void batchUpdateRejectsEmptyIdsBeforeService() throws Exception {
        mockMvc.perform(post("/api/admin/mcp/tools/batch-enable")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\":[]}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(service);
    }

    @Test
    void toolUpdateRejectsUnknownIntentAndRiskBeforeService() throws Exception {
        mockMvc.perform(post("/api/admin/mcp/tools/1/update")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"intent\":\"UNKNOWN\",\"risk\":\"critical\"}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(service);
    }

    @Test
    void securityUpdateRejectsNonPositiveCapBeforeService() throws Exception {
        mockMvc.perform(post("/api/admin/mcp/security/update")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sensitiveArgPatterns\":[],\"defaultOutputCapChars\":0,"
                                + "\"highRiskOutputCapChars\":1,\"toolDescCharLimit\":1}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(service);
    }
}
