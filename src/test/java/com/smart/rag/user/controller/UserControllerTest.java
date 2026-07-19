package com.smart.rag.user.controller;

import com.smart.rag.infrastructure.response.PagedResult;
import com.smart.rag.infrastructure.exception.BusinessException;
import com.smart.rag.infrastructure.exception.GlobalExceptionHandler;
import com.smart.rag.user.dto.*;
import com.smart.rag.user.service.SysUserService;
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

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("UserController 单元测试")
class UserControllerTest {

    @Mock
    private SysUserService sysUserService;

    @InjectMocks
    private UserController userController;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final LoginResponse.UserInfo testUserInfo = new LoginResponse.UserInfo(
            1L, "testuser", "nick", "test@example.com", "avatar.png", List.of("USER"), List.of());

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(userController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("获取用户列表 → 200")
    void listUsers_success() throws Exception {
        PagedResult<UserVO> result = new PagedResult<>(List.of(), 1, 20, 0, 0);
        when(sysUserService.listUsers(1, 20, null)).thenReturn(result);

        mockMvc.perform(get("/api/users?page=1&size=20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.page").value(1));
    }

    @Test
    @DisplayName("获取单个用户 → 200")
    void getUser_success() throws Exception {
        when(sysUserService.getUser(1L)).thenReturn(testUserInfo);

        mockMvc.perform(get("/api/users/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.username").value("testuser"));
    }

    @Test
    @DisplayName("更新用户 - 邮箱格式错误 → 200 with VALIDATION_ERROR code")
    void updateUser_invalidEmail() throws Exception {
        UserUpdateRequest req = new UserUpdateRequest(null, "not-an-email", null, null);

        mockMvc.perform(post("/api/users/1/update")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(100002));
    }

    @Test
    @DisplayName("更新用户状态 - 无效状态 → 200 with non-zero code")
    void updateUserStatus_invalidStatus() throws Exception {
        when(sysUserService.updateUserStatus(1L, 2)).thenThrow(
                new BusinessException("无效的用户状态，仅支持 0(禁用) 和 1(启用)"));

        mockMvc.perform(post("/api/users/1/status?status=2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40000));
    }

    @Test
    @DisplayName("更新用户状态 - 禁用 → 200")
    void updateUserStatus_validDisabled() throws Exception {
        when(sysUserService.updateUserStatus(1L, 0)).thenReturn(
                new UserStatusUpdateResult(1L, 0, "已禁用"));

        mockMvc.perform(post("/api/users/1/status?status=0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.message").value("已禁用"));
    }

    @Test
    @DisplayName("分配角色 - 空列表 → 200 with VALIDATION_ERROR code")
    void assignRoles_emptyList() throws Exception {
        AssignRolesRequest req = new AssignRolesRequest(List.of());

        mockMvc.perform(post("/api/users/1/roles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(100002));
    }

    @Test
    @DisplayName("删除用户 → 200")
    void deleteUser_success() throws Exception {
        when(sysUserService.deleteUser(1L)).thenReturn(new UserDeleteResult(1L, "用户已删除"));

        mockMvc.perform(post("/api/users/1/delete"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.message").value("用户已删除"));
    }
}
