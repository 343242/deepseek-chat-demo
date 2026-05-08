package com.demo.deepseekchat.user.controller;

import com.demo.deepseekchat.security.config.JwtProperties;
import com.demo.deepseekchat.security.dto.CaptchaResult;
import com.demo.deepseekchat.security.service.CaptchaService;
import com.demo.deepseekchat.security.util.SecurityUtils;
import com.demo.deepseekchat.user.dto.*;
import com.demo.deepseekchat.user.service.AuthService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final CaptchaService captchaService;
    private final JwtProperties jwtProperties;

    @Value("${app.jwt.access-expiration:900}")
    private long accessExpiration;

    @Value("${app.jwt.refresh-expiration:86400}")
    private long refreshExpiration;

    public AuthController(AuthService authService, CaptchaService captchaService, JwtProperties jwtProperties) {
        this.authService = authService;
        this.captchaService = captchaService;
        this.jwtProperties = jwtProperties;
    }

    @GetMapping("/captcha")
    public CaptchaResult getCaptcha(HttpServletRequest httpRequest) {
        String ip = httpRequest.getRemoteAddr();
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
        setTokenCookies(httpResponse, result.tokens().accessToken(), result.tokens().refreshToken());
        return result.response();
    }

    @PostMapping("/refresh")
    public LoginResponse refresh(@RequestBody(required = false) @Valid RefreshRequest request,
                                 HttpServletRequest httpRequest,
                                 HttpServletResponse httpResponse) {
        // 从请求体或 Cookie 中获取 refreshToken
        String refreshToken = null;
        if (request != null && request.refreshToken() != null) {
            refreshToken = request.refreshToken();
        } else {
            Cookie[] cookies = httpRequest.getCookies();
            if (cookies != null) {
                for (Cookie c : cookies) {
                    if ("refresh_token".equals(c.getName())) {
                        refreshToken = c.getValue();
                        break;
                    }
                }
            }
        }
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new com.demo.deepseekchat.exception.BusinessException("缺少刷新令牌");
        }
        AuthService.LoginResult result = authService.refreshToken(refreshToken);
        setTokenCookies(httpResponse, result.tokens().accessToken(), result.tokens().refreshToken());
        return result.response();
    }

    @PostMapping("/logout")
    public Map<String, String> logout(HttpServletRequest request,
                                      HttpServletResponse httpResponse) {
        Long userId = SecurityUtils.getCurrentUserId();
        String token = SecurityUtils.extractToken(request);
        authService.logout(userId, token);
        clearTokenCookies(httpResponse);
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

    private void setTokenCookies(HttpServletResponse response, String accessToken, String refreshToken) {
        Cookie accessCookie = new Cookie("access_token", accessToken);
        accessCookie.setHttpOnly(true);
        accessCookie.setSecure(jwtProperties.cookieSecure());
        accessCookie.setPath("/api");
        accessCookie.setMaxAge((int) accessExpiration);
        response.addCookie(accessCookie);

        Cookie refreshCookie = new Cookie("refresh_token", refreshToken);
        refreshCookie.setHttpOnly(true);
        refreshCookie.setSecure(jwtProperties.cookieSecure());
        refreshCookie.setPath("/api/auth/refresh");
        refreshCookie.setMaxAge((int) refreshExpiration);
        response.addCookie(refreshCookie);
    }

    private void clearTokenCookies(HttpServletResponse response) {
        Cookie accessCookie = new Cookie("access_token", "");
        accessCookie.setHttpOnly(true);
        accessCookie.setPath("/api");
        accessCookie.setMaxAge(0);
        response.addCookie(accessCookie);

        Cookie refreshCookie = new Cookie("refresh_token", "");
        refreshCookie.setHttpOnly(true);
        refreshCookie.setPath("/api/auth/refresh");
        refreshCookie.setMaxAge(0);
        response.addCookie(refreshCookie);
    }
}
