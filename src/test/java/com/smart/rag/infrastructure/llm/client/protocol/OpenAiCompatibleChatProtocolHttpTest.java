package com.smart.rag.infrastructure.llm.client.protocol;

import com.smart.rag.infrastructure.llm.ChatCandidate;
import com.smart.rag.infrastructure.llm.ChatRequest;
import com.smart.rag.infrastructure.llm.LlmCapability;
import com.smart.rag.infrastructure.llm.LlmResponse;
import com.smart.rag.infrastructure.llm.ResolvedEndpoint;
import com.smart.rag.infrastructure.llm.StreamChunk;
import com.smart.rag.infrastructure.exception.RateLimitedException;
import com.smart.rag.infrastructure.exception.RemoteException;
import com.smart.rag.infrastructure.exception.errorcode.RemoteErrorCode;
import com.smart.rag.infrastructure.llm.client.HttpClientFactory;
import com.smart.rag.infrastructure.llm.client.generic.GenericChatClient;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 协议层传输契约测试（MockWebServer）— design llm-client-stateless §4 WS-A / §8 AC1。
 * <p>
 * 断言：目标 URL + 端点路径解析、Authorization 头语义（带 key → Bearer 值；keyless →
 * 不发送该头；阻塞/流式两路一致）、阻塞/流式均经共享传输（同超时签名同实例）、
 * 薄壳委托（candidate.model 随请求体生效）、ResolvedEndpoint toString 脱敏（AC6）。
 */
@DisplayName("OpenAiCompatibleChatProtocol 传输契约（MockWebServer）")
class OpenAiCompatibleChatProtocolHttpTest {

    private MockWebServer server;
    private HttpClientFactory httpClientFactory;
    private OpenAiCompatibleChatProtocol protocol;
    private ChatCandidate candidate;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        httpClientFactory = new HttpClientFactory();
        protocol = new OpenAiCompatibleChatProtocol(httpClientFactory);
        candidate = new ChatCandidate();
        candidate.setId("test-candidate");
        candidate.setProvider("test-provider");
        candidate.setModel("test-model");
        candidate.setCapability(LlmCapability.CHAT);
    }

    @AfterEach
    void tearDown() throws Exception {
        httpClientFactory.closeAll();
        server.shutdown();
    }

    private ResolvedEndpoint endpoint(String apiKey) {
        return new ResolvedEndpoint(server.url("/v1").toString(), apiKey, "/chat/completions");
    }

    private static MockResponse chatJson() {
        return new MockResponse()
            .setHeader("Content-Type", "application/json")
            .setBody("{\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\","
                + "\"content\":\"你好\"},\"finish_reason\":\"stop\"}],"
                + "\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":2,\"total_tokens\":3}}");
    }

    // ====== 流式错误映射对齐（WS1：P1） ======

    @Test
    @DisplayName("流式·429 + Retry-After → RateLimitedException 携带毫秒值（与阻塞路径映射一致）")
    void streaming429MapsToRateLimited() {
        server.enqueue(new MockResponse().setResponseCode(429)
            .setHeader("Retry-After", "25")
            .setBody("{\"error\":{\"message\":\"rate limited\"}}"));

        Throwable error = reactorStepAwait(() ->
            protocol.chatStream(ChatRequest.of("hi"), candidate, endpoint("sk-test-key"))
                .collectList().block());

        assertThat(error).isInstanceOf(RateLimitedException.class);
        assertThat(((RateLimitedException) error).retryAfterMs()).isEqualTo(25_000L);
    }

    @Test
    @DisplayName("流式·5xx → LLM_TRANSIENT_ERROR（可重试，与阻塞路径一致）")
    void streaming5xxMapsToTransient() {
        server.enqueue(new MockResponse().setResponseCode(503).setBody("unavailable"));

        Throwable error = reactorStepAwait(() ->
            protocol.chatStream(ChatRequest.of("hi"), candidate, endpoint("sk-test-key"))
                .collectList().block());

        assertThat(error).isInstanceOf(RemoteException.class);
        assertThat(((RemoteException) error).getErrorCode()).isEqualTo(RemoteErrorCode.LLM_TRANSIENT_ERROR);
    }

    @Test
    @DisplayName("流式·其余 4xx → LLM_STREAM_ERROR 且消息携带错误体详情")
    void streamingOther4xxMapsToStreamErrorWithBody() {
        server.enqueue(new MockResponse().setResponseCode(400).setBody("{\"error\":\"bad request\"}}"));

        Throwable error = reactorStepAwait(() ->
            protocol.chatStream(ChatRequest.of("hi"), candidate, endpoint("sk-test-key"))
                .collectList().block());

        assertThat(error).isInstanceOf(RemoteException.class);
        assertThat(((RemoteException) error).getErrorCode()).isEqualTo(RemoteErrorCode.LLM_STREAM_ERROR);
        assertThat(error.getMessage()).contains("bad request");
    }

    /** 协议流式在 boundedElastic 上执行，collectList().block() 抛出的可能是 Reactor 包装——解包取根因 */
    private static Throwable reactorStepAwait(java.util.function.Supplier<?> action) {
        try {
            action.get();
            throw new AssertionError("Expected error");
        } catch (Throwable t) {
            Throwable cur = t;
            while (cur.getCause() != null && cur.getCause() != cur) {
                cur = cur.getCause();
            }
            return cur;
        }
    }

    // ====== 阻塞路径：URL/路径/Authorization 头 ======

    @Test
    @DisplayName("阻塞·带 key：目标 URL = baseUrl+endpoint，Authorization = Bearer 值，请求体携带 candidate.model")
    void blockingWithKey() throws Exception {
        server.enqueue(chatJson());

        LlmResponse resp = protocol.chat(ChatRequest.of("hi"), candidate, endpoint("sk-test-key"));

        assertThat(resp.content()).isEqualTo("你好");
        RecordedRequest recorded = server.takeRequest();
        assertThat(recorded.getMethod()).isEqualTo("POST");
        assertThat(recorded.getPath()).isEqualTo("/v1/chat/completions");
        assertThat(recorded.getHeader("Authorization")).isEqualTo("Bearer sk-test-key");
        assertThat(recorded.getBody().readUtf8()).contains("\"model\":\"test-model\"");
    }

    @Test
    @DisplayName("阻塞·keyless：请求不含 Authorization 头（服务器本地无鉴权端点可用）")
    void blockingKeylessOmitsAuthorizationHeader() throws Exception {
        server.enqueue(chatJson());

        LlmResponse resp = protocol.chat(ChatRequest.of("hi"), candidate, endpoint(null));

        assertThat(resp.content()).isEqualTo("你好");
        RecordedRequest recorded = server.takeRequest();
        assertThat(recorded.getHeader("Authorization")).isNull();
    }

    @Test
    @DisplayName("阻塞·blank key：视同 keyless，不发送 Authorization 头")
    void blockingBlankKeyOmitsAuthorizationHeader() throws Exception {
        server.enqueue(chatJson());

        protocol.chat(ChatRequest.of("hi"), candidate, endpoint("  "));

        assertThat(server.takeRequest().getHeader("Authorization")).isNull();
    }

    // ====== 流式路径：URL/路径/Authorization 头 ======

    private static MockResponse sseStream() {
        return new MockResponse()
            .setHeader("Content-Type", "text/event-stream")
            .setBody("data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"Hi\"},\"finish_reason\":null}]}\n\n"
                + "data: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"},"
                + "\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1,\"total_tokens\":2}]}\n\n"
                + "data: [DONE]\n\n");
    }

    @Test
    @DisplayName("流式·带 key：目标 URL 正确，Authorization = Bearer 值，SSE chunk 透传")
    void streamingWithKey() throws Exception {
        server.enqueue(sseStream());

        List<StreamChunk> chunks = protocol.chatStream(ChatRequest.of("hi"), candidate, endpoint("sk-stream-key"))
            .collectList().block(Duration.ofSeconds(10));

        assertThat(chunks).isNotNull();
        assertThat(chunks.stream().anyMatch(StreamChunk::hasText)).isTrue();
        RecordedRequest recorded = server.takeRequest();
        assertThat(recorded.getPath()).isEqualTo("/v1/chat/completions");
        assertThat(recorded.getHeader("Authorization")).isEqualTo("Bearer sk-stream-key");
        assertThat(recorded.getHeader("Accept")).isEqualTo("text/event-stream");
    }

    @Test
    @DisplayName("流式·keyless：请求不含 Authorization 头（现状为无条件拼接，属申报修复）")
    void streamingKeylessOmitsAuthorizationHeader() throws Exception {
        server.enqueue(sseStream());

        protocol.chatStream(ChatRequest.of("hi"), candidate, endpoint(null))
            .collectList().block(Duration.ofSeconds(10));

        assertThat(server.takeRequest().getHeader("Authorization")).isNull();
    }

    // ====== 共享传输（同超时签名同实例，无 per-candidate 构造） ======

    @Test
    @DisplayName("sharedOkHttpClient（含 call 签名）：同签名同实例，不同 call 签名不同实例")
    void sharedOkHttpClientCachedByFullSignature() {
        var a = httpClientFactory.sharedOkHttpClient(Duration.ofSeconds(10), Duration.ofSeconds(120), Duration.ofMillis(150_000));
        var b = httpClientFactory.sharedOkHttpClient(Duration.ofSeconds(10), Duration.ofSeconds(120), Duration.ofMillis(150_000));
        var c = httpClientFactory.sharedOkHttpClient(Duration.ofSeconds(10), Duration.ofSeconds(120), Duration.ofMillis(300_000));

        assertThat(a).isSameAs(b);
        assertThat(c).isNotSameAs(a);
    }

    @Test
    @DisplayName("阻塞·301 重定向不跟随（followRedirects(false) 防漂移断言，决策 11）")
    void blocking301NotFollowed() {
        server.enqueue(new MockResponse().setResponseCode(301)
            .setHeader("Location", server.url("/v1/other").toString()));

        Throwable error = catchRoot(() ->
            protocol.chat(ChatRequest.of("hi"), candidate, endpoint("sk-test-key")));

        assertThat(error).isInstanceOf(RemoteException.class);
        assertThat(((RemoteException) error).getErrorCode()).isEqualTo(RemoteErrorCode.LLM_STREAM_ERROR);
    }

    @Test
    @DisplayName("阻塞·429 + Retry-After → RateLimitedException（OkHttp 阻塞路径映射与流式一致）")
    void blocking429MapsToRateLimited() {
        server.enqueue(new MockResponse().setResponseCode(429)
            .setHeader("Retry-After", "7")
            .setBody("rate limited"));

        Throwable error = catchRoot(() ->
            protocol.chat(ChatRequest.of("hi"), candidate, endpoint("sk-test-key")));

        assertThat(error).isInstanceOf(RateLimitedException.class);
        assertThat(((RateLimitedException) error).retryAfterMs()).isEqualTo(7_000L);
    }

    private static Throwable catchRoot(java.util.function.Supplier<?> action) {
        try {
            action.get();
            throw new AssertionError("Expected error");
        } catch (Throwable t) {
            return t;
        }
    }

    @Test
    @DisplayName("sharedOkHttpClient：同超时签名同实例")
    void sharedOkHttpClientCachedByTimeoutSignature() {
        OkHttpClient a = httpClientFactory.sharedOkHttpClient(Duration.ofSeconds(10), Duration.ofSeconds(120));
        OkHttpClient b = httpClientFactory.sharedOkHttpClient(Duration.ofSeconds(10), Duration.ofSeconds(120));

        assertThat(a).isSameAs(b);
    }

    @Test
    @DisplayName("多候选经协议层共享传输：两个候选的阻塞调用均成功（无 per-candidate HttpClient 生命周期）")
    void multipleCandidatesShareTransport() throws Exception {
        server.enqueue(chatJson());
        server.enqueue(chatJson());

        ChatCandidate other = new ChatCandidate();
        other.setId("other-candidate");
        other.setProvider("test-provider");
        other.setModel("other-model");
        other.setCapability(LlmCapability.CHAT);

        protocol.chat(ChatRequest.of("a"), candidate, endpoint("sk-1"));
        protocol.chat(ChatRequest.of("b"), other, endpoint("sk-2"));

        assertThat(server.takeRequest().getBody().readUtf8()).contains("\"model\":\"test-model\"");
        assertThat(server.takeRequest().getBody().readUtf8()).contains("\"model\":\"other-model\"");
    }

    // ====== 薄壳委托（系统路径经薄壳后协议层线序行为零变化） ======

    @Test
    @DisplayName("GenericChatClient 薄壳：chat 委托协议，candidate/endpoint 闭包生效")
    void thinShellDelegatesBlocking() throws Exception {
        server.enqueue(chatJson());
        GenericChatClient client = new GenericChatClient(endpoint("sk-shell"), candidate, protocol);

        LlmResponse resp = client.chat(ChatRequest.of("hi"));

        assertThat(resp.content()).isEqualTo("你好");
        RecordedRequest recorded = server.takeRequest();
        assertThat(recorded.getPath()).isEqualTo("/v1/chat/completions");
        assertThat(recorded.getHeader("Authorization")).isEqualTo("Bearer sk-shell");
        assertThat(recorded.getBody().readUtf8()).contains("\"model\":\"test-model\"");
    }

    @Test
    @DisplayName("GenericChatClient 薄壳：chatStream 委托协议")
    void thinShellDelegatesStreaming() throws Exception {
        server.enqueue(sseStream());
        GenericChatClient client = new GenericChatClient(endpoint("sk-shell"), candidate, protocol);

        List<StreamChunk> chunks = client.chatStream(ChatRequest.of("hi"))
            .collectList().block(Duration.ofSeconds(10));

        assertThat(chunks).isNotNull();
        assertThat(chunks.stream().anyMatch(StreamChunk::hasText)).isTrue();
        assertThat(server.takeRequest().getHeader("Authorization")).isEqualTo("Bearer sk-shell");
    }

    // ====== URL 拼接边界 ======

    @Test
    @DisplayName("buildUrl：baseUrl 尾斜杠剥离 + endpoint 无前导斜杠补齐")
    void buildUrlNormalizesSlashes() {
        assertThat(OpenAiCompatibleChatProtocol.buildUrl(
            new ResolvedEndpoint("http://localhost:9/v1/", "k", "chat/completions")))
            .isEqualTo("http://localhost:9/v1/chat/completions");
    }

    // ====== ResolvedEndpoint 脱敏（AC6） ======

    @Test
    @DisplayName("ResolvedEndpoint.toString：apiKey 脱敏（**** / null），不出现在日志输出")
    void resolvedEndpointToStringMasksApiKey() {
        assertThat(new ResolvedEndpoint("http://localhost:9", "sk-super-secret", "/chat/completions").toString())
            .contains("****")
            .doesNotContain("sk-super-secret");
        assertThat(new ResolvedEndpoint("http://localhost:9", null, "/chat/completions").toString())
            .contains("apiKey=null")
            .doesNotContain("****");
    }

    @Test
    @DisplayName("ResolvedEndpoint 构造契约：baseUrl/endpoint 非空（null → NPE，构造期 fail-fast）")
    void resolvedEndpointRequiresBaseUrlAndEndpoint() {
        org.junit.jupiter.api.Assertions.assertThrows(NullPointerException.class,
            () -> new ResolvedEndpoint(null, "k", "/chat/completions"));
        org.junit.jupiter.api.Assertions.assertThrows(NullPointerException.class,
            () -> new ResolvedEndpoint("http://localhost:9", "k", null));
    }
}
