package com.smart.rag.infrastructure.llm.client.generic;

import com.smart.rag.infrastructure.llm.ChatRequest;
import com.smart.rag.infrastructure.llm.LlmResponse;
import com.smart.rag.infrastructure.llm.ModelCandidate;
import com.smart.rag.infrastructure.llm.ResolvedEndpoint;
import com.smart.rag.infrastructure.llm.StreamChunk;
import com.smart.rag.infrastructure.llm.client.AbstractChatClient;
import com.smart.rag.infrastructure.llm.client.protocol.ChatProtocol;
import reactor.core.publisher.Flux;

import java.util.Objects;

/**
 * 通用 Chat 客户端薄壳（OpenAI 兼容 API）— 端点绑定 + 协议委托（design llm-client-stateless §4 WS-A）。
 * <p>
 * 协议逻辑（请求体构建 / SSE 读取 / 响应解析）已抽出至共享的无状态
 * {@link ChatProtocol} 实现（{@code OpenAiCompatibleChatProtocol}，Spring 单例）；
 * 本类仅闭包启动期静态解析的 {@link ResolvedEndpoint}（凭据是端点数据，不是对象身份），
 * 携 candidate 委托协议执行。
 * <p>
 * 阻塞与流式传输均为协议层共享实例（按超时签名缓存，{@code HttpClientFactory} 统一
 * 管理生命周期），本类无可关闭资源。
 */
public class GenericChatClient extends AbstractChatClient {

    private final ResolvedEndpoint endpoint;
    private final ChatProtocol protocol;

    public GenericChatClient(ResolvedEndpoint endpoint, ModelCandidate candidate, ChatProtocol protocol) {
        super(Objects.requireNonNull(candidate, "candidate must not be null"), candidate.provider());
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint must not be null");
        this.protocol = Objects.requireNonNull(protocol, "protocol must not be null");
    }

    @Override
    public LlmResponse chat(ChatRequest request) {
        return protocol.chat(request, candidate, endpoint);
    }

    @Override
    public Flux<StreamChunk> chatStream(ChatRequest request) {
        return protocol.chatStream(request, candidate, endpoint);
    }

    @Override
    public void close() {
        // 共享传输（RestClient/OkHttp）由 HttpClientFactory.closeAll() 统一管理，薄壳无可关闭资源
    }
}
