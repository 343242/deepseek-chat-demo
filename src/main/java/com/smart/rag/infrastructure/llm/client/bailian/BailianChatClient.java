package com.smart.rag.infrastructure.llm.client.bailian;

import com.alibaba.dashscope.aigc.generation.Generation;
import com.alibaba.dashscope.aigc.generation.GenerationOutput;
import com.alibaba.dashscope.aigc.generation.GenerationParam;
import com.alibaba.dashscope.aigc.generation.GenerationResult;
import com.alibaba.dashscope.aigc.generation.GenerationUsage;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversation;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversationOutput;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversationParam;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversationResult;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversationUsage;
import com.alibaba.dashscope.common.Message;
import com.alibaba.dashscope.common.MultiModalMessage;
import com.alibaba.dashscope.common.ResponseFormat;
import com.alibaba.dashscope.common.Role;
import com.alibaba.dashscope.exception.ApiException;
import com.alibaba.dashscope.exception.InputRequiredException;
import com.alibaba.dashscope.exception.NoApiKeyException;
import com.alibaba.dashscope.exception.UploadFileException;
import com.alibaba.dashscope.protocol.ConnectionOptions;
import com.alibaba.dashscope.tools.FunctionDefinition;
import com.alibaba.dashscope.tools.ToolBase;
import com.alibaba.dashscope.tools.ToolCallBase;
import com.alibaba.dashscope.tools.ToolCallFunction;
import com.alibaba.dashscope.tools.ToolFunction;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.smart.rag.infrastructure.exception.RemoteException;
import com.smart.rag.infrastructure.exception.errorcode.RemoteErrorCode;
import com.smart.rag.infrastructure.llm.ChatRequest;
import com.smart.rag.infrastructure.llm.ChatTool;
import com.smart.rag.infrastructure.llm.LlmResponse;
import com.smart.rag.infrastructure.llm.MessageInformation;
import com.smart.rag.infrastructure.llm.ModelCandidate;
import com.smart.rag.infrastructure.llm.StreamChunk;
import com.smart.rag.infrastructure.llm.ThinkingBodyResolver;
import com.smart.rag.infrastructure.llm.ThinkingConfig;
import com.smart.rag.infrastructure.llm.ToolCallingCapable;
import com.smart.rag.infrastructure.llm.client.AbstractChatClient;
import io.reactivex.Flowable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 百炼 Chat 客户端 — DashScope 原生协议（dashscope-sdk-java）
 * <p>
 * 按 SDK facade 双路由分流（设计 §4.2.4，P0 真机实测采策略 b）：
 * <ul>
 *   <li>{@link ChatRoute#TEXT} → {@link Generation}（{@code text-generation/generation}，
 *       Message 形状）——qwen3-max / qwen-plus-latest 等纯文本族；</li>
 *   <li>{@link ChatRoute#MULTIMODAL} → {@link MultiModalConversation}
 *       （{@code multimodal-generation/generation}，content 为 {@code [{text:...}]} 数组的纯文本
 *       用法）——qwen3.7-plus / qwen3.8-max 等多模态路由族（P0 实测 text 路由拒服务该族模型）。</li>
 * </ul>
 * 路由由候选 {@code params.route: text|multimodal} 显式声明，未声明按模型名推断（次版本 ≥3.7
 * 或含 {@code -vl} 判多模态路由族）。
 * <p>
 * <b>流式轮末汇总包契约</b>（与 {@code GenericChatClient.readSse} 等价，设计 §4.2.3）：
 * 每个工具轮最后一个 StreamChunk 携带完整合并 toolCalls + finishReason + usage + 累计
 * reasoningContent。SDK 侧显式 {@code incrementalOutput(true)} 获得增量 delta（规避 SDK 内置
 * 累积合并），合并职责由 {@link DashScopeStreamAccumulator}（本包新写，按 DashScope delta
 * 形状——真机实证与 OpenAI 同为 index 分片语义）承担。
 * <p>
 * <b>错误映射</b>：{@link ApiException} 按 HTTP 状态码对齐 {@code HttpClientErrorHandler}
 * 分类（429 限流 / 5xx 及网络异常瞬态 / 其余 4xx 流式错误），{@link NoApiKeyException} 归
 * 配置错误。CircuitBreaker/Retry 语义不变。
 * <p>
 * <b>资源说明</b>：SDK facade 构造期持有一个 OkHttp 客户端（daemon 线程，无 close API），
 * {@link #close()} 为 no-op；连接池/dispatcher 空闲线程自行超时回收。
 */
public class BailianChatClient extends AbstractChatClient implements ToolCallingCapable {

    private static final Logger log = LoggerFactory.getLogger(BailianChatClient.class);
    private static final int CONNECT_TIMEOUT_SECONDS = 10;
    /** 覆盖阻塞（Generic 路径 60s）与流式（120s）两档——SDK facade 单实例同时服务两路径 */
    private static final int READ_TIMEOUT_SECONDS = 120;
    private static final Pattern QWEN_VERSIONED = Pattern.compile("^qwen(\\d+)\\.(\\d+)");

    /** SDK facade 双路由（P0 实测矩阵见任务 research/p0-probe-results.md §2） */
    enum ChatRoute { TEXT, MULTIMODAL }

    private final String apiKey;
    private final ChatRoute route;
    private final Generation generation;
    private final MultiModalConversation multimodal;

    /**
     * @param sdkBaseUrl SDK 形态 baseUrl（含 {@code /api/v1} 前缀，如
     *                   {@code https://xxx.cn-beijing.maas.aliyuncs.com/api/v1}）
     */
    public BailianChatClient(String sdkBaseUrl, String apiKey, ModelCandidate candidate) {
        this(sdkBaseUrl, apiKey, candidate,
            resolveRoute(candidate.params(), candidate.model()) == ChatRoute.TEXT
                ? new Generation("http", sdkBaseUrl, defaultConnectionOptions()) : null,
            resolveRoute(candidate.params(), candidate.model()) == ChatRoute.MULTIMODAL
                ? new MultiModalConversation("http", sdkBaseUrl, defaultConnectionOptions()) : null);
    }

    /** 测试注入桩 facade 用（route 由已注入的非空 facade 决定） */
    BailianChatClient(String sdkBaseUrl, String apiKey, ModelCandidate candidate,
                      Generation generationFacade, MultiModalConversation multimodalFacade) {
        super(Objects.requireNonNull(candidate, "candidate must not be null"), candidate.provider());
        Objects.requireNonNull(apiKey, "apiKey must not be null");
        this.apiKey = apiKey;
        if (generationFacade != null) {
            this.route = ChatRoute.TEXT;
            this.generation = generationFacade;
            this.multimodal = null;
        } else {
            this.route = ChatRoute.MULTIMODAL;
            this.generation = null;
            this.multimodal = Objects.requireNonNull(multimodalFacade);
        }
        log.info("BailianChatClient initialized: model={}, route={}, candidate={}",
            candidate.model(), route, candidate.id());
    }

    private static ConnectionOptions defaultConnectionOptions() {
        return ConnectionOptions.builder()
            .connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS))
            .readTimeout(Duration.ofSeconds(READ_TIMEOUT_SECONDS))
            .build();
    }

    // ======================== 路由解析 ========================

    /**
     * 路由解析：候选 {@code params.route}（text|multimodal）显式声明优先；
     * 未声明按模型名推断——{@code qwen<major>.<minor>-*} 次版本 ≥7（qwen3.7-plus/qwen3.8-max）
     * 或名称含 {@code -vl} 判 MULTIMODAL，其余（qwen3-max/qwen-plus-latest）判 TEXT。
     */
    static ChatRoute resolveRoute(Map<String, Object> params, String model) {
        Object declared = params == null ? null : params.get("route");
        if (declared instanceof String s && !s.isBlank()) {
            if ("multimodal".equalsIgnoreCase(s.trim())) return ChatRoute.MULTIMODAL;
            if ("text".equalsIgnoreCase(s.trim())) return ChatRoute.TEXT;
        }
        String m = model == null ? "" : model.toLowerCase();
        if (m.contains("-vl")) return ChatRoute.MULTIMODAL;
        var matcher = QWEN_VERSIONED.matcher(m);
        if (matcher.find()) {
            return Integer.parseInt(matcher.group(2)) >= 7 ? ChatRoute.MULTIMODAL : ChatRoute.TEXT;
        }
        return ChatRoute.TEXT;
    }

    // ======================== ChatCapable（阻塞） ========================

    @Override
    public LlmResponse chat(ChatRequest request) {
        try {
            return route == ChatRoute.TEXT
                ? chatViaGeneration(request)
                : chatViaMultimodal(request);
        } catch (ApiException | NoApiKeyException | InputRequiredException | UploadFileException e) {
            throw translate("Bailian Chat", e);
        }
    }

    private LlmResponse chatViaGeneration(ChatRequest request)
            throws ApiException, NoApiKeyException, InputRequiredException {
        GenerationResult result = generation.call(buildGenerationParam(request, false));
        GenerationOutput.Choice choice = firstChoice(result.getOutput());
        Message message = choice == null ? null : choice.getMessage();
        String content = message != null && message.getContent() != null ? message.getContent() : "";
        List<LlmResponse.ToolCall> toolCalls = message != null
            ? mapToolCalls(message.getToolCalls()) : List.of();
        String reasoning = message != null && message.getReasoningContent() != null
            ? message.getReasoningContent() : "";
        boolean truncated = choice != null && "length".equals(choice.getFinishReason());
        return new LlmResponse(content, truncated, mapUsage(result.getUsage()), toolCalls,
            Map.of("requestId", result.getRequestId() != null ? result.getRequestId() : ""), reasoning);
    }

    private LlmResponse chatViaMultimodal(ChatRequest request)
            throws ApiException, NoApiKeyException, InputRequiredException, UploadFileException {
        MultiModalConversationResult result = multimodal.call(buildMultimodalParam(request, false));
        MultiModalConversationOutput.Choice choice = firstChoice(result.getOutput());
        MultiModalMessage message = choice == null ? null : choice.getMessage();
        String joined = message != null ? joinTextContent(message.getContent()) : null;
        String content = joined != null ? joined : "";
        List<LlmResponse.ToolCall> toolCalls = message != null
            ? mapToolCalls(message.getToolCalls()) : List.of();
        String reasoning = message != null && message.getReasoningContent() != null
            ? message.getReasoningContent() : "";
        boolean truncated = choice != null && "length".equals(choice.getFinishReason());
        return new LlmResponse(content, truncated, mapUsage(result.getUsage()), toolCalls,
            Map.of("requestId", result.getRequestId() != null ? result.getRequestId() : ""), reasoning);
    }

    // ======================== ChatCapable（流式） ========================

    @Override
    public Flux<StreamChunk> chatStream(ChatRequest request) {
        return Flux.create(sink -> {
            // streamCall 签名抛受检异常，构造期失败直接 error（不发任何 chunk）
            Flowable<?> flowable;
            try {
                flowable = route == ChatRoute.TEXT
                    ? generation.streamCall(buildGenerationParam(request, true))
                    : multimodal.streamCall(buildMultimodalParam(request, true));
            } catch (ApiException | NoApiKeyException | InputRequiredException | UploadFileException e) {
                sink.error(translate("Bailian Chat Stream", e));
                return;
            }
            // RxJava2 Flowable → Reactor Flux 薄桥（subscribe/dispose 透传，不引 reactor-adapter）
            DashScopeStreamAccumulator acc = new DashScopeStreamAccumulator();
            io.reactivex.disposables.Disposable disposable = flowable.subscribe(
                chunk -> {
                    if (sink.isCancelled()) return;
                    if (route == ChatRoute.TEXT) {
                        GenerationResult r = (GenerationResult) chunk;
                        acc.accept(toView(r), mapUsage(r.getUsage()), sink);
                    } else {
                        MultiModalConversationResult r = (MultiModalConversationResult) chunk;
                        acc.accept(toView(r), mapUsage(r.getUsage()), sink);
                    }
                },
                err -> {
                    if (!sink.isCancelled()) sink.error(translate("Bailian Chat Stream", err));
                },
                () -> {
                    // 流自然结束兜底：finish_reason 未到也发轮末汇总包（对齐 GenericChatClient 行流结束兜底）
                    acc.emitRoundEnd(sink, null);
                    if (!sink.isCancelled()) sink.complete();
                });
            sink.onDispose(disposable::dispose);
        });
    }

    // ======================== ToolCallingCapable ========================

    /**
     * 带工具调用的阻塞对话。项目内 Agent 主链路走流式（chatStream + request.tools +
     * 轮末汇总包契约），本方法为 SPI 完整性保留：将 tools 合并进请求后走阻塞路径。
     */
    @Override
    public LlmResponse chatWithTools(ChatRequest request, List<Object> tools) {
        if (tools == null || tools.isEmpty()) return chat(request);
        List<ChatTool> merged = new ArrayList<>(request.tools());
        for (Object t : tools) {
            if (t instanceof ChatTool ct) merged.add(ct);
        }
        return chat(new ChatRequest(request.input(), request.systemPrompt(), request.history(),
            request.temperature(), request.maxTokens(), request.topP(), request.extraParams(),
            merged, request.thinking()));
    }

    // ======================== 参数构建 ========================

    /** 两条路由共享的已解析参数（避免对两个无共同接口的 lombok builder 做反射设置） */
    record CommonParams(Float temperature, Integer maxTokens, Double topP,
                        Boolean enableThinking, Integer thinkingBudget,
                        List<ToolBase> tools, ResponseFormat responseFormat,
                        Map<String, Object> extraParameters) {}

    CommonParams resolveCommon(ChatRequest request) {
        ThinkingConfig cfg = request.thinking() != null
            ? request.thinking()
            : ThinkingBodyResolver.extractDefault(candidate.params());
        Boolean enableThinking = cfg != null ? cfg.enabled() : null;
        Integer thinkingBudget = cfg != null && cfg.enabled() ? cfg.budgetTokens() : null;

        List<ToolBase> tools = null;
        if (request.tools() != null && !request.tools().isEmpty()) {
            tools = new ArrayList<>(request.tools().size());
            for (ChatTool t : request.tools()) {
                tools.add(toSdkTool(t));
            }
        }

        // extraParams：response_format → SDK ResponseFormat（评估 JSON 修复的 SDK 路径覆盖，
        // 设计决策 6）；其余键按 DashScope 原生 parameters 透传
        ResponseFormat responseFormat = null;
        Map<String, Object> passthrough = Map.of();
        if (!request.extraParams().isEmpty()) {
            var remaining = new LinkedHashMap<String, Object>();
            for (Map.Entry<String, Object> e : request.extraParams().entrySet()) {
                if ("response_format".equals(e.getKey())) {
                    Object type = e.getValue() instanceof Map<?, ?> m && m.containsKey("type")
                        ? m.get("type") : e.getValue();
                    if (type != null) {
                        responseFormat = ResponseFormat.builder().type(type).build();
                    }
                } else {
                    remaining.put(e.getKey(), e.getValue());
                }
            }
            passthrough = remaining;
        }
        return new CommonParams(request.temperature() != null ? request.temperature().floatValue() : null,
            request.maxTokens(), request.topP(), enableThinking, thinkingBudget,
            tools, responseFormat, passthrough);
    }

    GenerationParam buildGenerationParam(ChatRequest request, boolean stream) {
        CommonParams cp = resolveCommon(request);
        GenerationParam.GenerationParamBuilder<?, ?> b = GenerationParam.builder()
            .apiKey(apiKey)
            .model(candidate.model())
            .messages(toSdkMessagesText(request))
            .resultFormat(GenerationParam.ResultFormat.MESSAGE);
        if (cp.temperature() != null) b.temperature(cp.temperature());
        if (cp.maxTokens() != null) b.maxTokens(cp.maxTokens());
        if (cp.topP() != null) b.topP(cp.topP());
        if (cp.enableThinking() != null) b.enableThinking(cp.enableThinking());
        if (cp.thinkingBudget() != null) b.thinkingBudget(cp.thinkingBudget());
        if (cp.tools() != null) b.tools(cp.tools());
        if (cp.responseFormat() != null) b.responseFormat(cp.responseFormat());
        cp.extraParameters().forEach(b::parameter);
        if (stream) b.incrementalOutput(true);
        return b.build();
    }

    MultiModalConversationParam buildMultimodalParam(ChatRequest request, boolean stream) {
        CommonParams cp = resolveCommon(request);
        MultiModalConversationParam.MultiModalConversationParamBuilder<?, ?> b =
            MultiModalConversationParam.builder()
                .apiKey(apiKey)
                .model(candidate.model())
                .messages(toSdkMessagesMultimodal(request));
        if (cp.temperature() != null) b.temperature(cp.temperature());
        if (cp.maxTokens() != null) b.maxTokens(cp.maxTokens());
        if (cp.topP() != null) b.topP(cp.topP());
        if (cp.enableThinking() != null) b.enableThinking(cp.enableThinking());
        if (cp.thinkingBudget() != null) b.thinkingBudget(cp.thinkingBudget());
        if (cp.tools() != null) b.tools(cp.tools());
        if (cp.responseFormat() != null) b.responseFormat(cp.responseFormat());
        cp.extraParameters().forEach(b::parameter);
        if (stream) b.incrementalOutput(true);
        return b.build();
    }

    // ======================== 消息/工具形状转换 ========================

    /** ChatRequest → TEXT 路由 Message 列表（history 工具消息转换，设计 §4.2.2） */
    static List<Message> toSdkMessagesText(ChatRequest request) {
        List<Message> out = new ArrayList<>();
        if (request.systemPrompt() != null) {
            out.add(Message.builder().role(Role.SYSTEM.getValue()).content(request.systemPrompt()).build());
        }
        for (MessageInformation m : request.history()) {
            out.add(toSdkMessageText(m));
        }
        out.add(Message.builder().role(Role.USER.getValue()).content(request.input()).build());
        return out;
    }

    private static Message toSdkMessageText(MessageInformation m) {
        if ("tool".equals(m.role())) {
            return Message.builder().role(Role.TOOL.getValue())
                .toolCallId(m.toolCallId())
                .content(m.content() != null ? m.content() : "")
                .build();
        }
        if ("assistant".equals(m.role()) && m.metadata().containsKey("tool_calls")) {
            List<ToolCallBase> toolCalls = toSdkToolCalls(m.metadata().get("tool_calls"));
            if (!toolCalls.isEmpty()) {
                return Message.builder().role(Role.ASSISTANT.getValue())
                    .content(m.content() != null ? m.content() : "")
                    .toolCalls(toolCalls)
                    .build();
            }
        }
        // Qwen 原生协议不要求回传 reasoning_content（DeepSeek 兼容层 quirk，设计 §4.2.2 利好）——不注入
        return Message.builder().role(m.role())
            .content(m.content() != null ? m.content() : "")
            .build();
    }

    /** ChatRequest → MULTIMODAL 路由消息列表（content 为 [{text:...}] 数组的纯文本用法） */
    static List<Object> toSdkMessagesMultimodal(ChatRequest request) {
        List<Object> out = new ArrayList<>();
        if (request.systemPrompt() != null) {
            out.add(textMessage("system", request.systemPrompt()));
        }
        for (MessageInformation m : request.history()) {
            out.add(toSdkMessageMultimodal(m));
        }
        out.add(textMessage("user", request.input()));
        return out;
    }

    private static Object toSdkMessageMultimodal(MessageInformation m) {
        if ("tool".equals(m.role())) {
            return MultiModalMessage.builder()
                .role("tool").toolCallId(m.toolCallId())
                .content(List.of(Map.of("text", m.content() != null ? m.content() : "")))
                .build();
        }
        if ("assistant".equals(m.role()) && m.metadata().containsKey("tool_calls")) {
            List<ToolCallBase> toolCalls = toSdkToolCalls(m.metadata().get("tool_calls"));
            if (!toolCalls.isEmpty()) {
                // 真机实证（P0 冒烟 R2）：assistant 工具轮 content 允许空数组 + toolCalls 元数据
                return MultiModalMessage.builder().role("assistant")
                    .content(new ArrayList<>()).toolCalls(toolCalls).build();
            }
        }
        return textMessage(m.role(), m.content() != null ? m.content() : "");
    }

    private static MultiModalMessage textMessage(String role, String text) {
        return MultiModalMessage.builder().role(role)
            .content(List.of(Map.of("text", text))).build();
    }

    /** OpenAI 兼容 tool_calls 元数据（ChatModelAdapter.extractHistory 产出）→ SDK ToolCallFunction */
    static List<ToolCallBase> toSdkToolCalls(Object metadataToolCalls) {
        List<ToolCallBase> out = new ArrayList<>();
        if (metadataToolCalls instanceof List<?> list) {
            int index = 0;
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> tc)) continue;
                Object fn = tc.get("function");
                String name = fn instanceof Map<?, ?> f && f.get("name") instanceof String n ? n : null;
                String args = fn instanceof Map<?, ?> f && f.get("arguments") instanceof String a ? a : "";
                Object id = tc.get("id");
                ToolCallFunction sdk = new ToolCallFunction();
                sdk.setIndex(index++);
                if (id instanceof String s && !s.isEmpty()) sdk.setId(s);
                sdk.setType("function");
                ToolCallFunction.CallFunction call = sdk.new CallFunction();
                if (name != null) call.setName(name);
                call.setArguments(args != null ? args : "");
                sdk.setFunction(call);
                out.add(sdk);
            }
        }
        return out;
    }

    /** ChatTool → SDK ToolFunction（inputSchemaJson 字符串 → gson JsonObject） */
    static ToolBase toSdkTool(ChatTool tool) {
        JsonObject parameters;
        try {
            parameters = JsonParser.parseString(tool.inputSchemaJson()).getAsJsonObject();
        } catch (Exception e) {
            throw new RemoteException(RemoteErrorCode.LLM_CONFIG_ERROR,
                "Invalid tool input schema for '" + tool.name() + "': " + e.getMessage(), e);
        }
        FunctionDefinition.FunctionDefinitionBuilder<?, ?> fn = FunctionDefinition.builder()
            .name(tool.name())
            .parameters(parameters);
        if (tool.description() != null) fn.description(tool.description());
        return ToolFunction.builder().function(fn.build()).build();
    }

    // ======================== 响应视图（统一两条路由的 Choice 形状） ========================

    /** 两路由 Choice 的统一只读视图（text 提取差异在构造处吸收） */
    record ChoiceView(String text, String reasoning, List<ToolCallBase> toolCalls, String finishReason) {}

    private static ChoiceView toView(GenerationResult result) {
        GenerationOutput.Choice choice = firstChoice(result.getOutput());
        if (choice == null || choice.getMessage() == null) return new ChoiceView(null, null, null, null);
        Message m = choice.getMessage();
        return new ChoiceView(m.getContent(), m.getReasoningContent(), m.getToolCalls(),
            choice.getFinishReason());
    }

    private static ChoiceView toView(MultiModalConversationResult result) {
        MultiModalConversationOutput.Choice choice = firstChoice(result.getOutput());
        if (choice == null || choice.getMessage() == null) return new ChoiceView(null, null, null, null);
        MultiModalMessage m = choice.getMessage();
        return new ChoiceView(joinTextContent(m.getContent()), m.getReasoningContent(),
            m.getToolCalls(), choice.getFinishReason());
    }

    private static GenerationOutput.Choice firstChoice(GenerationOutput output) {
        return output != null && output.getChoices() != null && !output.getChoices().isEmpty()
            ? output.getChoices().get(0) : null;
    }

    private static MultiModalConversationOutput.Choice firstChoice(MultiModalConversationOutput output) {
        return output != null && output.getChoices() != null && !output.getChoices().isEmpty()
            ? output.getChoices().get(0) : null;
    }

    /** multimodal content（List&lt;Map&gt;，形如 [{text=增量}]）→ 拼接各 text 值 */
    static String joinTextContent(List<Map<String, Object>> content) {
        if (content == null || content.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> part : content) {
            Object text = part.get("text");
            if (text instanceof String s) sb.append(s);
        }
        return sb.length() > 0 ? sb.toString() : null;
    }

    static List<LlmResponse.ToolCall> mapToolCalls(List<ToolCallBase> toolCalls) {
        if (toolCalls == null || toolCalls.isEmpty()) return List.of();
        List<LlmResponse.ToolCall> out = new ArrayList<>(toolCalls.size());
        for (ToolCallBase base : toolCalls) {
            if (base instanceof ToolCallFunction f && f.getFunction() != null) {
                out.add(new LlmResponse.ToolCall(
                    f.getId() != null ? f.getId() : "",
                    f.getFunction().getName() != null ? f.getFunction().getName() : "",
                    f.getFunction().getArguments() != null ? f.getFunction().getArguments() : ""));
            }
        }
        return out;
    }

    /** SDK usage（两路由字段同构）→ TokenUsage（cacheHit 来自 prompt_tokens_details.cached_tokens） */
    static LlmResponse.TokenUsage mapUsage(Integer inputTokens, Integer outputTokens,
                                           Integer totalTokens, Integer cachedTokens) {
        return new LlmResponse.TokenUsage(
            inputTokens != null ? inputTokens : 0,
            outputTokens != null ? outputTokens : 0,
            totalTokens != null ? totalTokens : 0,
            cachedTokens);
    }

    private static LlmResponse.TokenUsage mapUsage(GenerationUsage usage) {
        if (usage == null) return null;
        Integer cached = usage.getPromptTokensDetails() != null
            ? usage.getPromptTokensDetails().getCachedTokens() : null;
        return mapUsage(usage.getInputTokens(), usage.getOutputTokens(), usage.getTotalTokens(), cached);
    }

    private static LlmResponse.TokenUsage mapUsage(MultiModalConversationUsage usage) {
        if (usage == null) return null;
        Integer cached = usage.getPromptTokensDetails() != null
            ? usage.getPromptTokensDetails().getCachedTokens() : null;
        return mapUsage(usage.getInputTokens(), usage.getOutputTokens(), usage.getTotalTokens(), cached);
    }

    // ======================== 错误映射 ========================

    /**
     * SDK 异常 → RemoteException（对齐 HttpClientErrorHandler 状态码分类）：
     * 429 → LLM_RATE_LIMITED；5xx / 网络异常(statusCode=-1) → LLM_TRANSIENT_ERROR；
     * 其余 4xx（含 workspace 域名 Key 归属不匹配 401、路由错配 url error 400）→ LLM_STREAM_ERROR；
     * NoApiKeyException → LLM_CONFIG_ERROR。纯转换器，调用方负责抛出。
     */
    static RuntimeException translate(String operation, Throwable e) {
        if (e instanceof RemoteException re) return re;
        if (e instanceof NoApiKeyException nae) {
            return new RemoteException(RemoteErrorCode.LLM_CONFIG_ERROR,
                operation + " failed: no api key provided", nae);
        }
        if (e instanceof ApiException ae) {
            int status = ae.getStatus() != null ? ae.getStatus().getStatusCode() : -1;
            String code = ae.getStatus() != null ? orEmpty(ae.getStatus().getCode()) : "";
            String message = operation + " failed: HTTP " + status
                + " code=" + code + ": " + orEmpty(ae.getMessage());
            if (status == 429) {
                return new RemoteException(RemoteErrorCode.LLM_RATE_LIMITED, message, ae);
            }
            if (status >= 500 || status < 0) {
                return new RemoteException(RemoteErrorCode.LLM_TRANSIENT_ERROR, message, ae);
            }
            return new RemoteException(RemoteErrorCode.LLM_STREAM_ERROR, message, ae);
        }
        if (e instanceof InputRequiredException ire) {
            return new RemoteException(RemoteErrorCode.LLM_CONFIG_ERROR,
                operation + " failed: " + ire.getMessage(), ire);
        }
        return new RemoteException(RemoteErrorCode.LLM_STREAM_ERROR,
            operation + " failed: " + e.getMessage(), e);
    }

    private static String orEmpty(String s) {
        return s != null ? s : "";
    }

    // ======================== 流式累积器（DashScope delta 形状，全新实现） ========================

    /**
     * SDK Flowable 事件流上的轮内累积器（设计 §4.2.3「搬迁非删除」——不复用
     * {@code client.protocol.ToolCallAccumulator}：其为包私有且按 OpenAI index 语义；本类按
     * DashScope delta 形状新写。真机实证：首片携带 index+id+name，后续片仅 arguments 片段，
     * 尾片 finish_reason=tool_calls 且 toolCalls 为空片段——轮末汇总必须由本类 drain 产出）。
     * <p>
     * 生命周期：单次 chatStream = 单轮 = 一个实例。
     */
    static final class DashScopeStreamAccumulator {

        private final Map<Integer, ToolCallAcc> byIndex = new LinkedHashMap<>();
        private final StringBuilder reasoningBuf = new StringBuilder();
        private LlmResponse.TokenUsage lastUsage;
        private boolean roundEndEmitted;

        void accept(ChoiceView view, LlmResponse.TokenUsage usage, FluxSink<StreamChunk> sink) {
            // DashScope 每 chunk 均携带累积 usage（P0 实证），保留最新值供轮末汇总/兜底
            if (usage != null) lastUsage = usage;
            if (view == null) return; // usage-only 块（choices 空）

            // 0) reasoning delta → 即时下发（保 TTFT，前端唯一显示来源）+ 累积（工具轮汇总包携带完整值供 R6 回传）
            if (view.reasoning() != null && !view.reasoning().isEmpty()) {
                reasoningBuf.append(view.reasoning());
                sink.next(new StreamChunk(null, null, null, null, view.reasoning()));
            }
            // 1) content → text chunk（即时发；multimodal 路由已抽取为纯文本增量）
            if (view.text() != null && !view.text().isEmpty()) {
                sink.next(new StreamChunk(view.text(), null, null, null));
            }
            // 2) tool_calls 分片 → 按 index 累积（轮末汇总发）
            if (view.toolCalls() != null) {
                for (ToolCallBase base : view.toolCalls()) {
                    if (base instanceof ToolCallFunction f) {
                        merge(f);
                    }
                }
            }
            // 3) finish_reason → 轮末汇总包（该块 usage 即终值）。
            //    P0 联调实证：DashScope 流式中间块可能携带非终止的 finish_reason 值
            //    （如字面 "null" 字符串）——仅识别到终止枚举才收口，未识别终止值由流完
            //    成兜底 emitRoundEnd 保证轮末包不丢。
            if (view.finishReason() != null && !view.finishReason().isEmpty()
                    && mapFinishReason(view.finishReason()) != null) {
                emitRoundEnd(sink, view.finishReason());
            }
        }

        private void merge(ToolCallFunction f) {
            int idx = f.getIndex() != null ? f.getIndex() : 0;
            String id = f.getId() != null && !f.getId().isEmpty() ? f.getId() : null;
            String name = f.getFunction() != null ? f.getFunction().getName() : null;
            String args = f.getFunction() != null ? f.getFunction().getArguments() : null;
            ToolCallAcc acc = byIndex.get(idx);
            if (acc == null) {
                byIndex.put(idx, new ToolCallAcc(id, name, new StringBuilder(args != null ? args : "")));
            } else {
                if (acc.id == null && id != null) acc.id = id;
                if (acc.name == null && name != null) acc.name = name;
                if (args != null && !args.isEmpty()) acc.args.append(args);
            }
        }

        /**
         * 轮末汇总包：完整 toolCalls + finishReason + usage + 完整累积 reasoning（ChatModelAdapter ReAct 依赖）。
         * <p>
         * 累积 reasoning 仅随工具轮汇总包下发（R6 回传：DeepSeek 要求 tool_calls 轮完整回传上一轮
         * reasoning_content）。非工具轮不携带——reasoning delta 已即时增量下发，再带完整累积值会被
         * {@code AbstractModeStrategy.splitIntoFrames} 当作新增片段重复发帧（前端思考过程整段重复）。
         */
        void emitRoundEnd(FluxSink<StreamChunk> sink, String finishReason) {
            if (roundEndEmitted) return;
            List<StreamChunk.ToolCallDelta> toolCalls = drain();
            StreamChunk.FinishReason fr = mapFinishReason(finishReason);
            // fr/usage/toolCalls 全空时无内容可发（非工具轮 reasoning 已增量下发，不再补发）
            if (toolCalls.isEmpty() && fr == null && lastUsage == null) return;
            roundEndEmitted = true;
            String reasoning = toolCalls.isEmpty() || reasoningBuf.isEmpty()
                ? null : reasoningBuf.toString();
            sink.next(new StreamChunk(null, toolCalls.isEmpty() ? null : toolCalls, fr, lastUsage, reasoning));
        }

        private List<StreamChunk.ToolCallDelta> drain() {
            if (byIndex.isEmpty()) return List.of();
            List<StreamChunk.ToolCallDelta> out = new ArrayList<>(byIndex.size());
            for (Map.Entry<Integer, ToolCallAcc> e : byIndex.entrySet()) {
                ToolCallAcc a = e.getValue();
                out.add(new StreamChunk.ToolCallDelta(e.getKey(), a.id, a.name, a.args.toString()));
            }
            byIndex.clear();
            return out;
        }

        private static final class ToolCallAcc {
            String id;
            String name;
            final StringBuilder args;
            ToolCallAcc(String id, String name, StringBuilder args) {
                this.id = id; this.name = name; this.args = args;
            }
        }
    }

    static StreamChunk.FinishReason mapFinishReason(String raw) {
        if (raw == null || raw.isEmpty()) return null;
        return switch (raw) {
            case "stop" -> StreamChunk.FinishReason.STOP;
            case "length" -> StreamChunk.FinishReason.LENGTH;
            case "tool_calls" -> StreamChunk.FinishReason.TOOL_CALLS;
            case "content_filter" -> StreamChunk.FinishReason.CONTENT_FILTER;
            default -> null;
        };
    }

    @Override
    public void close() {
        // SDK facade 无 close API（OkHttp daemon 线程自回收），无资源需显式释放
    }

}
