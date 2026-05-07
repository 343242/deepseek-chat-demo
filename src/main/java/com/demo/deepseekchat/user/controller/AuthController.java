package com.demo.deepseekchat.user.controller;

import com.demo.deepseekchat.security.dto.CaptchaResult;
import com.demo.deepseekchat.security.service.CaptchaService;
import com.demo.deepseekchat.security.util.SecurityUtils;
import com.demo.deepseekchat.user.dto.*;
import com.demo.deepseekchat.user.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final CaptchaService captchaService;

    public AuthController(AuthService authService, CaptchaService captchaService) {
        this.authService = authService;
        this.captchaService = captchaService;
    }

    @GetMapping("/captcha")
    public CaptchaResult getCaptcha() {
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
                               HttpServletRequest httpRequest) {
        String ip = httpRequest.getRemoteAddr();
        return authService.login(
                request.username(), request.password(), ip,
                request.captchaId(), request.captchaCode()
        );
    }

    @PostMapping("/refresh")
    public LoginResponse refresh(@RequestBody @Valid RefreshRequest request) {
        return authService.refreshToken(request.refreshToken());
    }

    @PostMapping("/logout")
    public Map<String, String> logout(HttpServletRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        String token = SecurityUtils.extractToken(request);
        authService.logout(userId, token);
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
    public LoginResponse.UserInfo updateProfile(@RequestBody UserUpdateRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        return authService.updateProfile(userId, request);
    }
}
