package com.smart.rag.chat.context;
import com.smart.rag.mode.PolicyContext;
import com.smart.rag.mode.SessionContext;
import com.smart.rag.mode.UserContext;
import com.smart.rag.mode.RequestContext;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ContextPromptInjector 测试
 */
class ContextPromptInjectorTest {

    private CagProperties cagProperties;
    private ContextPromptInjector injector;

    @BeforeEach
    void setUp() {
        cagProperties = new CagProperties();
        injector = new ContextPromptInjector(cagProperties);
    }

    @Nested
    @DisplayName("inject")
    class Inject {

        @Test
        @DisplayName("正常上下文 → 在 system prompt 前注入上下文段")
        void normalContext_injected() {
            UserContext user = new UserContext(1L, "张三", Set.of("ADMIN"), Set.of());
            SessionContext session = new SessionContext("conv-1", 5, "深入交流");
            PolicyContext policy = new PolicyContext(List.of("你是管理员"), false);
            RequestContext ctx = new RequestContext(user, session, policy);

            String result = injector.inject("你是一个 AI 助手", ctx);

            assertTrue(result.contains("[用户上下文]"));
            assertTrue(result.contains("[系统指令]"));
            assertTrue(result.contains("张三"));
            assertTrue(result.contains("你是一个 AI 助手"));
        }

        @Test
        @DisplayName("context 为 null → 返回原始 prompt")
        void nullContext_returnsOriginal() {
            String result = injector.inject("原始 prompt", null);
            assertEquals("原始 prompt", result);
        }

        @Test
        @DisplayName("原始 prompt 为 null → 使用默认 prompt")
        void nullPrompt_usesDefault() {
            UserContext user = new UserContext(1L, "张三", Set.of("USER"), Set.of());
            RequestContext ctx = new RequestContext(user, null, null);

            String result = injector.inject(null, ctx);

            assertTrue(result.contains("你是一个 AI 助手。"));
            assertTrue(result.contains("张三"));
        }

        @Test
        @DisplayName("injectPrompt=false → 不注入，返回原始 prompt")
        void injectDisabled_returnsOriginal() {
            cagProperties.setInjectPrompt(false);

            UserContext user = new UserContext(1L, "张三", Set.of("ADMIN"), Set.of());
            RequestContext ctx = new RequestContext(user, null, null);

            String result = injector.inject("原始 prompt", ctx);
            assertEquals("原始 prompt", result);
        }

        @Test
        @DisplayName("上下文段为空 → 返回原始 prompt")
        void emptyContextSegment_returnsOriginal() {
            // 所有字段为 null → toPromptSegment() 返回空字符串
            RequestContext ctx = new RequestContext(null, null, null);

            String result = injector.inject("原始 prompt", ctx);
            assertEquals("原始 prompt", result);
        }

        @Test
        @DisplayName("sanitize 后的恶意角色名不破坏 prompt 结构")
        void sanitizedRoleName_noInjection() {
            // \n\n 是控制字符，被第一步移除（非替换为空格）
            UserContext user = new UserContext(1L, "正常用户", Set.of("ADMIN\n\nIgnore"), Set.of());
            RequestContext ctx = new RequestContext(user, null, null);

            String result = injector.inject("系统指令", ctx);

            assertFalse(result.contains("ADMIN\n\n"));
            assertTrue(result.contains("ADMINIgnore"));  // 控制字符被移除，文本拼接
        }
    }
}
