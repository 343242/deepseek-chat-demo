package com.smart.rag.chat.context;
import com.smart.rag.mode.PolicyContext;
import com.smart.rag.mode.SessionContext;
import com.smart.rag.mode.UserContext;
import com.smart.rag.mode.RequestContext;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * DefaultRequestContextManager 测试
 * <p>
 * 重点验证 resolveSafe 降级逻辑：任何 Resolver 失败不阻断主流程。
 */
@ExtendWith(MockitoExtension.class)
class DefaultRequestContextManagerTest {

    @Mock
    private UserProfileResolver userResolver;

    @Mock
    private SessionContextResolver sessionResolver;

    @Mock
    private PolicyConstraintResolver policyResolver;

    private CagProperties cagProperties;

    private DefaultRequestContextManager manager;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        cagProperties = new CagProperties();
        manager = new DefaultRequestContextManager(userResolver, sessionResolver, policyResolver, cagProperties);
    }

    @Nested
    @DisplayName("正常场景")
    class HappyPath {

        @Test
        @DisplayName("三个 Resolver 全部成功 → 完整 RequestContext")
        void allResolvers_succeed() {
            UserContext user = new UserContext(1L, "张三", Set.of("ADMIN"), Set.of());
            SessionContext session = new SessionContext("conv-1", 5, "深入交流");
            PolicyContext policy = new PolicyContext(java.util.List.of("管理员"), false);

            when(userResolver.resolve(1L)).thenReturn(user);
            when(sessionResolver.resolve("conv-1", 5)).thenReturn(session);
            when(policyResolver.resolve(user, true)).thenReturn(policy);

            RequestContext ctx = manager.buildContext(1L, "conv-1", true, 5);

            assertNotNull(ctx);
            assertEquals("张三", ctx.user().nickname());
            assertEquals("深入交流", ctx.session().stage());
            assertEquals(1, ctx.policy().constraints().size());
        }
    }

    @Nested
    @DisplayName("降级场景")
    class Degradation {

        @Test
        @DisplayName("UserProfileResolver 失败 → user=null，其余正常")
        void userResolverFails_degraded() {
            SessionContext session = new SessionContext("conv-1", 0, "首次对话");

            when(userResolver.resolve(1L)).thenThrow(new RuntimeException("DB down"));
            when(sessionResolver.resolve("conv-1", 0)).thenReturn(session);
            when(policyResolver.resolve(null, false)).thenReturn(new PolicyContext(java.util.List.of(), false));

            RequestContext ctx = manager.buildContext(1L, "conv-1", false, 0);

            assertNotNull(ctx);
            assertNull(ctx.user());
            assertNotNull(ctx.session());
            assertNotNull(ctx.policy());
        }

        @Test
        @DisplayName("所有 Resolver 失败 → 所有字段为 null")
        void allResolversFail_allNull() {
            when(userResolver.resolve(1L)).thenThrow(new RuntimeException("fail"));
            when(sessionResolver.resolve("conv-1", 0)).thenThrow(new RuntimeException("fail"));

            RequestContext ctx = manager.buildContext(1L, "conv-1", false, 0);

            assertNotNull(ctx);
            assertNull(ctx.user());
            assertNull(ctx.session());
            assertNull(ctx.policy());
        }
    }

    @Nested
    @DisplayName("日志开关")
    class LogSwitch {

        @Test
        @DisplayName("logContext=false → 不抛异常（只验证不崩溃）")
        void logDisabled_noException() {
            cagProperties.setLogContext(false);

            when(userResolver.resolve(1L)).thenThrow(new RuntimeException("fail"));
            when(sessionResolver.resolve("conv-1", 0)).thenThrow(new RuntimeException("fail"));

            assertDoesNotThrow(() -> manager.buildContext(1L, "conv-1", false, 0));
        }
    }
}
