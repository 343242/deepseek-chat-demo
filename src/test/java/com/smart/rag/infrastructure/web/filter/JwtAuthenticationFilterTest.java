package com.smart.rag.infrastructure.web.filter;

import com.smart.rag.infrastructure.web.auth.UserPermissionProvider;
import com.smart.rag.infrastructure.web.service.TokenCacheService;
import com.smart.rag.infrastructure.web.util.JwtTokenProvider;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * JwtAuthenticationFilter 单元测试。
 * <p>
 * 重点验证 dispatch 阶段的认证快照机制：REQUEST 阶段缓存，ASYNC/ERROR dispatch 恢复而非重验——
 * SSE 长流（文档状态流 10 分钟、评测流 1 小时）收尾时 token 可能已过 access-expiration TTL，
 * 重验会把收尾变成匿名请求，触发「响应已提交的 Access Denied」。
 */
@ExtendWith(MockitoExtension.class)
class JwtAuthenticationFilterTest {

    @Mock
    private JwtTokenProvider jwtTokenProvider;

    @Mock
    private TokenCacheService tokenCacheService;

    @Mock
    private UserPermissionProvider userPermissionProvider;

    @Mock
    private FilterChain filterChain;

    private JwtAuthenticationFilter filter;
    private MockHttpServletRequest request;
    private MockHttpServletResponse response;

    @BeforeEach
    void setUp() {
        filter = new JwtAuthenticationFilter(jwtTokenProvider, tokenCacheService, userPermissionProvider);
        request = new MockHttpServletRequest();
        request.setDispatcherType(DispatcherType.REQUEST);
        request.addHeader("Authorization", "Bearer test-token");
        response = new MockHttpServletResponse();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void stubValidToken() {
        when(jwtTokenProvider.validateToken("test-token")).thenReturn(true);
        when(jwtTokenProvider.getTokenType("test-token")).thenReturn("access");
        when(jwtTokenProvider.getUserIdFromToken("test-token")).thenReturn(1L);
        when(jwtTokenProvider.getJtiFromToken("test-token")).thenReturn("jti-1");
        when(tokenCacheService.isAccessTokenValid(1L, "jti-1")).thenReturn(true);
        when(tokenCacheService.getUserStatus(1L)).thenReturn("active");
        when(tokenCacheService.getUserPermissions(1L)).thenReturn(Set.of("chat:send"));
        when(jwtTokenProvider.getRolesFromToken("test-token")).thenReturn(null);
    }

    @Test
    @DisplayName("REQUEST 阶段验证通过后建立认证并缓存快照")
    void requestDispatch_cachesAuthentication() throws Exception {
        stubValidToken();

        filter.doFilter(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication().getPrincipal()).isEqualTo(1L);
    }

    @Test
    @DisplayName("ASYNC dispatch 恢复快照不重验 token——长流收尾不受 token 过期影响")
    void asyncDispatch_restoresCachedAuthWithoutRevalidation() throws Exception {
        stubValidToken();
        filter.doFilter(request, response, filterChain);

        // 模拟 SecurityContextHolderFilter 在 dispatch 前清空上下文
        SecurityContextHolder.clearContext();
        request.setDispatcherType(DispatcherType.ASYNC);

        filter.doFilter(request, response, filterChain);

        // token 仅在 REQUEST 阶段校验一次
        verify(jwtTokenProvider, times(1)).validateToken("test-token");
        verify(filterChain, times(2)).doFilter(request, response);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication().getPrincipal()).isEqualTo(1L);
    }

    @Test
    @DisplayName("ERROR dispatch 无快照（初始未认证路径）时回退正常 token 流程")
    void errorDispatch_withoutSnapshot_fallsBackToTokenPath() throws Exception {
        stubValidToken();
        request.setDispatcherType(DispatcherType.ERROR);

        filter.doFilter(request, response, filterChain);

        verify(jwtTokenProvider, times(1)).validateToken("test-token");
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
    }
}
