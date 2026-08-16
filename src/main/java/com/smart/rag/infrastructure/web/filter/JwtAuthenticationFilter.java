package com.smart.rag.infrastructure.web.filter;

import com.smart.rag.infrastructure.web.auth.UserPermissionProvider;
import com.smart.rag.infrastructure.web.service.TokenCacheService;
import com.smart.rag.infrastructure.web.util.JwtTokenProvider;
import com.smart.rag.infrastructure.web.util.SecurityUtils;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);

    /**
     * REQUEST 阶段验证通过的认证快照（request attribute 键）。
     * <p>
     * SSE 流式请求（SseEmitter/Flux）在 emitter 收尾时会以 ASYNC/ERROR dispatch 重走过滤器链。
     * 若收尾时刻按 token 当前时效重新鉴权，流存活超过 access-expiration TTL 后
     * （文档状态流 10 分钟、评测流 1 小时，均可能跨过 15 分钟 token 寿命），
     * 收尾会变成匿名请求 → 「响应已提交的 Access Denied」。请求开始时已鉴权通过，
     * 其生命周期内身份不应被重新审判——dispatch 阶段恢复快照而非重验。
     */
    private static final String CACHED_AUTH_ATTRIBUTE =
        JwtAuthenticationFilter.class.getName() + ".cachedAuth";

    /**
     * 允许在 ASYNC dispatch 时重新认证。
     * <p>
     * Flux SSE 响应完成后 Spring MVC 会发起 async dispatch，
     * 此时 SecurityContext 已被 SecurityContextHolderFilter 清空。
     * 必须重新从请求中提取 JWT 并设置认证信息，否则会触发 Access Denied。
     */
    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }

    private final JwtTokenProvider jwtTokenProvider;
    private final TokenCacheService tokenCacheService;
    private final UserPermissionProvider userPermissionProvider;

    public JwtAuthenticationFilter(JwtTokenProvider jwtTokenProvider,
                                    TokenCacheService tokenCacheService,
                                    UserPermissionProvider userPermissionProvider) {
        this.jwtTokenProvider = jwtTokenProvider;
        this.tokenCacheService = tokenCacheService;
        this.userPermissionProvider = userPermissionProvider;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {
        // ASYNC/ERROR dispatch：恢复 REQUEST 阶段的认证快照，不重验 token（见 CACHED_AUTH_ATTRIBUTE 注释）
        if (request.getDispatcherType() != DispatcherType.REQUEST
            && request.getAttribute(CACHED_AUTH_ATTRIBUTE) instanceof UsernamePasswordAuthenticationToken cached) {
            SecurityContextHolder.getContext().setAuthentication(cached);
            filterChain.doFilter(request, response);
            return;
        }

        String token = SecurityUtils.extractToken(request);

        // 诊断留痕：各拒绝分支记录 DispatcherType + URI。SSE 流式请求在 emitter 收尾时以 ASYNC/ERROR
        // dispatch 重走本过滤器，若此时 token 已过 access-expiration TTL，重认证会静默降级为匿名，
        // 下游只剩「响应已提交的 Access Denied」，无法定位原始请求。
        // 注意 validateToken 吞掉异常，过期与签名非法在此不可区分。
        if (token == null) {
            filterChain.doFilter(request, response);
            return;
        }

        if (!jwtTokenProvider.validateToken(token)) {
            log.debug("JWT rejected: {} {} — validation failed (expired or invalid), continuing as anonymous",
                request.getDispatcherType(), request.getRequestURI());
            filterChain.doFilter(request, response);
            return;
        }

        try {
            // Only process access tokens
            String tokenType = jwtTokenProvider.getTokenType(token);
            if (!"access".equals(tokenType)) {
                log.debug("JWT rejected: {} {} — token type '{}', not 'access'",
                    request.getDispatcherType(), request.getRequestURI(), tokenType);
                filterChain.doFilter(request, response);
                return;
            }

            Long userId = jwtTokenProvider.getUserIdFromToken(token);

            // Check token revocation using jti (P2-11: stable ID)
            String tokenId = jwtTokenProvider.getJtiFromToken(token);
            if (tokenId == null || !tokenCacheService.isAccessTokenValid(userId, tokenId)) {
                log.debug("JWT rejected: {} {} — jti not in Redis (revoked/TTL lapsed/flushed), userId={}, jti={}",
                    request.getDispatcherType(), request.getRequestURI(), userId, tokenId);
                filterChain.doFilter(request, response);
                return;
            }

            // Check user status
            String userStatus = tokenCacheService.getUserStatus(userId);
            if ("disabled".equals(userStatus) || "deleted".equals(userStatus)) {
                log.debug("JWT rejected: {} {} — user {} is {}",
                    request.getDispatcherType(), request.getRequestURI(), userId, userStatus);
                filterChain.doFilter(request, response);
                return;
            }

            // Get permissions (prefer Redis cache)
            Set<String> permissions = tokenCacheService.getUserPermissions(userId);
            if (permissions == null) {
                permissions = userPermissionProvider.loadUserPermissions(userId);
            }

            // Build authorities from permissions
            List<SimpleGrantedAuthority> authorities = permissions.stream()
                .map(SimpleGrantedAuthority::new)
                .collect(Collectors.toCollection(ArrayList::new));

            // Add ROLE_ prefixed role authorities
            List<String> roles = jwtTokenProvider.getRolesFromToken(token);
            if (roles != null) {
                roles.forEach(role -> authorities.add(new SimpleGrantedAuthority("ROLE_" + role)));
            }

            UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(userId, null, authorities);
            SecurityContextHolder.getContext().setAuthentication(auth);
            request.setAttribute(CACHED_AUTH_ATTRIBUTE, auth);
        } catch (Exception e) {
            // Log for operational visibility; do NOT set authentication on failure
            if (e instanceof io.jsonwebtoken.ExpiredJwtException) {
                log.debug("JWT expired: {}", e.getMessage());
            } else {
                log.warn("JWT authentication failed: {}", e.getMessage());
            }
        }

        filterChain.doFilter(request, response);
    }
}
