package com.smart.rag.infrastructure.web.token;

import com.smart.rag.infrastructure.web.config.JwtProperties;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;

/**
 * Cookie Token 管理器 — 负责 access_token / refresh_token 的 Cookie 读写
 *
 * <p>职责单一：只管 Cookie 的创建、解析、清除，不涉及任何业务逻辑。</p>
 *
 * <p>遵循 SRP：从 AuthController 中抽取的 Cookie 操作封装。</p>
 */
@Component
public class CookieTokenManager {

    private final JwtProperties jwtProperties;

    public CookieTokenManager(JwtProperties jwtProperties) {
        this.jwtProperties = jwtProperties;
    }

    /**
     * 将 access_token 和 refresh_token 写入 Cookie
     */
    public void setTokenCookies(HttpServletResponse response, String accessToken, String refreshToken) {
        response.addCookie(buildAccessTokenCookie(accessToken));
        response.addCookie(buildRefreshTokenCookie(refreshToken));
    }

    /**
     * 清除 access_token 和 refresh_token Cookie
     */
    public void clearTokenCookies(HttpServletResponse response) {
        response.addCookie(buildExpiredCookie("access_token", "/api"));
        response.addCookie(buildExpiredCookie("refresh_token", "/api/auth/refresh"));
    }

    /**
     * 从 Cookie 中提取 refresh_token
     *
     * @return refresh_token 值，不存在返回 null
     */
    public String extractRefreshToken(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return null;
        for (Cookie c : cookies) {
            if ("refresh_token".equals(c.getName())) {
                return c.getValue();
            }
        }
        return null;
    }

    // ==================== Private ====================

    private Cookie buildAccessTokenCookie(String token) {
        Cookie cookie = new Cookie("access_token", token);
        cookie.setHttpOnly(true);
        cookie.setSecure(jwtProperties.cookieSecure());
        cookie.setPath("/api");
        cookie.setMaxAge((int) jwtProperties.accessExpiration());
        cookie.setAttribute("SameSite", "Lax");
        return cookie;
    }

    private Cookie buildRefreshTokenCookie(String token) {
        Cookie cookie = new Cookie("refresh_token", token);
        cookie.setHttpOnly(true);
        cookie.setSecure(jwtProperties.cookieSecure());
        cookie.setPath("/api/auth/refresh");
        cookie.setMaxAge((int) jwtProperties.refreshExpiration());
        cookie.setAttribute("SameSite", "Lax");
        return cookie;
    }

    private Cookie buildExpiredCookie(String name, String path) {
        Cookie cookie = new Cookie(name, "");
        cookie.setHttpOnly(true);
        cookie.setSecure(jwtProperties.cookieSecure());
        cookie.setPath(path);
        cookie.setMaxAge(0);
        cookie.setAttribute("SameSite", "Lax");
        return cookie;
    }
}
