package com.demo.chat.user.controller;

import com.demo.chat.exception.BusinessException;
import com.demo.chat.exception.GlobalExceptionHandler;
import com.demo.chat.user.dto.AssignPermissionsRequest;
import com.demo.chat.user.service.SysPermissionService;
import com.demo.chat.user.service.SysRoleService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("RoleController 单元测试")
class RoleControllerTest {

    @Mock
    private SysRoleService roleService;

    @Mock
    private SysPermissionService permissionService;

    @InjectMocks
    private RoleController roleController;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(roleController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("获取角色列表 → 200")
    void listRoles_success() throws Exception {
        when(roleService.listRoles()).thenReturn(List.of());

        mockMvc.perform(get("/api/roles"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("创建角色 - 名称为空 → 400 (BusinessException)")
    void createRole_blankName() throws Exception {
        Map<String, String> req = Map.of("roleName", "", "roleDesc", "desc");

        mockMvc.perform(post("/api/roles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("分配权限 - 空列表 → 400")
    void assignPermissions_emptyList() throws Exception {
        AssignPermissionsRequest req = new AssignPermissionsRequest(List.of());

        mockMvc.perform(patch("/api/roles/1/permissions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("分配权限 - 合法请求 → 200")
    void assignPermissions_valid() throws Exception {
        AssignPermissionsRequest req = new AssignPermissionsRequest(List.of(1L, 2L));

        mockMvc.perform(patch("/api/roles/1/permissions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.message").value("权限已更新"));
    }
}
