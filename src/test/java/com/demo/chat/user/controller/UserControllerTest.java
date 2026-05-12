package com.demo.chat.user.controller;

import com.demo.chat.common.response.PagedResult;
import com.demo.chat.exception.BusinessException;
import com.demo.chat.exception.GlobalExceptionHandler;
import com.demo.chat.user.dto.*;
import com.demo.chat.user.service.SysUserService;
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

import static org.mockito.ArgumentMatchers.*;
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
            1L, "testuser", "nick", "test@example.com", "avatar.png", List.of("USER"));

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
    @DisplayName("更新用户 - 邮箱格式错误 → 400")
    void updateUser_invalidEmail() throws Exception {
        UserUpdateRequest req = new UserUpdateRequest(null, "not-an-email", null, null);

        mockMvc.perform(patch("/api/users/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("更新用户状态 - 无效状态 → 400")
    void updateUserStatus_invalidStatus() throws Exception {
        when(sysUserService.updateUserStatus(1L, 2)).thenThrow(
                new BusinessException("无效的用户状态，仅支持 0(禁用) 和 1(启用)"));

        mockMvc.perform(patch("/api/users/1/status?status=2"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("更新用户状态 - 禁用 → 200")
    void updateUserStatus_validDisabled() throws Exception {
        when(sysUserService.updateUserStatus(1L, 0)).thenReturn(
                new UserStatusUpdateResult(1L, 0, "已禁用"));

        mockMvc.perform(patch("/api/users/1/status?status=0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.message").value("已禁用"));
    }

    @Test
    @DisplayName("分配角色 - 空列表 → 400")
    void assignRoles_emptyList() throws Exception {
        AssignRolesRequest req = new AssignRolesRequest(List.of());

        mockMvc.perform(patch("/api/users/1/roles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("删除用户 → 200")
    void deleteUser_success() throws Exception {
        when(sysUserService.deleteUser(1L)).thenReturn(new UserDeleteResult(1L, "用户已删除"));

        mockMvc.perform(delete("/api/users/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.message").value("用户已删除"));
    }
}
