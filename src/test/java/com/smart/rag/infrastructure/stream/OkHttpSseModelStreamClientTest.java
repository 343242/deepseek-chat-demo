package com.smart.rag.infrastructure.stream;

import com.smart.rag.infrastructure.provider.ModelRouter;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import okio.BufferedSource;
import okio.Timeout;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("OkHttpSseModelStreamClient")
class OkHttpSseModelStreamClientTest {

    @Test
    @DisplayName("parses OpenAI compatible SSE chunks and ignores done marker")
    void parsesSseChunks() {
        var callFactory = new CapturingCallFactory(successResponse("""
                data: {"choices":[{"delta":{"content":"你"}}]}
                
                data: {"choices":[{"delta":{"content":"好"}}]}
                
                data: [DONE]
                
                """));
        var client = new OkHttpSseModelStreamClient(callFactory);

        List<String> chunks = client.stream(streamRequest()).collectList().block();

        assertThat(chunks).containsExactly("你", "好");
        assertThat(callFactory.lastCall().cancelled()).isFalse();
        assertThat(callFactory.lastCall().responseClosed()).isTrue();
    }

    @Test
    @DisplayName("closes response body when JSON chunk is malformed")
    void closesResponseOnMalformedJson() {
        var callFactory = new CapturingCallFactory(successResponse("""
                data: {"choices":[{"delta":{"content":"ok"}}]}
                
                data: {
                
                """));
        var client = new OkHttpSseModelStreamClient(callFactory);

        assertThatThrownBy(() -> client.stream(streamRequest()).collectList().block())
                .hasMessageContaining("模型流式响应解析失败");
        assertThat(callFactory.lastCall().responseClosed()).isTrue();
    }

    @Test
    @DisplayName("cancels OkHttp call when downstream cancels subscription")
    void cancelsCallOnSubscriptionCancel() {
        var body = """
                data: {"choices":[{"delta":{"content":"first"}}]}
                
                data: {"choices":[{"delta":{"content":"second"}}]}
                
                """;
        var callFactory = new CapturingCallFactory(successResponse(body));
        var client = new OkHttpSseModelStreamClient(callFactory);

        client.stream(streamRequest()).take(1).collectList().block();

        assertThat(callFactory.lastCall().cancelled()).isTrue();
        assertThat(callFactory.lastCall().responseClosed()).isTrue();
    }

    private static ModelStreamRequest streamRequest() {
        return new ModelStreamRequest(
                new ModelRouter.Route("deepseek", "deepseek-chat"),
                "hello",
                "https://api.example.test/v1",
                "/chat/completions",
                "sk-test",
                null,
                null);
    }

    private static Response successResponse(String body) {
        return new Response.Builder()
                .request(new Request.Builder().url("https://api.example.test/v1/chat/completions").build())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(new TrackingResponseBody(body))
                .build();
    }

    private static final class CapturingCallFactory implements Call.Factory {
        private final Response response;
        private CapturingCall call;

        private CapturingCallFactory(Response response) {
            this.response = response;
        }

        @Override
        public Call newCall(Request request) {
            call = new CapturingCall(request, response);
            return call;
        }

        CapturingCall lastCall() {
            return call;
        }
    }

    private static final class CapturingCall implements Call {
        private final Request request;
        private final Response response;
        private final AtomicBoolean cancelled = new AtomicBoolean(false);
        private final AtomicReference<Response> executedResponse = new AtomicReference<>();

        private CapturingCall(Request request, Response response) {
            this.request = request;
            this.response = response;
        }

        @Override
        public Request request() {
            return request;
        }

        @Override
        public Response execute() throws IOException {
            executedResponse.set(response);
            return response;
        }

        @Override
        public void enqueue(Callback responseCallback) {
            throw new UnsupportedOperationException("async execution is not used");
        }

        @Override
        public void cancel() {
            cancelled.set(true);
        }

        @Override
        public boolean isExecuted() {
            return executedResponse.get() != null;
        }

        @Override
        public boolean isCanceled() {
            return cancelled.get();
        }

        @Override
        public Call clone() {
            return new CapturingCall(request, response);
        }

        @Override
        public Timeout timeout() {
            return Timeout.NONE;
        }

        boolean cancelled() {
            return cancelled.get();
        }

        boolean responseClosed() {
            Response executed = executedResponse.get();
            return executed != null
                    && executed.body() instanceof TrackingResponseBody body
                    && body.closed();
        }
    }

    private static final class TrackingResponseBody extends ResponseBody {
        private final Buffer buffer;
        private final MediaType mediaType = MediaType.get("text/event-stream");
        private final AtomicBoolean closed = new AtomicBoolean(false);

        private TrackingResponseBody(String body) {
            this.buffer = new Buffer().writeUtf8(body);
        }

        @Override
        public MediaType contentType() {
            return mediaType;
        }

        @Override
        public long contentLength() {
            return buffer.size();
        }

        @Override
        public BufferedSource source() {
            return buffer;
        }

        @Override
        public void close() {
            closed.set(true);
            super.close();
        }

        boolean closed() {
            return closed.get();
        }
    }
}
