package com.smart.rag.infrastructure.stream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.Call;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.BufferedSource;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class OkHttpSseModelStreamClient {

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final Call.Factory callFactory;
    private final ObjectMapper objectMapper;

    public OkHttpSseModelStreamClient(Call.Factory callFactory) {
        this(callFactory, new ObjectMapper());
    }

    OkHttpSseModelStreamClient(Call.Factory callFactory, ObjectMapper objectMapper) {
        this.callFactory = callFactory;
        this.objectMapper = objectMapper;
    }

    public Flux<String> stream(ModelStreamRequest streamRequest) {
        return Flux.<String>create(sink -> executeStream(streamRequest, sink), FluxSink.OverflowStrategy.BUFFER)
                .subscribeOn(Schedulers.boundedElastic());
    }

    private void executeStream(ModelStreamRequest streamRequest, FluxSink<String> sink) {
        Call call = callFactory.newCall(toHttpRequest(streamRequest));
        sink.onCancel(call::cancel);
        try (Response response = call.execute()) {
            if (!response.isSuccessful()) {
                throw new ModelStreamException("模型流式请求失败: HTTP " + response.code());
            }
            ResponseBody body = response.body();
            if (body == null) {
                throw new ModelStreamException("模型流式响应为空");
            }
            readSse(body.source(), call, sink);
            if (!sink.isCancelled()) {
                sink.complete();
            }
        } catch (ModelStreamException e) {
            if (!sink.isCancelled()) {
                sink.error(e);
            }
        } catch (IOException e) {
            if (!sink.isCancelled()) {
                sink.error(new ModelStreamException("模型流式请求失败", e));
            }
        } catch (RuntimeException e) {
            if (!sink.isCancelled()) {
                sink.error(e);
            }
        }
    }

    private void readSse(BufferedSource source, Call call, FluxSink<String> sink) throws IOException {
        while (!source.exhausted()) {
            if (sink.isCancelled()) {
                call.cancel();
                return;
            }
            String line = source.readUtf8Line();
            if (line == null) {
                return;
            }
            String data = parseDataLine(line);
            if (data == null) {
                continue;
            }
            if ("[DONE]".equals(data)) {
                return;
            }
            String content = parseContent(data);
            if (content != null && !content.isEmpty()) {
                sink.next(content);
                if (sink.isCancelled()) {
                    call.cancel();
                    return;
                }
            }
        }
    }

    private String parseDataLine(String line) {
        if (line == null || line.isBlank() || !line.startsWith("data:")) {
            return null;
        }
        return line.substring("data:".length()).trim();
    }

    private String parseContent(String data) {
        try {
            JsonNode root = objectMapper.readTree(data);
            JsonNode choices = root.path("choices");
            if (!choices.isArray() || choices.isEmpty()) {
                return null;
            }
            JsonNode first = choices.get(0);
            JsonNode deltaContent = first.path("delta").path("content");
            if (deltaContent.isTextual()) {
                return deltaContent.asText();
            }
            JsonNode messageContent = first.path("message").path("content");
            return messageContent.isTextual() ? messageContent.asText() : null;
        } catch (IOException e) {
            throw new ModelStreamException("模型流式响应解析失败", e);
        }
    }

    private Request toHttpRequest(ModelStreamRequest request) {
        HttpUrl base = HttpUrl.parse(request.baseUrl());
        if (base == null) {
            throw new ModelStreamException("模型 API 地址无效");
        }
        HttpUrl url = base.newBuilder()
                .addPathSegments(trimSlashes(request.completionsPath()))
                .build();

        String body = toJson(request);
        return new Request.Builder()
                .url(url)
                .header("Accept", "text/event-stream")
                .header("Authorization", "Bearer " + request.apiKey())
                .post(RequestBody.create(body, JSON))
                .build();
    }

    private String toJson(ModelStreamRequest request) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("model", request.route().modelId());
            payload.put("messages", List.of(Map.of(
                    "role", "user",
                    "content", request.prompt())));
            payload.put("stream", true);
            if (request.temperature() != null) {
                payload.put("temperature", request.temperature());
            }
            if (request.maxTokens() != null) {
                payload.put("max_tokens", request.maxTokens());
            }
            return objectMapper.writeValueAsString(payload);
        } catch (IOException e) {
            throw new ModelStreamException("模型流式请求构建失败", e);
        }
    }

    private String trimSlashes(String path) {
        String result = path;
        while (result.startsWith("/")) {
            result = result.substring(1);
        }
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }
}
