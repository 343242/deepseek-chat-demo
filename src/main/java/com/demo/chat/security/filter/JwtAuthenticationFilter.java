package com.demo.chat.security.filter;

import com.demo.chat.security.service.TokenCacheService;
import com.demo.chat.security.util.JwtTokenProvider;
import com.demo.chat.security.util.SecurityUtils;
import com.demo.chat.user.service.AuthService;
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

    private final JwtTokenProvider jwtTokenProvider;
    private final TokenCacheService tokenCacheService;
    private final AuthService authService;

    public JwtAuthenticationFilter(JwtTokenProvider jwtTokenProvider,
                                    TokenCacheService tokenCacheService,
                                    AuthService authService) {
        this.jwtTokenProvider = jwtTokenProvider;
        this.tokenCacheService = tokenCacheService;
        this.authService = authService;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {
        String token = SecurityUtils.extractToken(request);

        if (token != null && jwtTokenProvider.validateToken(token)) {
            try {
                // Only process access tokens
                if (!"access".equals(jwtTokenProvider.getTokenType(token))) {
                    filterChain.doFilter(request, response);
                    return;
                }

                Long userId = jwtTokenProvider.getUserIdFromToken(token);

                // Check token revocation using jti (P2-11: stable ID)
                String tokenId = jwtTokenProvider.getJtiFromToken(token);
                if (tokenId == null || !tokenCacheService.isAccessTokenValid(userId, tokenId)) {
                    filterChain.doFilter(request, response);
                    return;
                }

                // Check user status
                String userStatus = tokenCacheService.getUserStatus(userId);
                if ("disabled".equals(userStatus) || "deleted".equals(userStatus)) {
                    filterChain.doFilter(request, response);
                    return;
                }

                // Get permissions (prefer Redis cache)
                Set<String> permissions = tokenCacheService.getUserPermissions(userId);
                if (permissions == null) {
                    permissions = authService.loadUserPermissions(userId);
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
            } catch (Exception e) {
                // Log for operational visibility; do NOT set authentication on failure
                if (e instanceof io.jsonwebtoken.ExpiredJwtException) {
                    log.debug("JWT expired: {}", e.getMessage());
                } else {
                    log.warn("JWT authentication failed: {}", e.getMessage());
                }
            }
        }

        filterChain.doFilter(request, response);
    }
}
