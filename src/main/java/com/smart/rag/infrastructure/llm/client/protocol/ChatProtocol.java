package com.smart.rag.infrastructure.llm.client.protocol;

import com.smart.rag.infrastructure.llm.ChatRequest;
import com.smart.rag.infrastructure.llm.LlmResponse;
import com.smart.rag.infrastructure.llm.ModelCandidate;
import com.smart.rag.infrastructure.llm.ResolvedEndpoint;
import com.smart.rag.infrastructure.llm.StreamChunk;
import reactor.core.publisher.Flux;

/**
 * Chat 协议 SPI — 无状态单例，凭据与端点是方法入参（design llm-client-stateless §1 决策 2）。
 * <p>
 * 协议实现不持有任何凭据/端点字段：阻塞与流式传输均为共享实例（按超时签名缓存，
 * 由 {@code HttpClientFactory} 统一管理生命周期），每请求绝对 URL + 显式 Authorization 头
 * （apiKey 缺省时不发送该头）。
 * <p>
 * {@code candidate}（模型名/params/思考配置）同为方法入参——请求体构建需要它，
 * 而协议是全员共享的单例，候选身份只能随请求传入。
 * <p>
 * {@link #id()} 为 resilience §10 bailian 协议归并（已申报后续项）预留的协议标识。
 */
public interface ChatProtocol {

    /** 协议标识（如 {@code openai-compatible}） */
    String id();

    /**
     * 阻塞补全。
     *
     * @param request  对话请求
     * @param candidate 候选（模型名/params/思考配置，请求体构建来源）
     * @param endpoint 已解析端点（baseUrl + 可缺省 apiKey + 端点路径）
     */
    LlmResponse chat(ChatRequest request, ModelCandidate candidate, ResolvedEndpoint endpoint);

    /**
     * 流式补全（SSE）。
     *
     * @see #chat
     */
    Flux<StreamChunk> chatStream(ChatRequest request, ModelCandidate candidate, ResolvedEndpoint endpoint);
}
