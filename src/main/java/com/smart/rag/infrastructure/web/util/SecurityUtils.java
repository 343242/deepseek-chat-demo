package com.smart.rag.infrastructure.web.util;

import com.smart.rag.infrastructure.exception.ClientException;
import com.smart.rag.infrastructure.exception.errorcode.ClientErrorCode;
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
            throw new ClientException(ClientErrorCode.UNAUTHORIZED);
        }
        return (Long) auth.getPrincipal();
    }

    /**
     * 从 Authorization header 或 Cookie 中提取 access token
     */
    public static String extractToken(HttpServletRequest request) {
        // 1. Authorization header
        String bearer = request.getHeader("Authorization");
        if (StringUtils.hasText(bearer) && bearer.startsWith("Bearer ")) {
            return bearer.substring(7);
        }
        // 2. Cookie
        jakarta.servlet.http.Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (jakarta.servlet.http.Cookie cookie : cookies) {
                if ("access_token".equals(cookie.getName())) {
                    return cookie.getValue();
                }
            }
        }
        return null;
    }
}
