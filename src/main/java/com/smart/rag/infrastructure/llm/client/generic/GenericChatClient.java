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
    public Flux<String> chatStream(ChatRequest request) {
        Map<String, Object> body = buildRequestBody(request, true);
        String url = buildUrl();

        return Flux.<String>create(sink -> {
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
                readSse(responseBody.source(), call, sink);
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

    private void readSse(BufferedSource source, Call call, FluxSink<String> sink) throws IOException {
        while (!source.exhausted()) {
            if (sink.isCancelled()) {
                call.cancel();
                return;
            }
            String line = source.readUtf8Line();
            if (line == null) return;
            if (!line.startsWith("data:")) continue;

            String data = line.substring(5).trim();
            if ("[DONE]".equals(data)) return;

            String content = extractContent(data);
            if (content != null && !content.isEmpty()) {
                sink.next(content);
            }
        }
    }

    private String extractContent(String data) {
        try {
            JsonNode root = objectMapper.readTree(data);
            JsonNode choices = root.path("choices");
            if (!choices.isArray() || choices.isEmpty()) return null;
            JsonNode delta = choices.get(0).path("delta").path("content");
            return delta.isTextual() ? delta.asText() : null;
        } catch (IOException e) {
            log.debug("Failed to parse SSE data chunk: {}", data, e);
            return null;
        }
    }

    private String buildUrl() {
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        String ep = endpoint.startsWith("/") ? endpoint : "/" + endpoint;
        return base + ep;
    }

    private Map<String, Object> buildRequestBody(ChatRequest request, boolean stream) {
        List<Map<String, String>> messages = new ArrayList<>();
        if (request.systemPrompt() != null) {
            messages.add(Map.of("role", "system", "content", request.systemPrompt()));
        }
        if (request.history() != null) {
            for (MessageInformation msg : request.history()) {
                Map<String, String> m = new LinkedHashMap<>();
                m.put("role", msg.role());
                m.put("content", msg.content() != null ? msg.content() : "");
                messages.add(m);
            }
        }
        messages.add(Map.of("role", "user", "content", request.input()));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", candidate.model());
        body.put("messages", messages);
        body.put("stream", stream);
        if (request.temperature() != null) body.put("temperature", request.temperature());
        if (request.maxTokens() != null) body.put("max_tokens", request.maxTokens());
        if (request.topP() != null) body.put("top_p", request.topP());
        return body;
    }

    private LlmResponse parseResponse(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode choices = root.path("choices");
            String content = "";
            boolean truncated = false;
            List<LlmResponse.ToolCall> toolCalls = List.of();

            if (choices.isArray() && !choices.isEmpty()) {
                JsonNode message = choices.get(0).path("message");
                content = message.path("content").asText("");
                truncated = "length".equals(choices.get(0).path("finish_reason").asText(""));
                toolCalls = parseToolCalls(message);
            }

            LlmResponse.TokenUsage tokenUsage = parseTokenUsage(root.path("usage"));
            return new LlmResponse(content, truncated, tokenUsage, toolCalls, Map.of());
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

    private LlmResponse.TokenUsage parseTokenUsage(JsonNode usage) {
        return new LlmResponse.TokenUsage(
            usage.path("prompt_tokens").asInt(0),
            usage.path("completion_tokens").asInt(0),
            usage.path("total_tokens").asInt(0)
        );
    }

    @Override
    public void close() {
        // Note: okHttpClient is a shared singleton managed by HttpClientFactory.closeAll().
        // Only the per-instance RestClient/JdkHttpClient resources are released here.
        http.close();
    }
}
