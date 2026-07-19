package com.smart.rag.infrastructure.web.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * JWT 与认证 Cookie 相关配置（前缀 {@code app.jwt}）。
 *
 * @param secret           JWT 签名密钥
 * @param accessExpiration access token 有效期（秒）
 * @param refreshExpiration refresh token 有效期（秒）
 * @param issuer           JWT issuer claim
 * @param redisPrefix      token 缓存的 Redis key 前缀
 * @param cookieSecure     Cookie 是否标记 Secure（dev=false，stable/prod 应为 true）
 * @param cookieSameSite   Cookie 的 SameSite 属性（Lax / Strict / None）。
 *                         <ul>
 *                           <li>{@code Lax}（默认）：同源 + 顶层导航 GET 带 Cookie，跨站 POST 不带 —— 适配同源/反代部署。</li>
 *                           <li>{@code None}：跨站请求带 Cookie —— 前后端跨域部署时必须，但按浏览器规范必须配 Secure。
 *                               {@code CookieTokenManager} 会在检测到 {@code None} 时强制 Secure=true 兜底。</li>
 *                           <li>{@code Strict}：仅同源带 Cookie —— 最严，但会破坏 OAuth/SSO 跳转回链，慎用。</li>
 *                         </ul>
 */
@ConfigurationProperties(prefix = "app.jwt")
public record JwtProperties(
    String secret,
    long accessExpiration,
    long refreshExpiration,
    String issuer,
    String redisPrefix,
    boolean cookieSecure,
    String cookieSameSite
) {
    /** 默认 SameSite，兼容历史行为（无配置时维持 Lax）。 */
    private static final String DEFAULT_SAME_SITE = "Lax";

    /** 紧凑构造器：兜底 null/空白 cookieSameSite，避免 Cookie.setAttribute 抛错。 */
    public JwtProperties {
        if (cookieSameSite == null || cookieSameSite.isBlank()) {
            cookieSameSite = DEFAULT_SAME_SITE;
        }
    }

    /** 开发环境默认 false，生产环境应设为 true */
    public boolean cookieSecure() {
        return cookieSecure;
    }

    /** SameSite 属性值（保证非空，Lax/Strict/None）。 */
    public String cookieSameSite() {
        return cookieSameSite;
    }
}
