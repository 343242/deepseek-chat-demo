package com.demo.chat.chat.context;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DefaultPolicyConstraintResolver 测试
 */
class DefaultPolicyConstraintResolverTest {

    private final DefaultPolicyConstraintResolver resolver = new DefaultPolicyConstraintResolver();

    @Nested
    @DisplayName("resolve")
    class Resolve {

        @Test
        @DisplayName("ADMIN 角色 → 可访问所有信息")
        void adminRole_fullAccess() {
            UserContext admin = new UserContext(1L, "管理员", java.util.Set.of("ADMIN"), java.util.Set.of());
            var result = resolver.resolve(admin, false);

            assertTrue(result.constraints().stream()
                    .anyMatch(c -> c.contains("管理员")));
            assertFalse(result.ragRestricted());
        }

        @Test
        @DisplayName("普通角色 → 仅访问有权文档")
        void normalRole_restricted() {
            UserContext user = new UserContext(2L, "用户", java.util.Set.of("USER"), java.util.Set.of());
            var result = resolver.resolve(user, false);

            assertTrue(result.constraints().stream()
                    .anyMatch(c -> c.contains("有权访问")));
        }

        @Test
        @DisplayName("ragEnabled=true → 添加知识库约束")
        void ragEnabled_addsConstraint() {
            UserContext user = new UserContext(1L, "管理员", java.util.Set.of("ADMIN"), java.util.Set.of());
            var result = resolver.resolve(user, true);

            assertTrue(result.constraints().stream()
                    .anyMatch(c -> c.contains("知识库")));
        }

        @Test
        @DisplayName("ragEnabled=false → 不添加知识库约束")
        void ragDisabled_noKbConstraint() {
            UserContext user = new UserContext(1L, "管理员", java.util.Set.of("ADMIN"), java.util.Set.of());
            var result = resolver.resolve(user, false);

            assertFalse(result.constraints().stream()
                    .anyMatch(c -> c.contains("知识库")));
        }

        @Test
        @DisplayName("user 为 null → 空约束")
        void nullUser_emptyConstraints() {
            var result = resolver.resolve(null, true);

            assertTrue(result.constraints().isEmpty());
            assertFalse(result.ragRestricted());
        }

        @Test
        @DisplayName("ADMIN + ragEnabled → 两条约束")
        void adminWithRag_twoConstraints() {
            UserContext admin = new UserContext(1L, "管理员", java.util.Set.of("ADMIN"), java.util.Set.of());
            var result = resolver.resolve(admin, true);

            assertEquals(2, result.constraints().size());
        }
    }
}
