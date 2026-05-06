package com.demo.deepseekchat.user.controller;

import com.demo.deepseekchat.user.dto.*;
import com.demo.deepseekchat.user.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    public LoginResponse.UserInfo register(@RequestBody RegisterRequest request) {
        return authService.register(request.username(), request.password(), request.nickname());
    }

    @PostMapping("/login")
    public LoginResponse login(@RequestBody LoginRequest request,
                               HttpServletRequest httpRequest) {
        String ip = httpRequest.getRemoteAddr();
        return authService.login(request.username(), request.password(), ip);
    }

    @PostMapping("/refresh")
    public LoginResponse refresh(@RequestBody RefreshRequest request) {
        return authService.refreshToken(request.refreshToken());
    }

    @PostMapping("/logout")
    public Map<String, String> logout(HttpServletRequest request) {
        Long userId = getCurrentUserId();
        String token = extractToken(request);
        authService.logout(userId, token);
        return Map.of("message", "已登出");
    }

    @GetMapping("/me")
    public LoginResponse.UserInfo getCurrentUser() {
        Long userId = getCurrentUserId();
        return authService.getCurrentUser(userId);
    }

    @PatchMapping("/me/password")
    public Map<String, String> changePassword(@RequestBody ChangePasswordRequest request) {
        Long userId = getCurrentUserId();
        authService.changePassword(userId, request.oldPassword(), request.newPassword());
        return Map.of("message", "密码已修改");
    }

    @PatchMapping("/me/profile")
    public LoginResponse.UserInfo updateProfile(@RequestBody UserUpdateRequest request) {
        Long userId = getCurrentUserId();
        return authService.updateProfile(userId, request);
    }

    private Long getCurrentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getPrincipal() == null) {
            throw new IllegalStateException("未认证");
        }
        return (Long) auth.getPrincipal();
    }

    private String extractToken(HttpServletRequest request) {
        String bearer = request.getHeader("Authorization");
        if (bearer != null && bearer.startsWith("Bearer ")) {
            return bearer.substring(7);
        }
        return "";
    }
}
