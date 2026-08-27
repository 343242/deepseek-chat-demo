package com.smart.rag.rag.service.impl;

import com.smart.rag.infrastructure.exception.errorcode.RemoteErrorCode;
import com.smart.rag.infrastructure.exception.RemoteException;
import com.smart.rag.infrastructure.llm.ChatCapable;
import com.smart.rag.infrastructure.llm.LlmCapability;
import com.smart.rag.infrastructure.llm.registry.LlmClientRegistry;
import com.smart.rag.rag.config.RagEntityProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link EntityChatClientResolver} 单元测试：extraction-model 路由 + fail-fast（不静默回落）。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("EntityChatClientResolver — extraction-model 统一路由")
class EntityChatClientResolverTest {

    @Mock
    private LlmClientRegistry llmClientRegistry;
    @Mock
    private ChatCapable chatCapable;

    @InjectMocks
    private EntityChatClientResolver resolver;

    private RagEntityProperties props(String extractionModel) {
        return new RagEntityProperties(20, 500, 32, 0.85, 50, 20, 10, 1, 0.7, 0.5, 0.3, 0.2,
                true, extractionModel, true, 0, 0, 0, 0, null);
    }

    @Test
    @DisplayName("extraction-model 未配置 → CHAT 默认候选")
    void blankModel_usesDefault() {
        resolver = new EntityChatClientResolver(llmClientRegistry, props(""));
        when(llmClientRegistry.getDefault(LlmCapability.CHAT, ChatCapable.class)).thenReturn(chatCapable);

        assertThat(resolver.resolve()).isSameAs(chatCapable);
        verify(llmClientRegistry, never()).get(eq("any"), eq(ChatCapable.class));
    }

    @Test
    @DisplayName("extraction-model 已配置 → get(candidateId)（查询/索引两侧同模型）")
    void configuredModel_usesGetById() {
        resolver = new EntityChatClientResolver(llmClientRegistry, props("deepseek-v4-flash"));
        when(llmClientRegistry.get(eq("deepseek-v4-flash"), eq(ChatCapable.class))).thenReturn(chatCapable);

        assertThat(resolver.resolve()).isSameAs(chatCapable);
        verify(llmClientRegistry, never()).getDefault(LlmCapability.CHAT, ChatCapable.class);
    }

    @Test
    @DisplayName("配置了无效候选 ID → RemoteException 向上抛，不静默回落默认候选（llm-spi fail-fast）")
    void invalidModel_failsFast_noSilentFallback() {
        resolver = new EntityChatClientResolver(llmClientRegistry, props("no-such-model"));
        when(llmClientRegistry.get(eq("no-such-model"), eq(ChatCapable.class)))
                .thenThrow(new RemoteException(RemoteErrorCode.LLM_CONFIG_ERROR, "候选 ID 无效"));

        assertThatThrownBy(() -> resolver.resolve())
                .isInstanceOf(RemoteException.class);
        verify(llmClientRegistry, never()).getDefault(LlmCapability.CHAT, ChatCapable.class);
    }
}
