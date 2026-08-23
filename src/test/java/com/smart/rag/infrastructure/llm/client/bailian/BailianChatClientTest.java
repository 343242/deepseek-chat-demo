package com.smart.rag.infrastructure.llm.client.bailian;

import com.alibaba.dashscope.aigc.generation.Generation;
import com.alibaba.dashscope.aigc.generation.GenerationParam;
import com.alibaba.dashscope.aigc.generation.GenerationOutput;
import com.alibaba.dashscope.aigc.generation.GenerationResult;
import com.alibaba.dashscope.aigc.generation.GenerationUsage;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversation;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversationOutput;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversationParam;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversationResult;
import com.alibaba.dashscope.common.Message;
import com.alibaba.dashscope.common.MultiModalMessage;
import com.alibaba.dashscope.common.Status;
import com.alibaba.dashscope.exception.ApiException;
import com.alibaba.dashscope.exception.NoApiKeyException;
import com.alibaba.dashscope.tools.ToolCallFunction;
import com.alibaba.dashscope.tools.ToolFunction;
import com.smart.rag.infrastructure.exception.RemoteException;
import com.smart.rag.infrastructure.exception.errorcode.RemoteErrorCode;
import com.smart.rag.infrastructure.llm.ChatCandidate;
import com.smart.rag.infrastructure.llm.ChatRequest;
import com.smart.rag.infrastructure.llm.ChatTool;
import com.smart.rag.infrastructure.llm.LlmResponse;
import com.smart.rag.infrastructure.llm.MessageInformation;
import com.smart.rag.infrastructure.llm.StreamChunk;
import com.smart.rag.infrastructure.llm.ThinkingConfig;
import io.reactivex.Flowable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * BailianChatClient 单元测试（设计 §4.6）
 * <p>
 * 覆盖：路由解析、参数映射（messages/thinking/tools/responseFormat/resultFormat）、
 * history 工具消息转换、流式轮末汇总包契约（toolCall 增量合并 / reasoning 累计 / 流末 usage 收口）、
 * usage cached_tokens 映射、错误分类。桩注入 SDK facade（Mockito inline 可 mock final 类）。
 */
@DisplayName("BailianChatClient 单元测试")
class BailianChatClientTest {

    private static ChatCandidate candidate(String model, Map<String, Object> params) {
        ChatCandidate c = new ChatCandidate();
        c.setId(model);
        c.setProvider("bailian");
        c.setModel(model);
        c.setPriority(1);
        c.setParams(params);
        c.setSupportsStreaming(true);
        return c;
    }

    private static ToolCallFunction toolCallFragment(int index, String id, String name, String args) {
        ToolCallFunction f = new ToolCallFunction();
        f.setIndex(index);
        if (id != null) f.setId(id);
        f.setType("function");
        ToolCallFunction.CallFunction fn = f.new CallFunction();
        if (name != null) fn.setName(name);
        if (args != null) fn.setArguments(args);
        f.setFunction(fn);
        return f;
    }

    // ======================== 路由解析 ========================

    @Nested
    @DisplayName("resolveRoute — 模型族路由（P0 实测矩阵）")
    class ResolveRoute {

        @Test
        @DisplayName("次版本 ≥3.7 的 qwen 判 MULTIMODAL（qwen3.7-plus/qwen3.8-max）")
        void versionedMultimodal() {
            assertThat(BailianChatClient.resolveRoute(Map.of(), "qwen3.7-plus"))
                .isEqualTo(BailianChatClient.ChatRoute.MULTIMODAL);
            assertThat(BailianChatClient.resolveRoute(Map.of(), "qwen3.8-max"))
                .isEqualTo(BailianChatClient.ChatRoute.MULTIMODAL);
        }

        @Test
        @DisplayName("次版本 <3.7 或无次版本判 TEXT（qwen3-max/qwen-plus-latest）")
        void versionedText() {
            assertThat(BailianChatClient.resolveRoute(Map.of(), "qwen3-max"))
                .isEqualTo(BailianChatClient.ChatRoute.TEXT);
            assertThat(BailianChatClient.resolveRoute(Map.of(), "qwen-plus-latest"))
                .isEqualTo(BailianChatClient.ChatRoute.TEXT);
            assertThat(BailianChatClient.resolveRoute(Map.of(), "qwen3.5-plus"))
                .isEqualTo(BailianChatClient.ChatRoute.TEXT);
        }

        @Test
        @DisplayName("-vl 模型判 MULTIMODAL")
        void visionLanguage() {
            assertThat(BailianChatClient.resolveRoute(Map.of(), "qwen3-vl-plus"))
                .isEqualTo(BailianChatClient.ChatRoute.MULTIMODAL);
        }

        @Test
        @DisplayName("params.route 显式声明优先于模型名推断")
        void explicitOverride() {
            assertThat(BailianChatClient.resolveRoute(Map.of("route", "multimodal"), "qwen3-max"))
                .isEqualTo(BailianChatClient.ChatRoute.MULTIMODAL);
            assertThat(BailianChatClient.resolveRoute(Map.of("route", "text"), "qwen3.7-plus"))
                .isEqualTo(BailianChatClient.ChatRoute.TEXT);
        }
    }

    // ======================== 参数映射（TEXT 路由） ========================

    @Nested
    @DisplayName("buildGenerationParam — TEXT 路由参数映射")
    class BuildTextParam {

        private BailianChatClient client() {
            return new BailianChatClient("http://localhost:1/api/v1", "test-key",
                candidate("qwen3-max", Map.of()), mock(Generation.class), null);
        }

        @Test
        @DisplayName("system/history/input → messages；resultFormat 固定 message")
        void messageShape() {
            ChatRequest request = ChatRequest.builder("你好")
                .systemPrompt("你是助手")
                .history(List.of(
                    MessageInformation.user("早上好"),
                    MessageInformation.assistant("你好！")))
                .build();
            GenerationParam param = client().buildGenerationParam(request, false);

            List<Message> messages = param.getMessages();
            assertThat(messages).hasSize(4);
            assertThat(messages.get(0).getRole()).isEqualTo("system");
            assertThat(messages.get(0).getContent()).isEqualTo("你是助手");
            assertThat(messages.get(1).getRole()).isEqualTo("user");
            assertThat(messages.get(2).getRole()).isEqualTo("assistant");
            assertThat(messages.get(3).getContent()).isEqualTo("你好");
            assertThat(param.getResultFormat()).isEqualTo(GenerationParam.ResultFormat.MESSAGE);
        }

        @Test
        @DisplayName("history 工具消息：assistant.tool_calls 元数据 + tool 结果 → SDK 形状")
        void historyToolMessages() {
            Map<String, Object> toolCallMeta = Map.of(
                "id", "call_1", "type", "function",
                "function", Map.of("name", "get_weather", "arguments", "{\"city\":\"北京\"}"));
            ChatRequest request = ChatRequest.builder("天气如何")
                .history(List.of(
                    MessageInformation.user("北京天气"),
                    MessageInformation.assistant("", Map.of("tool_calls", List.of(toolCallMeta))),
                    MessageInformation.tool("call_1", "{\"temp\":25}")))
                .build();
            List<Message> messages = client().buildGenerationParam(request, false).getMessages();

            Message assistant = messages.get(1);
            assertThat(assistant.getRole()).isEqualTo("assistant");
            assertThat(assistant.getToolCalls()).hasSize(1);
            ToolCallFunction tc = (ToolCallFunction) assistant.getToolCalls().get(0);
            assertThat(tc.getId()).isEqualTo("call_1");
            assertThat(tc.getFunction().getName()).isEqualTo("get_weather");
            assertThat(tc.getFunction().getArguments()).isEqualTo("{\"city\":\"北京\"}");
            // Qwen 原生协议不要求回传 reasoning_content（DeepSeek quirk 不迁移）
            assertThat(assistant.getReasoningContent()).isNull();

            Message tool = messages.get(2);
            assertThat(tool.getRole()).isEqualTo("tool");
            assertThat(tool.getToolCallId()).isEqualTo("call_1");
            assertThat(tool.getContent()).isEqualTo("{\"temp\":25}");
        }

        @Test
        @DisplayName("thinking：per-request 覆盖映射为 enableThinking + thinkingBudget")
        void thinkingMapping() {
            ChatRequest request = ChatRequest.builder("hi")
                .thinking(ThinkingConfig.budgeted(2048))
                .build();
            GenerationParam param = client().buildGenerationParam(request, false);
            assertThat(param.getEnableThinking()).isTrue();
            assertThat(param.getThinkingBudget()).isEqualTo(2048);
        }

        @Test
        @DisplayName("thinking：候选 params 默认在 per-request 缺省时生效")
        void thinkingDefaultFromCandidate() {
            BailianChatClient c = new BailianChatClient("http://localhost:1/api/v1", "test-key",
                candidate("qwen3-max", Map.of("thinking",
                    Map.of("dialect", "budget", "enabled", true, "thinking-budget", 16000))),
                mock(Generation.class), null);
            GenerationParam param = c.buildGenerationParam(ChatRequest.of("hi"), false);
            assertThat(param.getEnableThinking()).isTrue();
            assertThat(param.getThinkingBudget()).isEqualTo(16000);
        }

        @Test
        @DisplayName("thinking：两者皆无时不注入（字段为 null）")
        void thinkingAbsent() {
            GenerationParam param = client().buildGenerationParam(ChatRequest.of("hi"), false);
            assertThat(param.getEnableThinking()).isNull();
            assertThat(param.getThinkingBudget()).isNull();
        }

        @Test
        @DisplayName("tools：ChatTool → ToolFunction（schema 字符串 → JsonObject）")
        void toolsMapping() {
            ChatRequest request = ChatRequest.builder("天气")
                .tools(List.of(new ChatTool("get_weather", "查天气",
                    "{\"type\":\"object\",\"properties\":{\"city\":{\"type\":\"string\"}}}")))
                .build();
            GenerationParam param = client().buildGenerationParam(request, false);
            assertThat(param.getTools()).hasSize(1);
            ToolFunction tf = (ToolFunction) param.getTools().get(0);
            assertThat(tf.getFunction().getName()).isEqualTo("get_weather");
            assertThat(tf.getFunction().getDescription()).isEqualTo("查天气");
            assertThat(tf.getFunction().getParameters().get("type").getAsString()).isEqualTo("object");
        }

        @Test
        @DisplayName("response_format extraParam → SDK ResponseFormat（决策 6 双路径覆盖）")
        void responseFormatMapping() {
            ChatRequest request = ChatRequest.builder("hi")
                .extraParams(Map.of("response_format", Map.of("type", "json_object")))
                .build();
            GenerationParam param = client().buildGenerationParam(request, false);
            assertThat(param.getResponseFormat()).isNotNull();
            assertThat(String.valueOf(param.getResponseFormat().getType())).isEqualTo("json_object");
        }

        @Test
        @DisplayName("流式参数：incrementalOutput=true 显式声明；阻塞路径不设")
        void incrementalOutput() {
            BailianChatClient c = client();
            assertThat(c.buildGenerationParam(ChatRequest.of("hi"), true).getIncrementalOutput()).isTrue();
            assertThat(c.buildGenerationParam(ChatRequest.of("hi"), false).getIncrementalOutput()).isNull();
        }

        @Test
        @DisplayName("采样参数映射：temperature/maxTokens/topP")
        void samplingParams() {
            ChatRequest request = ChatRequest.builder("hi")
                .temperature(0.7).maxTokens(1024).topP(0.9).build();
            GenerationParam param = client().buildGenerationParam(request, false);
            assertThat(param.getTemperature()).isEqualTo(0.7f);
            assertThat(param.getMaxTokens()).isEqualTo(1024);
            assertThat(param.getTopP()).isEqualTo(0.9);
        }
    }

    // ======================== 参数映射（MULTIMODAL 路由） ========================

    @Nested
    @DisplayName("buildMultimodalParam — MULTIMODAL 路由参数映射")
    class BuildMultimodalParam {

        private BailianChatClient client() {
            return new BailianChatClient("http://localhost:1/api/v1", "test-key",
                candidate("qwen3.7-plus", Map.of()), null, mock(MultiModalConversation.class));
        }

        @Test
        @DisplayName("纯文本用法：content 为 [{text:...}] 数组")
        void textOnlyContent() {
            ChatRequest request = ChatRequest.builder("你好")
                .systemPrompt("你是助手")
                .build();
            List<Object> messages = client().buildMultimodalParam(request, false).getMessages();
            assertThat(messages).hasSize(2);
            MultiModalMessage sys = (MultiModalMessage) messages.get(0);
            assertThat(sys.getRole()).isEqualTo("system");
            assertThat(sys.getContent()).isEqualTo(List.of(Map.of("text", "你是助手")));
            MultiModalMessage user = (MultiModalMessage) messages.get(1);
            assertThat(user.getRole()).isEqualTo("user");
            assertThat(user.getContent()).isEqualTo(List.of(Map.of("text", "你好")));
        }

        @Test
        @DisplayName("history 工具消息：assistant 空数组 content + toolCalls；tool 结果带 toolCallId")
        void historyToolMessages() {
            Map<String, Object> toolCallMeta = Map.of(
                "id", "call_9", "type", "function",
                "function", Map.of("name", "search", "arguments", "{\"q\":\"x\"}"));
            ChatRequest request = ChatRequest.builder("继续")
                .history(List.of(
                    MessageInformation.assistant("", Map.of("tool_calls", List.of(toolCallMeta))),
                    MessageInformation.tool("call_9", "结果")))
                .build();
            List<Object> messages = client().buildMultimodalParam(request, false).getMessages();

            MultiModalMessage assistant = (MultiModalMessage) messages.get(0);
            assertThat(assistant.getRole()).isEqualTo("assistant");
            assertThat(assistant.getContent()).isEmpty();
            assertThat(assistant.getToolCalls()).hasSize(1);

            MultiModalMessage tool = (MultiModalMessage) messages.get(1);
            assertThat(tool.getRole()).isEqualTo("tool");
            assertThat(tool.getToolCallId()).isEqualTo("call_9");
            assertThat(tool.getContent()).isEqualTo(List.of(Map.of("text", "结果")));
        }
    }

    // ======================== 阻塞响应映射 ========================

    @Nested
    @DisplayName("chat/chatStream — facade 桩响应映射")
    class FacadeMapping {

        @Test
        @DisplayName("TEXT 路由阻塞：content/reasoning/toolCalls/truncated/usage(cached_tokens)")
        void chatViaGeneration() throws Exception {
            Generation facade = mock(Generation.class);
            Message message = new Message();
            message.setRole("assistant");
            message.setContent("答案");
            message.setReasoningContent("思考");
            message.setToolCalls(List.of(toolCallFragment(0, "call_1", "fn", "{}")));

            GenerationResult result = mock(GenerationResult.class);
            GenerationOutput output = mock(GenerationOutput.class);
            @SuppressWarnings("unchecked")
            GenerationOutput.Choice choice = mock(GenerationOutput.Choice.class);
            when(choice.getMessage()).thenReturn(message);
            when(choice.getFinishReason()).thenReturn("stop");
            when(output.getChoices()).thenReturn((List) List.of(choice));
            when(result.getOutput()).thenReturn(output);
            when(result.getUsage()).thenReturn(GenerationUsage.builder()
                .inputTokens(10).outputTokens(5).totalTokens(15)
                .promptTokensDetails(GenerationUsage.PromptTokensDetails.builder()
                    .cachedTokens(4).build())
                .build());
            when(result.getRequestId()).thenReturn("req-1");

            when(facade.call(any(GenerationParam.class))).thenReturn(result);
            BailianChatClient client = new BailianChatClient("http://localhost:1/api/v1", "k",
                candidate("qwen3-max", Map.of()), facade, null);

            LlmResponse resp = client.chat(ChatRequest.of("q"));
            assertThat(resp.content()).isEqualTo("答案");
            assertThat(resp.reasoningContent()).isEqualTo("思考");
            assertThat(resp.toolCalls()).hasSize(1);
            assertThat(resp.toolCalls().get(0).name()).isEqualTo("fn");
            assertThat(resp.truncated()).isFalse();
            assertThat(resp.tokenUsage().promptTokens()).isEqualTo(10);
            assertThat(resp.tokenUsage().cacheHitTokens()).isEqualTo(4);
        }

        @Test
        @DisplayName("TEXT 路由阻塞：finish=length 标记 truncated")
        void truncatedFlag() throws Exception {
            Generation facade = mock(Generation.class);
            GenerationResult result = mock(GenerationResult.class);
            GenerationOutput output = mock(GenerationOutput.class);
            GenerationOutput.Choice choice = mock(GenerationOutput.Choice.class);
            when(choice.getMessage()).thenReturn(new Message());
            when(choice.getFinishReason()).thenReturn("length");
            when(output.getChoices()).thenReturn((List) List.of(choice));
            when(result.getOutput()).thenReturn(output);
            when(facade.call(any(GenerationParam.class))).thenReturn(result);
            BailianChatClient client = new BailianChatClient("http://localhost:1/api/v1", "k",
                candidate("qwen3-max", Map.of()), facade, null);

            assertThat(client.chat(ChatRequest.of("q")).truncated()).isTrue();
        }

        @Test
        @DisplayName("MULTIMODAL 路由流式：轮末汇总包契约（增量+合并+收口）")
        void multimodalStreamRoundEnd() throws Exception {
            MultiModalConversation facade = mock(MultiModalConversation.class);

            MultiModalConversationResult r1 = mmResult(null, "思", null, null);
            MultiModalConversationResult r2 = mmResult(null, "考", null, null);
            MultiModalConversationResult r3 = mmResult("答", null, null, null);
            MultiModalConversationResult r4 = mmResult(null, null,
                List.of(toolCallFragment(0, "call_1", "get_weather", "")), null);
            MultiModalConversationResult r5 = mmResult(null, null,
                List.of(toolCallFragment(0, "", null, "{\"city\":")), null);
            MultiModalConversationResult r6 = mmResult(null, null,
                List.of(toolCallFragment(0, "", null, "\"北京\"}")), null);
            MultiModalConversationResult r7 = mmResult(null, null, null, "tool_calls");
            when(facade.streamCall(any(MultiModalConversationParam.class)))
                .thenReturn(Flowable.just(r1, r2, r3, r4, r5, r6, r7));

            BailianChatClient client = new BailianChatClient("http://localhost:1/api/v1", "k",
                candidate("qwen3.7-plus", Map.of()), null, facade);

            List<StreamChunk> chunks = client.chatStream(ChatRequest.of("q"))
                .collectList().block(java.time.Duration.ofSeconds(5));

            assertThat(chunks).isNotNull();
            // 增量即时下发：reasoning ×2 + text ×1
            assertThat(chunks.stream().filter(c -> c.hasReasoning() && !c.hasToolCall()).count()).isEqualTo(2);
            assertThat(chunks.stream().filter(StreamChunk::hasText).count()).isEqualTo(1);
            // 轮末汇总包：完整 toolCalls + finishReason + 累计 reasoning
            StreamChunk roundEnd = chunks.get(chunks.size() - 1);
            assertThat(roundEnd.finishReason()).isEqualTo(StreamChunk.FinishReason.TOOL_CALLS);
            assertThat(roundEnd.toolCalls()).hasSize(1);
            assertThat(roundEnd.toolCalls().get(0).id()).isEqualTo("call_1");
            assertThat(roundEnd.toolCalls().get(0).name()).isEqualTo("get_weather");
            assertThat(roundEnd.toolCalls().get(0).arguments()).isEqualTo("{\"city\":\"北京\"}");
            assertThat(roundEnd.reasoningContent()).isEqualTo("思考");
        }

        @Test
        @DisplayName("流式错误：ApiException → RemoteException（429 限流分类）")
        void streamErrorMapping() throws Exception {
            MultiModalConversation facade = mock(MultiModalConversation.class);
            when(facade.streamCall(any(MultiModalConversationParam.class)))
                .thenReturn(Flowable.error(new ApiException(Status.builder()
                    .statusCode(429).code("Throttling").message("rate limited").build())));
            BailianChatClient client = new BailianChatClient("http://localhost:1/api/v1", "k",
                candidate("qwen3.7-plus", Map.of()), null, facade);

            List<Throwable> errors = new java.util.ArrayList<>();
            client.chatStream(ChatRequest.of("q"))
                .doOnError(errors::add)
                .onErrorComplete()
                .blockLast(java.time.Duration.ofSeconds(5));
            assertThat(errors).hasSize(1);
            assertThat(errors.get(0)).isInstanceOf(RemoteException.class);
            assertThat(((RemoteException) errors.get(0)).getErrorCode()).isEqualTo(RemoteErrorCode.LLM_RATE_LIMITED);
        }

        @Test
        @DisplayName("流式回归：中间块携带非终止 finish_reason（字面 \"null\"）不过早收口轮末包")
        void nonTerminalFinishIgnored() throws Exception {
            MultiModalConversation facade = mock(MultiModalConversation.class);
            // P0 联调实证：DashScope 中间块可能带字面 "null"；仅终止枚举（stop 等）才收口
            MultiModalConversationResult r1 = mmResult("好的", null, null, "null");
            MultiModalConversationResult r2 = mmResult("呀", null, null, null);
            MultiModalConversationResult r3 = mmResult(null, null, null, "stop");
            when(facade.streamCall(any(MultiModalConversationParam.class)))
                .thenReturn(Flowable.just(r1, r2, r3));

            BailianChatClient client = new BailianChatClient("http://localhost:1/api/v1", "k",
                candidate("qwen3.7-plus", Map.of()), null, facade);

            List<StreamChunk> chunks = client.chatStream(ChatRequest.of("q"))
                .collectList().block(java.time.Duration.ofSeconds(5));

            assertThat(chunks).isNotNull();
            assertThat(chunks.stream().filter(StreamChunk::hasText).count()).isEqualTo(2);
            StreamChunk roundEnd = chunks.get(chunks.size() - 1);
            assertThat(roundEnd.finishReason()).isEqualTo(StreamChunk.FinishReason.STOP);
            // "null" 中间块不得触发提前收口（否则终块 stop 会被 roundEndEmitted 守卫吞掉）
            assertThat(chunks.size()).isEqualTo(3);
        }

        private MultiModalConversationResult mmResult(String text, String reasoning,
                List<com.alibaba.dashscope.tools.ToolCallBase> toolCalls, String finishReason) {
            MultiModalMessage message = new MultiModalMessage();
            if (text != null) message.setContent(List.of(Map.of("text", text)));
            if (reasoning != null) message.setReasoningContent(reasoning);
            if (toolCalls != null) message.setToolCalls(toolCalls);
            message.setRole("assistant");

            MultiModalConversationOutput.Choice choice = new MultiModalConversationOutput.Choice();
            choice.setMessage(message);
            if (finishReason != null) choice.setFinishReason(finishReason);

            MultiModalConversationResult r = mock(MultiModalConversationResult.class);
            MultiModalConversationOutput output = mock(MultiModalConversationOutput.class);
            when(output.getChoices()).thenReturn((List) List.of(choice));
            when(r.getOutput()).thenReturn(output);
            return r;
        }
    }

    // ======================== usage / 错误映射 ========================

    @Nested
    @DisplayName("mapUsage / translate — 映射契约")
    class MappingContracts {

        @Test
        @DisplayName("usage：cached_tokens → cacheHitTokens")
        void usageCachedTokens() {
            LlmResponse.TokenUsage usage = BailianChatClient.mapUsage(10, 5, 15, 4);
            assertThat(usage.promptTokens()).isEqualTo(10);
            assertThat(usage.completionTokens()).isEqualTo(5);
            assertThat(usage.totalTokens()).isEqualTo(15);
            assertThat(usage.cacheHitTokens()).isEqualTo(4);
        }

        @Test
        @DisplayName("translate：429→限流 / 5xx→瞬态 / 4xx→流式错误 / 网络异常(-1)→瞬态")
        void apiExceptionClassification() {
            assertThat(translateStatus(429).getErrorCode()).isEqualTo(RemoteErrorCode.LLM_RATE_LIMITED);
            assertThat(translateStatus(500).getErrorCode()).isEqualTo(RemoteErrorCode.LLM_TRANSIENT_ERROR);
            assertThat(translateStatus(400).getErrorCode()).isEqualTo(RemoteErrorCode.LLM_STREAM_ERROR);
            assertThat(translateStatus(401).getErrorCode()).isEqualTo(RemoteErrorCode.LLM_STREAM_ERROR);
            assertThat(translateStatus(-1).getErrorCode()).isEqualTo(RemoteErrorCode.LLM_TRANSIENT_ERROR);
        }

        @Test
        @DisplayName("translate：NoApiKeyException → 配置错误")
        void noApiKey() {
            RemoteException re = (RemoteException) BailianChatClient.translate("op",
                new NoApiKeyException());
            assertThat(re.getErrorCode()).isEqualTo(RemoteErrorCode.LLM_CONFIG_ERROR);
        }

        @Test
        @DisplayName("chat 阻塞路径异常同样走错误分类")
        void chatErrorMapping() throws Exception {
            Generation facade = mock(Generation.class);
            when(facade.call(any(GenerationParam.class)))
                .thenThrow(new ApiException(Status.builder()
                    .statusCode(429).code("Throttling").message("limited").build()));
            BailianChatClient client = new BailianChatClient("http://localhost:1/api/v1", "k",
                candidate("qwen3-max", Map.of()), facade, null);

            assertThatThrownBy(() -> client.chat(ChatRequest.of("q")))
                .isInstanceOf(RemoteException.class)
                .extracting(e -> ((RemoteException) e).getErrorCode())
                .isEqualTo(RemoteErrorCode.LLM_RATE_LIMITED);
        }

        private RemoteException translateStatus(int status) {
            return (RemoteException) BailianChatClient.translate("op", new ApiException(
                Status.builder().statusCode(status).code("x").message("m").build()));
        }
    }

    // ======================== chatWithTools ========================

    @Test
    @DisplayName("chatWithTools：tools 合并进请求后走阻塞路径")
    void chatWithToolsMergesTools() throws Exception {
        Generation facade = mock(Generation.class);
        GenerationResult result = mock(GenerationResult.class);
        GenerationOutput output = mock(GenerationOutput.class);
        GenerationOutput.Choice choice = mock(GenerationOutput.Choice.class);
        when(choice.getMessage()).thenReturn(new Message());
        when(choice.getFinishReason()).thenReturn("stop");
        when(output.getChoices()).thenReturn((List) List.of(choice));
        when(result.getOutput()).thenReturn(output);
        when(facade.call(any(GenerationParam.class))).thenAnswer(inv -> {
            GenerationParam p = inv.getArgument(0);
            assertThat(p.getTools()).hasSize(1);
            return result;
        });
        BailianChatClient client = new BailianChatClient("http://localhost:1/api/v1", "k",
            candidate("qwen3-max", Map.of()), facade, null);

        LlmResponse resp = client.chatWithTools(ChatRequest.of("q"),
            List.of(new ChatTool("fn", null, "{\"type\":\"object\"}")));
        assertThat(resp).isNotNull();
    }
}
