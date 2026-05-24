package com.smart.rag.user.controller;

import com.smart.rag.common.errorcode.ErrorCode;
import com.smart.rag.common.response.GlobalResponse;
import com.smart.rag.exception.BusinessException;
import com.smart.rag.security.dto.CaptchaResult;
import com.smart.rag.security.service.CaptchaService;
import com.smart.rag.security.token.CookieTokenManager;
import com.smart.rag.security.util.SecurityUtils;
import com.smart.rag.user.dto.*;
import com.smart.rag.user.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

/**
 * 认证控制器 — 仅负责 HTTP 请求/响应的转发
 * <p>
 * 职责：参数接收 → 调用 Service → 返回 GlobalResponse。
 * Cookie 管理委托给 {@link CookieTokenManager}。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final CaptchaService captchaService;
    private final CookieTokenManager cookieTokenManager;

    public AuthController(AuthService authService,
                          CaptchaService captchaService,
                          CookieTokenManager cookieTokenManager) {
        this.authService = authService;
        this.captchaService = captchaService;
        this.cookieTokenManager = cookieTokenManager;
    }

    @GetMapping("/captcha")
    public GlobalResponse<CaptchaResult> getCaptcha(HttpServletRequest request) {
        String ip = request.getRemoteAddr();
        captchaService.checkRateLimit(ip);
        return GlobalResponse.ok(captchaService.generate());
    }

    @PostMapping("/register")
    public GlobalResponse<LoginResponse.UserInfo> register(@Valid @RequestBody RegisterRequest request) {
        return GlobalResponse.ok(authService.register(
                request.username(), request.password(), request.email(),
                request.nickname(), request.captchaId(), request.captchaCode()
        ));
    }

    @PostMapping("/login")
    public GlobalResponse<LoginResponse> login(@Valid @RequestBody LoginRequest request,
                                                HttpServletRequest httpRequest,
                                                HttpServletResponse httpResponse) {
        String ip = httpRequest.getRemoteAddr();
        AuthService.LoginResult result = authService.login(
                request.username(), request.password(), ip,
                request.captchaId(), request.captchaCode()
        );
        cookieTokenManager.setTokenCookies(httpResponse, result.tokens().accessToken(), result.tokens().refreshToken());
        return GlobalResponse.ok(result.response());
    }

    @PostMapping("/refresh")
    public GlobalResponse<LoginResponse> refresh(@RequestBody(required = false) @Valid RefreshRequest request,
                                                  HttpServletRequest httpRequest,
                                                  HttpServletResponse httpResponse) {
        String refreshToken = resolveRefreshToken(request, httpRequest);
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new BusinessException(ErrorCode.REFRESH_TOKEN_MISSING);
        }
        AuthService.LoginResult result = authService.refreshToken(refreshToken);
        cookieTokenManager.setTokenCookies(httpResponse, result.tokens().accessToken(), result.tokens().refreshToken());
        return GlobalResponse.ok(result.response());
    }

    @PostMapping("/logout")
    public GlobalResponse<Void> logout(HttpServletRequest request,
                                       HttpServletResponse httpResponse) {
        Long userId = SecurityUtils.getCurrentUserId();
        String token = SecurityUtils.extractToken(request);
        authService.logout(userId, token);
        cookieTokenManager.clearTokenCookies(httpResponse);
        return GlobalResponse.ok("已登出");
    }

    @GetMapping("/me")
    public GlobalResponse<LoginResponse.UserInfo> getCurrentUser() {
        Long userId = SecurityUtils.getCurrentUserId();
        return GlobalResponse.ok(authService.getCurrentUser(userId));
    }

    @PostMapping("/me/password")
    public GlobalResponse<Void> changePassword(@Valid @RequestBody ChangePasswordRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        authService.changePassword(userId, request.oldPassword(), request.newPassword());
        return GlobalResponse.ok("密码已修改");
    }

    @PostMapping("/me/profile")
    public GlobalResponse<LoginResponse.UserInfo> updateProfile(@Valid @RequestBody UserUpdateRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        return GlobalResponse.ok(authService.updateProfile(userId, request));
    }

    // ==================== Private ====================

    private String resolveRefreshToken(RefreshRequest request, HttpServletRequest httpRequest) {
        if (request != null && request.refreshToken() != null) {
            return request.refreshToken();
        }
        return cookieTokenManager.extractRefreshToken(httpRequest);
    }
}
