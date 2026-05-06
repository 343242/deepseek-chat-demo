package com.demo.deepseekchat.security.util;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.StringUtils;

/**
 * 安全工具类 — 从 SecurityContext / HttpServletRequest 获取当前用户信息
 */
public final class SecurityUtils {

    private SecurityUtils() {}

    /**
     * 从 SecurityContext 获取当前认证用户的 ID
     */
    public static Long getCurrentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getPrincipal() == null) {
            throw new IllegalStateException("未认证");
        }
        return (Long) auth.getPrincipal();
    }

    /**
     * 从 Authorization header 提取 Bearer token
     */
    public static String extractToken(HttpServletRequest request) {
        String bearer = request.getHeader("Authorization");
        if (StringUtils.hasText(bearer) && bearer.startsWith("Bearer ")) {
            return bearer.substring(7);
        }
        return null;
    }
}
