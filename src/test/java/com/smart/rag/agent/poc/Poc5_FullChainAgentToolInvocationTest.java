package com.smart.rag.agent.poc;

import com.smart.rag.infrastructure.llm.ChatCapable;
import com.smart.rag.infrastructure.llm.LlmResponse;
import com.smart.rag.infrastructure.llm.adapter.ChatModelAdapter;
import com.smart.rag.infrastructure.llm.adapter.UsageRecordingChatModel;
import com.smart.rag.infrastructure.llm.usage.UsageContext;
import com.smart.rag.infrastructure.llm.usage.UsageScene;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.ToolCallAdvisor;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.model.tool.DefaultToolCallingManager;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.ai.tool.resolution.StaticToolCallbackResolver;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("PoC 5 full-chain agent tool invocation (post-fix)")
class Poc5_FullChainAgentToolInvocationTest {
    @Mock private ChatCapable delegate;

    @Test
    @DisplayName("full agent path: tool callback FIRES via ChatModelAdapter (post-fix)")
    void toolFiresThroughAdapter() {
        boolean[] fired = {false};
        var cb = FunctionToolCallback.builder("search", (Map input, ToolContext ctx) -> { fired[0] = true; return "RESULT"; }).description("search").inputType(Map.class).build();
        ToolCallback[] callbacks = { cb };

        AtomicInteger count = new AtomicInteger();
        when(delegate.chat(any())).thenAnswer(inv -> {
            if (count.incrementAndGet() == 1) {
                return new LlmResponse("", false, null, List.of(new LlmResponse.ToolCall("call_1", "search", "{}")), Map.of());
            }
            return new LlmResponse("final answer after tool", false, null, List.of(), Map.of());
        });

        UsageRecordingChatModel model = new UsageRecordingChatModel(new ChatModelAdapter(delegate),
            new UsageContext(1L, "poc-agent", UsageScene.AGENT, "conv"), sample -> { });
        DefaultToolCallingManager mgr = DefaultToolCallingManager.builder().toolCallbackResolver(new StaticToolCallbackResolver(List.of(callbacks))).build();
        ToolCallAdvisor advisor = ToolCallAdvisor.builder().toolCallingManager(mgr).advisorOrder(2).build();
        ChatResponse resp = ChatClient.builder(model).build().prompt().user("find X").advisors(advisor).options(ToolCallingChatOptions.builder().toolCallbacks(callbacks).build()).call().chatResponse();

        String content = resp == null ? "null" : resp.getResult().getOutput().getText();
        System.out.println("POC_RESULT fullchain toolFired=" + fired[0] + " modelCalls=" + count.get() + " content=" + content);
        assertThat(fired[0]).as("post-fix: tool should fire through ChatModelAdapter").isTrue();
    }
}
