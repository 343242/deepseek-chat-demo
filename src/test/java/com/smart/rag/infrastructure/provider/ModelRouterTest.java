package com.smart.rag.infrastructure.provider;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.smart.rag.infrastructure.exception.ClientException;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ModelRouter 单元测试")
class ModelRouterTest {

    private final ModelRouter router = new ModelRouter("deepseek");

    @Nested
    @DisplayName("复合格式解析")
    class CompositeFormatTests {

        @Test
        @DisplayName("deepseek/deepseek-chat → Route(deepseek, deepseek-chat)")
        void resolve_deepseekComposite() {
            ModelRouter.Route route = router.resolve("deepseek/deepseek-chat");

            assertEquals("deepseek", route.providerId());
            assertEquals("deepseek-chat", route.modelId());
        }

        @Test
        @DisplayName("zhipu/glm-4-air → Route(zhipu, glm-4-air)")
        void resolve_zhipuComposite() {
            ModelRouter.Route route = router.resolve("zhipu/glm-4-air");

            assertEquals("zhipu", route.providerId());
            assertEquals("glm-4-air", route.modelId());
        }

        @Test
        @DisplayName("minimax/MiniMax-Text-01 → Route(minimax, MiniMax-Text-01)")
        void resolve_minimaxComposite() {
            ModelRouter.Route route = router.resolve("minimax/MiniMax-Text-01");

            assertEquals("minimax", route.providerId());
            assertEquals("MiniMax-Text-01", route.modelId());
        }

        @Test
        @DisplayName("moonshot/moonshot-v1-128k → Route(moonshot, moonshot-v1-128k)")
        void resolve_moonshotComposite() {
            ModelRouter.Route route = router.resolve("moonshot/moonshot-v1-128k");

            assertEquals("moonshot", route.providerId());
            assertEquals("moonshot-v1-128k", route.modelId());
        }
    }

    @Nested
    @DisplayName("简单格式（向后兼容）")
    class SimpleFormatTests {

        @Test
        @DisplayName("deepseek-chat → 默认 provider=deepseek")
        void resolve_simpleFormat_defaultsToDeepSeek() {
            ModelRouter.Route route = router.resolve("deepseek-chat");

            assertEquals("deepseek", route.providerId());
            assertEquals("deepseek-chat", route.modelId());
        }

        @Test
        @DisplayName("任意 modelId → 默认 provider=deepseek")
        void resolve_anyModelId_defaultsToDeepSeek() {
            ModelRouter.Route route = router.resolve("glm-4-air");

            assertEquals("deepseek", route.providerId());
            assertEquals("glm-4-air", route.modelId());
        }
    }

    @Nested
    @DisplayName("边界情况")
    class EdgeCaseTests {

        @Test
        @DisplayName("null → ClientException")
        void resolve_null_throwsException() {
            assertThrows(ClientException.class, () -> router.resolve(null));
        }

        @Test
        @DisplayName("空字符串 → ClientException")
        void resolve_blank_throwsException() {
            assertThrows(ClientException.class, () -> router.resolve(""));
        }

        @Test
        @DisplayName("纯空格 → ClientException")
        void resolve_whitespace_throwsException() {
            assertThrows(ClientException.class, () -> router.resolve("   "));
        }

        @Test
        @DisplayName("斜杠开头 (\"/model\") → 默认 provider（无 provider 部分）")
        void resolve_leadingSlash_defaultsProvider() {
            ModelRouter.Route route = router.resolve("/some-model");
            assertEquals("deepseek", route.providerId());
        }

        @Test
        @DisplayName("斜杠结尾 (\"provider/\") → 默认 provider")
        void resolve_trailingSlash_defaultsProvider() {
            ModelRouter.Route route = router.resolve("deepseek/");
            assertEquals("deepseek", route.providerId());
        }
    }

    @Nested
    @DisplayName("toCompositeId")
    class CompositeIdTests {

        @Test
        @DisplayName("Route→toCompositeId 可逆")
        void toCompositeId_roundTrip() {
            String raw = "deepseek/deepseek-chat";
            ModelRouter.Route route = router.resolve(raw);

            assertEquals(raw, route.toCompositeId());
        }

        @Test
        @DisplayName("简单格式→toCompositeId 补上 provider 前缀")
        void toCompositeId_simpleFormat() {
            ModelRouter.Route route = router.resolve("deepseek-chat");

            assertEquals("deepseek/deepseek-chat", route.toCompositeId());
        }
    }
}
