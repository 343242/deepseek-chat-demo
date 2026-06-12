package com.smart.rag.infrastructure.llm.client.generic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smart.rag.infrastructure.exception.RemoteException;
import com.smart.rag.infrastructure.exception.errorcode.RemoteErrorCode;
import com.smart.rag.infrastructure.llm.*;
import com.smart.rag.infrastructure.llm.client.AbstractChatClient;
import okhttp3.*;
import okhttp3.OkHttpClient;
import okio.BufferedSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.*;

/**
 * 通用 Chat 客户端（OpenAI 兼容 API）
 * <p>
 * 基于 OpenAI 兼容的 /chat/completions 端点。
 * 阻塞调用使用 {@link RestClient}，流式调用使用 OkHttp SSE。
 */
public class GenericChatClient extends AbstractChatClient {

    private static final Logger log = LoggerFactory.getLogger(GenericChatClient.class);
    private static final MediaType JSON_MEDIA = MediaType.get("application/json; charset=utf-8");

    protected final String baseUrl;
    protected final String endpoint;
    protected final String apiKey;

    private final RestClient restClient;
    private final Call.Factory callFactory;
    private final ObjectMapper objectMapper;

    public GenericChatClient(String baseUrl, String endpoint,
                             String apiKey, ModelCandidate candidate) {
        super(candidate, candidate.provider());
        this.baseUrl = baseUrl;
        this.endpoint = endpoint;
        this.apiKey = apiKey;
        this.objectMapper = new ObjectMapper();

        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
        requestFactory.setReadTimeout(Duration.ofSeconds(60));

        this.restClient = RestClient.builder()
            .baseUrl(baseUrl)
            .defaultHeader("Authorization", "Bearer " + apiKey)
            .defaultHeader("Content-Type", "application/json")
            .requestFactory(requestFactory)
            .build();

        this.callFactory = new OkHttpClient.Builder()
            .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
            .build();
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
        } catch (Exception e) {
            throw new RemoteException(RemoteErrorCode.LLM_STREAM_ERROR,
                "Chat call failed: " + e.getMessage(), e);
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

            Call call = callFactory.newCall(httpRequest);
            sink.onCancel(call::cancel);

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
            } catch (IOException e) {
                if (!sink.isCancelled()) {
                    sink.error(new RemoteException(RemoteErrorCode.LLM_STREAM_ERROR,
                        "Stream request failed", e));
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
                String finishReason = choices.get(0).path("finish_reason").asText("");
                truncated = "length".equals(finishReason);

                JsonNode tcNode = message.path("tool_calls");
                if (tcNode.isArray() && !tcNode.isEmpty()) {
                    toolCalls = new ArrayList<>();
                    for (JsonNode tc : tcNode) {
                        toolCalls.add(new LlmResponse.ToolCall(
                            tc.path("id").asText(),
                            tc.path("function").path("name").asText(),
                            tc.path("function").path("arguments").asText()
                        ));
                    }
                }
            }

            JsonNode usage = root.path("usage");
            LlmResponse.TokenUsage tokenUsage = new LlmResponse.TokenUsage(
                usage.path("prompt_tokens").asInt(0),
                usage.path("completion_tokens").asInt(0),
                usage.path("total_tokens").asInt(0)
            );

            return new LlmResponse(content, truncated, tokenUsage, toolCalls, Map.of());
        } catch (IOException e) {
            throw new RemoteException(RemoteErrorCode.LLM_STREAM_ERROR,
                "Failed to parse chat response: " + e.getMessage(), e);
        }
    }

    @Override
    public void close() {
        if (callFactory instanceof OkHttpClient ok) {
            ok.dispatcher().executorService().shutdown();
            ok.connectionPool().evictAll();
        }
    }
}
