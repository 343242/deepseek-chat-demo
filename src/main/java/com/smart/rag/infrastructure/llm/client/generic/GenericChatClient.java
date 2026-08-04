package com.smart.rag.infrastructure.llm.client.generic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smart.rag.infrastructure.exception.RemoteException;
import com.smart.rag.infrastructure.exception.errorcode.RemoteErrorCode;
import com.smart.rag.infrastructure.llm.*;
import com.smart.rag.infrastructure.llm.client.AbstractChatClient;
import com.smart.rag.infrastructure.llm.client.HttpClientErrorHandler;
import com.smart.rag.infrastructure.llm.client.HttpClientFactory;
import okhttp3.*;
import okhttp3.OkHttpClient;
import okio.BufferedSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.*;

/**
 * 通用 Chat 客户端（OpenAI 兼容 API）
 * <p>
 * 基于 OpenAI 兼容的 /chat/completions 端点。
 * 阻塞调用使用 {@link RestClient}，流式调用使用 OkHttp SSE。
 * <p>
 * <b>Dual HTTP client libraries:</b> RestClient requires java.net.http.HttpClient as its underlying
 * transport (via {@link JdkClientHttpRequestFactory}), while OkHttp is used for SSE streaming because
 * it provides superior Server-Sent Events support (line-by-line reading with cancellation) compared
 * to the JDK's HttpURLConnection. This is an accepted tradeoff — the blocking path stays on
 * RestClient for consistency with the rest of the Spring ecosystem, and the streaming path uses
 * OkHttp's battle-tested SSE handling.
 */
public class GenericChatClient extends AbstractChatClient {

    private static final Logger log = LoggerFactory.getLogger(GenericChatClient.class);
    private static final MediaType JSON_MEDIA = MediaType.get("application/json; charset=utf-8");
    private static final int CONNECT_TIMEOUT_SECONDS = 10;
    private static final int READ_TIMEOUT_SECONDS = 60;
    private static final int STREAM_READ_TIMEOUT_SECONDS = 120;

    private final String baseUrl;
    private final String endpoint;
    private final String apiKey;

    private final RestClient restClient;
    private final OkHttpClient okHttpClient;
    private final ObjectMapper objectMapper;
    private final HttpClientFactory.HttpHandles http;

    public GenericChatClient(String baseUrl, String endpoint,
                             String apiKey, ModelCandidate candidate,
                             HttpClientFactory httpClientFactory) {
        super(Objects.requireNonNull(candidate, "candidate must not be null"), candidate.provider());
        this.baseUrl = Objects.requireNonNull(baseUrl, "baseUrl must not be null");
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint must not be null");
        this.apiKey = Objects.requireNonNull(apiKey, "apiKey must not be null");
        Objects.requireNonNull(httpClientFactory, "httpClientFactory must not be null");
        this.objectMapper = new ObjectMapper();
        this.http = HttpClientFactory.buildRestClient(baseUrl, apiKey,
            Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS), Duration.ofSeconds(READ_TIMEOUT_SECONDS));
        this.restClient = http.restClient();

        // Share OkHttpClient across all GenericChatClient instances (per timeout signature).
        // OkHttp officially recommends single instance reuse — each instance owns a connection
        // pool + dispatcher ExecutorService; N candidates × N pools multiplies resource cost.
        // The shared instance is managed by HttpClientFactory.closeAll() — do NOT close here.
        this.okHttpClient = httpClientFactory.sharedOkHttpClient(
            Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS),
            Duration.ofSeconds(STREAM_READ_TIMEOUT_SECONDS));

        log.info("GenericChatClient initialized: model={}, endpoint={}", candidate.model(), endpoint);
    }

    @Override
    public LlmResponse chat(ChatRequest request) {
        Map<String, Object> body = buildRequestBody(request, false);
        String url = buildUrl();

        try {
            String responseJson = restClient.post()
                .uri(url)
                .body(body)
                .retrieve()
                .body(String.class);

            return parseResponse(responseJson);
        } catch (RemoteException e) {
            throw e;
        } catch (Exception e) {
            throw HttpClientErrorHandler.translate("Chat", url, e);
        }
    }

    @Override
    public Flux<StreamChunk> chatStream(ChatRequest request) {
        Map<String, Object> body = buildRequestBody(request, true);
        String url = buildUrl();

        return Flux.<StreamChunk>create(sink -> {
            String jsonBody;
            try {
                jsonBody = objectMapper.writeValueAsString(body);
            } catch (IOException e) {
                sink.error(e);
                return;
            }

            Request httpRequest = new Request.Builder()
                .url(url)
                .header("Authorization", "Bearer " + apiKey)
                .header("Accept", "text/event-stream")
                .post(RequestBody.create(jsonBody, JSON_MEDIA))
                .build();

            Call call = okHttpClient.newCall(httpRequest);
            sink.onCancel(call::cancel);
            sink.onDispose(call::cancel);

            try (Response response = call.execute()) {
                if (!response.isSuccessful()) {
                    sink.error(new RemoteException(RemoteErrorCode.LLM_STREAM_ERROR,
                        "Stream request failed: HTTP " + response.code()));
                    return;
                }
                ResponseBody responseBody = response.body();
                if (responseBody == null) {
                    sink.error(new RemoteException(RemoteErrorCode.LLM_STREAM_ERROR, "Stream response body is null"));
                    return;
                }
                readSse(objectMapper, responseBody.source(), call, sink);
                if (!sink.isCancelled()) {
                    sink.complete();
                }
            } catch (RemoteException e) {
                if (!sink.isCancelled()) sink.error(e);
            } catch (Exception e) {
                if (!sink.isCancelled()) {
                    sink.error(HttpClientErrorHandler.translate("Chat Stream", url, e));
                }
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    static void readSse(ObjectMapper objectMapper, BufferedSource source, Call call, FluxSink<StreamChunk> sink) throws IOException {
        ToolCallAccumulator acc = new ToolCallAccumulator();
        StringBuilder reasoningBuf = new StringBuilder(); // 累积 reasoning 片段（工具调用多轮回传需完整值）
        while (!source.exhausted()) {
            if (sink.isCancelled()) {
                call.cancel();
                return;
            }
            String line = source.readUtf8Line();
            if (line == null) break;
            if (!line.startsWith("data:")) continue;

            String data = line.substring(5).trim();
            if ("[DONE]".equals(data)) {
                // [DONE] 兜底：finish_reason 未到的极端情况也发轮末汇总包（必须携带累积 reasoning，勿漏）
                emitRoundEnd(sink, acc, null, null, reasoningBuf.toString());
                return;
            }
            try {
                JsonNode root = objectMapper.readTree(data);
                JsonNode choices = root.path("choices");
                if (!choices.isArray() || choices.isEmpty()) continue;
                JsonNode choice0 = choices.get(0);
                JsonNode delta = choice0.path("delta");

                // 0) reasoning_content delta → 即时下发（保 TTFT）+ 累积（轮末汇总包携带完整值供 R6 回传）
                JsonNode reasoningNode = delta.path("reasoning_content");
                if (reasoningNode.isTextual() && !reasoningNode.asText().isEmpty()) {
                    reasoningBuf.append(reasoningNode.asText());
                    sink.next(new StreamChunk(null, null, null, null, reasoningNode.asText()));
                }

                // 1) content → text chunk（即时发，保 TTFT）
                JsonNode contentNode = delta.path("content");
                if (contentNode.isTextual() && !contentNode.asText().isEmpty()) {
                    sink.next(new StreamChunk(contentNode.asText(), null, null, null));
                }

                // 2) tool_calls 分片 → acc 按 index 累积（轮末汇总发，不依赖 Spring AI delta 合并行为）
                JsonNode tcArray = delta.path("tool_calls");
                if (tcArray.isArray()) {
                    for (JsonNode tc : tcArray) {
                        int idx = tc.path("index").asInt(0);
                        String id = tc.has("id") ? tc.path("id").asText() : null;
                        JsonNode fn = tc.path("function");
                        String name = fn.has("name") ? fn.path("name").asText() : null;
                        String args = fn.has("arguments") ? fn.path("arguments").asText() : null;
                        acc.merge(idx, id, name, args);
                    }
                }

                // 3) finish_reason → 轮末汇总包（完整 toolCalls + finishReason + usage + 完整累积 reasoning）
                JsonNode frNode = choice0.path("finish_reason");
                if (frNode.isTextual() && !"null".equals(frNode.asText())) {
                    LlmResponse.TokenUsage usage = parseTokenUsage(root.path("usage"));
                    emitRoundEnd(sink, acc, frNode.asText(), usage, reasoningBuf.toString());
                    return;
                }
            } catch (IOException e) {
                log.debug("Failed to parse SSE data chunk: {}", data, e);
            }
        }
    }

    /** 轮末汇总包：完整 toolCalls（若累积到）+ finishReason + usage + 完整累积 reasoning（R6 回传）。Poc6 防御式契约，供 ChatModelAdapter 检测工具调用。 */
    private static void emitRoundEnd(FluxSink<StreamChunk> sink, ToolCallAccumulator acc,
                                     String finishReason, LlmResponse.TokenUsage usage,
                                     String accumulatedReasoning) {
        java.util.List<StreamChunk.ToolCallDelta> toolCalls = acc.drain();
        StreamChunk.FinishReason fr = mapFinishReason(finishReason);
        boolean noReasoning = accumulatedReasoning == null || accumulatedReasoning.isEmpty();
        // guard 含 reasoning：[DONE] 场景下 fr/usage/toolCalls 皆 null 但 accumulatedReasoning 可能非空，不能跳过
        if (toolCalls.isEmpty() && fr == null && usage == null && noReasoning) return;
        sink.next(new StreamChunk(null, toolCalls.isEmpty() ? null : toolCalls, fr, usage, accumulatedReasoning));
    }

    private static StreamChunk.FinishReason mapFinishReason(String raw) {
        if (raw == null) return null;
        return switch (raw) {
            case "stop" -> StreamChunk.FinishReason.STOP;
            case "length" -> StreamChunk.FinishReason.LENGTH;
            case "tool_calls" -> StreamChunk.FinishReason.TOOL_CALLS;
            case "content_filter" -> StreamChunk.FinishReason.CONTENT_FILTER;
            default -> null;
        };
    }

    private String buildUrl() {
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        String ep = endpoint.startsWith("/") ? endpoint : "/" + endpoint;
        return base + ep;
    }

    Map<String, Object> buildRequestBody(ChatRequest request, boolean stream) {
        List<Map<String, Object>> messages = new ArrayList<>();
        if (request.systemPrompt() != null) {
            Map<String, Object> sys = new LinkedHashMap<>();
            sys.put("role", "system");
            sys.put("content", request.systemPrompt());
            messages.add(sys);
        }
        if (request.history() != null) {
            for (MessageInformation msg : request.history()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("role", msg.role());
                if ("tool".equals(msg.role())) {
                    if (msg.toolCallId() != null) m.put("tool_call_id", msg.toolCallId());
                    m.put("content", msg.content() != null ? msg.content() : "");
                } else if ("assistant".equals(msg.role()) && msg.metadata() != null && msg.metadata().containsKey("tool_calls")) {
                    m.put("content", msg.content() != null ? msg.content() : "");
                    m.put("tool_calls", msg.metadata().get("tool_calls"));
                    // R6 回传：工具调用多轮中完整回传上一轮 reasoning_content（DeepSeek 要求，否则 400）。
                    // 仅 tool_calls 分支回传——纯多轮对话中该字段被 API 忽略（DeepSeek 文档确认），不注入避免冗余。
                    Object rc = msg.metadata().get("reasoning_content");
                    if (rc instanceof String s && !s.isEmpty()) m.put("reasoning_content", s);
                } else {
                    m.put("content", msg.content() != null ? msg.content() : "");
                }
                messages.add(m);
            }
        }
        Map<String, Object> userMsg = new LinkedHashMap<>();
        userMsg.put("role", "user");
        userMsg.put("content", request.input());
        messages.add(userMsg);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", candidate.model());
        body.put("messages", messages);
        body.put("stream", stream);
        if (request.temperature() != null) body.put("temperature", request.temperature());
        if (request.maxTokens() != null) body.put("max_tokens", request.maxTokens());
        if (request.topP() != null) body.put("top_p", request.topP());
        if (request.tools() != null && !request.tools().isEmpty()) {
            List<Map<String, Object>> tools = new ArrayList<>();
            for (com.smart.rag.infrastructure.llm.ChatTool t : request.tools()) {
                Map<String, Object> fn = new LinkedHashMap<>();
                fn.put("name", t.name());
                if (t.description() != null) fn.put("description", t.description());
                try {
                    fn.put("parameters", objectMapper.readTree(t.inputSchemaJson()));
                } catch (Exception e) {
                    fn.put("parameters", t.inputSchemaJson());
                }
                Map<String, Object> tool = new LinkedHashMap<>();
                tool.put("type", "function");
                tool.put("function", fn);
                tools.add(tool);
            }
            body.put("tools", tools);
        }
        // 思考参数注入：per-request > candidate.params > 不注入（AC3/AC4/R5）。
        // 不以 candidate.supportsThinking() 为门槛——支持思考是能力声明，注入与否完全由
        // params.thinking / per-request 决定。
        ThinkingConfig cfg = request.thinking() != null
            ? request.thinking()
            : ThinkingBodyResolver.extractDefault(candidate.params());
        if (cfg != null) {
            ThinkingDialect dialect = ThinkingBodyResolver.extractDialect(candidate.params());
            body.putAll(ThinkingBodyResolver.resolve(cfg, dialect));
        }
        return body;
    }

    LlmResponse parseResponse(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode choices = root.path("choices");
            String content = "";
            String reasoning = "";
            boolean truncated = false;
            List<LlmResponse.ToolCall> toolCalls = List.of();

            if (choices.isArray() && !choices.isEmpty()) {
                JsonNode message = choices.get(0).path("message");
                content = message.path("content").asText("");
                truncated = "length".equals(choices.get(0).path("finish_reason").asText(""));
                toolCalls = parseToolCalls(message);
                reasoning = message.path("reasoning_content").asText("");
            }

            LlmResponse.TokenUsage tokenUsage = parseTokenUsage(root.path("usage"));
            return new LlmResponse(content, truncated, tokenUsage, toolCalls, Map.of(), reasoning);
        } catch (IOException e) {
            throw new RemoteException(RemoteErrorCode.LLM_RESPONSE_PARSE_ERROR,
                "Failed to parse chat response: " + e.getMessage(), e);
        }
    }

    private List<LlmResponse.ToolCall> parseToolCalls(JsonNode message) {
        JsonNode tcNode = message.path("tool_calls");
        if (!tcNode.isArray() || tcNode.isEmpty()) return List.of();
        List<LlmResponse.ToolCall> toolCalls = new ArrayList<>(tcNode.size());
        for (JsonNode tc : tcNode) {
            toolCalls.add(new LlmResponse.ToolCall(
                tc.path("id").asText(),
                tc.path("function").path("name").asText(),
                tc.path("function").path("arguments").asText()
            ));
        }
        return toolCalls;
    }

    private static LlmResponse.TokenUsage parseTokenUsage(JsonNode usage) {
        Integer cacheHitTokens = null;
        // DeepSeek: prompt_cache_hit_tokens
        if (usage.has("prompt_cache_hit_tokens")) {
            cacheHitTokens = usage.get("prompt_cache_hit_tokens").asInt(0);
        }
        // 百炼/Qwen/GLM: prompt_tokens_details.cached_tokens
        if (cacheHitTokens == null) {
            JsonNode details = usage.path("prompt_tokens_details");
            if (details.has("cached_tokens")) {
                cacheHitTokens = details.get("cached_tokens").asInt(0);
            }
        }
        return new LlmResponse.TokenUsage(
            usage.path("prompt_tokens").asInt(0),
            usage.path("completion_tokens").asInt(0),
            usage.path("total_tokens").asInt(0),
            cacheHitTokens
        );
    }

    @Override
    public void close() {
        // Note: okHttpClient is a shared singleton managed by HttpClientFactory.closeAll().
        // Only the per-instance RestClient/JdkHttpClient resources are released here.
        http.close();
    }
}
