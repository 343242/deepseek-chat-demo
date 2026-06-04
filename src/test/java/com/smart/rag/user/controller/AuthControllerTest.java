package com.smart.rag.user.controller;

import com.smart.rag.infrastructure.exception.BusinessException;
import com.smart.rag.infrastructure.exception.GlobalExceptionHandler;
import com.smart.rag.infrastructure.web.dto.CaptchaResult;
import com.smart.rag.infrastructure.web.service.CaptchaService;
import com.smart.rag.infrastructure.web.token.CookieTokenManager;
import com.smart.rag.user.dto.LoginRequest;
import com.smart.rag.user.dto.LoginResponse;
import com.smart.rag.user.dto.RegisterRequest;
import com.smart.rag.user.service.AuthService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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
@DisplayName("AuthController 单元测试")
class AuthControllerTest {

    @Mock private AuthService authService;
    @Mock private CaptchaService captchaService;
    @Mock private CookieTokenManager cookieTokenManager;

    @InjectMocks private AuthController authController;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(authController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private LoginResponse.UserInfo buildUserInfo() {
        return new LoginResponse.UserInfo(1L, "testuser", "Test", "test@example.com", null, List.of("USER"));
    }

    @Nested
    @DisplayName("注册")
    class RegisterTests {

        @Test
        @DisplayName("register_validRequest → 200")
        void register_validRequest() throws Exception {
            when(authService.register(anyString(), anyString(), anyString(), any(), anyString(), anyString()))
                    .thenReturn(buildUserInfo());

            mockMvc.perform(post("/api/auth/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    new RegisterRequest("testuser", "Password1!", "t@e.com", "nick", "cap-id", "100"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.username").value("testuser"));
        }

        @Test
        @DisplayName("register_invalidRequest (blank username) → 200 with VALIDATION_ERROR code")
        void register_invalidRequest() throws Exception {
            mockMvc.perform(post("/api/auth/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    new RegisterRequest("", "Password1!", "t@e.com", "nick", "cap-id", "100"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(100002));
        }
    }

    @Nested
    @DisplayName("登录")
    class LoginTests {

        @Test
        @DisplayName("login_success → 200")
        void login_success() throws Exception {
            AuthService.LoginResult result = new AuthService.LoginResult(
                    new AuthService.TokenPair("access-token", "refresh-token"),
                    new LoginResponse(buildUserInfo())
            );
            when(authService.login(anyString(), anyString(), anyString(), anyString(), anyString()))
                    .thenReturn(result);

            mockMvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    new LoginRequest("testuser", "Password1!", "cap-id", "100"))))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("login_invalidCredentials → 200 with non-zero code (BusinessException)")
        void login_invalidCredentials() throws Exception {
            when(authService.login(anyString(), anyString(), anyString(), anyString(), anyString()))
                    .thenThrow(new BusinessException("用户名或密码错误"));

            mockMvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    new LoginRequest("testuser", "wrong", "cap-id", "100"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(40000));
        }
    }

    @Nested
    @DisplayName("验证码")
    class CaptchaTests {

        @Test
        @DisplayName("captcha_success → 200")
        void captcha_success() throws Exception {
            when(captchaService.generate()).thenReturn(
                    new CaptchaResult("id", "bg", "pz", null));

            mockMvc.perform(get("/api/auth/captcha"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.captchaId").value("id"));
        }
    }
}
