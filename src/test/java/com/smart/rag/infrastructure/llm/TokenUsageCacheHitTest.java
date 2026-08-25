package com.smart.rag.infrastructure.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smart.rag.infrastructure.llm.client.protocol.OpenAiCompatibleChatProtocol;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TokenUsage cache hit 解析单测（Phase 10.12）。
 * <p>
 * 验证 {@code OpenAiCompatibleChatProtocol.parseTokenUsage} 正确读取 DeepSeek
 * {@code prompt_cache_hit_tokens} 与百炼 {@code prompt_tokens_details.cached_tokens}，
 * 以及 {@link LlmResponse.TokenUsage} 新字段 / 3-arg 兼容构造器。
 */
class TokenUsageCacheHitTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 反射调用 private static parseTokenUsage（无状态方法，可静态调用） */
    private static LlmResponse.TokenUsage parse(JsonNode usage) throws Exception {
        Method m = OpenAiCompatibleChatProtocol.class.getDeclaredMethod("parseTokenUsage", JsonNode.class);
        m.setAccessible(true);
        return (LlmResponse.TokenUsage) m.invoke(null, usage);
    }

    @Test
    @DisplayName("DeepSeek: 读取 prompt_cache_hit_tokens")
    void parsesDeepSeekCacheHitTokens() throws Exception {
        JsonNode usage = MAPPER.readTree("""
            {"prompt_tokens": 1000, "completion_tokens": 200, "total_tokens": 1200,
             "prompt_cache_hit_tokens": 800}
            """);
        LlmResponse.TokenUsage tu = parse(usage);

        assertThat(tu.promptTokens()).isEqualTo(1000);
        assertThat(tu.completionTokens()).isEqualTo(200);
        assertThat(tu.totalTokens()).isEqualTo(1200);
        assertThat(tu.cacheHitTokens()).isEqualTo(800);
    }

    @Test
    @DisplayName("百炼/Qwen: 读取 prompt_tokens_details.cached_tokens")
    void parsesBailianCachedTokens() throws Exception {
        JsonNode usage = MAPPER.readTree("""
            {"prompt_tokens": 500, "completion_tokens": 50, "total_tokens": 550,
             "prompt_tokens_details": {"cached_tokens": 300}}
            """);
        assertThat(parse(usage).cacheHitTokens()).isEqualTo(300);
    }

    @Test
    @DisplayName("无缓存字段时 cacheHitTokens 为 null")
    void noCacheField_returnsNull() throws Exception {
        JsonNode usage = MAPPER.readTree("""
            {"prompt_tokens": 100, "completion_tokens": 10, "total_tokens": 110}
            """);
        assertThat(parse(usage).cacheHitTokens()).isNull();
    }

    @Test
    @DisplayName("两者都有时 DeepSeek 优先（先检查）")
    void deepSeekTakesPrecedence() throws Exception {
        JsonNode usage = MAPPER.readTree("""
            {"prompt_tokens": 100, "completion_tokens": 10, "total_tokens": 110,
             "prompt_cache_hit_tokens": 50,
             "prompt_tokens_details": {"cached_tokens": 99}}
            """);
        assertThat(parse(usage).cacheHitTokens()).isEqualTo(50);
    }

    @Test
    @DisplayName("3-arg 兼容构造器：cacheHitTokens 默认 null")
    void threeArgConstructor_defaultsNull() {
        LlmResponse.TokenUsage tu = new LlmResponse.TokenUsage(10, 20, 30);
        assertThat(tu.promptTokens()).isEqualTo(10);
        assertThat(tu.totalTokens()).isEqualTo(30);
        assertThat(tu.cacheHitTokens()).isNull();
    }

    @Test
    @DisplayName("4-arg 构造器：cacheHitTokens 可显式设置")
    void fourArgConstructor_setsCacheHit() {
        LlmResponse.TokenUsage tu = new LlmResponse.TokenUsage(10, 20, 30, 5);
        assertThat(tu.cacheHitTokens()).isEqualTo(5);
    }
}
