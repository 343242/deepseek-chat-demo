package com.smart.rag.infrastructure.llm.adapter;

import com.smart.rag.infrastructure.llm.ChatCapable;
import com.smart.rag.infrastructure.llm.registry.LlmClientRegistry;
import com.smart.rag.infrastructure.llm.usage.UsageEventSink;
import com.smart.rag.infrastructure.llm.usage.UsageScene;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.same;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ChatModelAssembler 单测 — 唯一装配点：registry 候选解析 + 归因上下文绑定 + 装饰栈组装。
 */
@ExtendWith(MockitoExtension.class)
class ChatModelAssemblerTest {

    @Mock
    private LlmClientRegistry registry;

    @Mock
    private UsageEventSink sink;

    @Mock
    private ChatCapable capable;

    @Test
    @DisplayName("chatModel: 经 registry 取候选并绑定归因上下文（装饰器包装 ChatModelAdapter）")
    void chatModelBindsContext() {
        when(registry.get("candidate-a", ChatCapable.class)).thenReturn(capable);
        ChatModelAssembler assembler = new ChatModelAssembler(registry, sink);

        UsageRecordingChatModel model = assembler.chatModel(
            7L, "candidate-a", UsageScene.AGENT, "conv-1");

        assertThat(model).isNotNull();
        verify(registry, times(1)).get(eq("candidate-a"), eq(ChatCapable.class));
    }

    @Test
    @DisplayName("chatClient(4参): 装配带采集装饰器的 ChatClient")
    void chatClientAssemblesDecoratedClient() {
        when(registry.get("candidate-a", ChatCapable.class)).thenReturn(capable);
        ChatModelAssembler assembler = new ChatModelAssembler(registry, sink);

        ChatClient client = assembler.chatClient(7L, "candidate-a", UsageScene.CHAT, null);

        assertThat(client).isNotNull();
        verify(registry, times(1)).get(eq("candidate-a"), eq(ChatCapable.class));
    }

    @Test
    @DisplayName("chatClient(model): 直接包装既有装饰器实例，不二次访问 registry（护栏/采集共用单实例）")
    void chatClientWrapsExistingModelWithoutRegistryAccess() {
        when(registry.get("candidate-a", ChatCapable.class)).thenReturn(capable);
        ChatModelAssembler assembler = new ChatModelAssembler(registry, sink);

        UsageRecordingChatModel model = assembler.chatModel(
            7L, "candidate-a", UsageScene.AGENT, "conv-1");
        ChatClient client = assembler.chatClient(model);

        assertThat(client).isNotNull();
        // 仅 chatModel 那一次访问 registry；包装既有实例零额外解析
        verify(registry, times(1)).get(eq("candidate-a"), eq(ChatCapable.class));
        verify(registry, times(1)).get(same("candidate-a"), eq(ChatCapable.class));
    }
}
