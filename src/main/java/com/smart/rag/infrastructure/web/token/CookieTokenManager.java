package com.smart.rag.infrastructure.web.token;

import com.smart.rag.infrastructure.web.config.JwtProperties;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(CookieTokenManager.class);

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

    /**
     * 应用 SameSite 属性，并强制保证语义合法。
     * <p>
     * 按浏览器规范，{@code SameSite=None} 必须 {@code Secure}，否则 Chrome/Firefox 会静默拒绝 Set-Cookie，
     * 导致登录态丢失。此处检测到 None 时强制 {@code setSecure(true)} 兜底，避免运维遗漏 cookie-secure 配置。
     */
    private void applySameSite(Cookie cookie) {
        String sameSite = jwtProperties.cookieSameSite();
        if ("none".equalsIgnoreCase(sameSite) && !cookie.getSecure()) {
            log.warn("SameSite=None 强制开启 Secure（原配置 cookie-secure={}）—— 否则浏览器会拒绝 Set-Cookie",
                    jwtProperties.cookieSecure());
            cookie.setSecure(true);
        }
        cookie.setAttribute("SameSite", sameSite);
    }

    private Cookie buildAccessTokenCookie(String token) {
        Cookie cookie = new Cookie("access_token", token);
        cookie.setHttpOnly(true);
        cookie.setSecure(jwtProperties.cookieSecure());
        cookie.setPath("/api");
        cookie.setMaxAge((int) jwtProperties.accessExpiration());
        applySameSite(cookie);
        return cookie;
    }

    private Cookie buildRefreshTokenCookie(String token) {
        Cookie cookie = new Cookie("refresh_token", token);
        cookie.setHttpOnly(true);
        cookie.setSecure(jwtProperties.cookieSecure());
        cookie.setPath("/api/auth/refresh");
        cookie.setMaxAge((int) jwtProperties.refreshExpiration());
        applySameSite(cookie);
        return cookie;
    }

    private Cookie buildExpiredCookie(String name, String path) {
        Cookie cookie = new Cookie(name, "");
        cookie.setHttpOnly(true);
        cookie.setSecure(jwtProperties.cookieSecure());
        cookie.setPath(path);
        cookie.setMaxAge(0);
        applySameSite(cookie);
        return cookie;
    }
}
