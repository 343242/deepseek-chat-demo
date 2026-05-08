package com.demo.chat.user.controller;

import com.demo.chat.exception.BusinessException;
import com.demo.chat.security.dto.CaptchaResult;
import com.demo.chat.security.service.CaptchaService;
import com.demo.chat.security.token.CookieTokenManager;
import com.demo.chat.security.util.SecurityUtils;
import com.demo.chat.user.dto.*;
import com.demo.chat.user.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 认证控制器 — 仅负责 HTTP 请求/响应的转发
 *
 * <p>职责：参数接收 → 调用 Service → 返回结果。</p>
 * <p>Cookie 管理委托给 {@link CookieTokenManager}，不持有任何 Cookie 逻辑。</p>
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
    public CaptchaResult getCaptcha(HttpServletRequest request) {
        String ip = request.getRemoteAddr();
        captchaService.checkRateLimit(ip);
        return captchaService.generate();
    }

    @PostMapping("/register")
    public LoginResponse.UserInfo register(@Valid @RequestBody RegisterRequest request) {
        return authService.register(
                request.username(), request.password(), request.email(),
                request.nickname(), request.captchaId(), request.captchaCode()
        );
    }

    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest request,
                               HttpServletRequest httpRequest,
                               HttpServletResponse httpResponse) {
        String ip = httpRequest.getRemoteAddr();
        AuthService.LoginResult result = authService.login(
                request.username(), request.password(), ip,
                request.captchaId(), request.captchaCode()
        );
        cookieTokenManager.setTokenCookies(httpResponse, result.tokens().accessToken(), result.tokens().refreshToken());
        return result.response();
    }

    @PostMapping("/refresh")
    public LoginResponse refresh(@RequestBody(required = false) @Valid RefreshRequest request,
                                 HttpServletRequest httpRequest,
                                 HttpServletResponse httpResponse) {
        String refreshToken = resolveRefreshToken(request, httpRequest);
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new BusinessException("缺少刷新令牌");
        }
        AuthService.LoginResult result = authService.refreshToken(refreshToken);
        cookieTokenManager.setTokenCookies(httpResponse, result.tokens().accessToken(), result.tokens().refreshToken());
        return result.response();
    }

    @PostMapping("/logout")
    public Map<String, String> logout(HttpServletRequest request,
                                      HttpServletResponse httpResponse) {
        Long userId = SecurityUtils.getCurrentUserId();
        String token = SecurityUtils.extractToken(request);
        authService.logout(userId, token);
        cookieTokenManager.clearTokenCookies(httpResponse);
        return Map.of("message", "已登出");
    }

    @GetMapping("/me")
    public LoginResponse.UserInfo getCurrentUser() {
        Long userId = SecurityUtils.getCurrentUserId();
        return authService.getCurrentUser(userId);
    }

    @PatchMapping("/me/password")
    public Map<String, String> changePassword(@Valid @RequestBody ChangePasswordRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        authService.changePassword(userId, request.oldPassword(), request.newPassword());
        return Map.of("message", "密码已修改");
    }

    @PatchMapping("/me/profile")
    public LoginResponse.UserInfo updateProfile(@Valid @RequestBody UserUpdateRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        return authService.updateProfile(userId, request);
    }

    // ==================== Private ====================

    /**
     * 从请求体或 Cookie 中解析 refresh_token（策略：body 优先，cookie 回退）
     */
    private String resolveRefreshToken(RefreshRequest request, HttpServletRequest httpRequest) {
        if (request != null && request.refreshToken() != null) {
            return request.refreshToken();
        }
        return cookieTokenManager.extractRefreshToken(httpRequest);
    }
}
