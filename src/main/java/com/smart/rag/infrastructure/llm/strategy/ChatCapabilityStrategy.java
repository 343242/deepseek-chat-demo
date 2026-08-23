package com.smart.rag.infrastructure.llm.strategy;

import com.smart.rag.infrastructure.llm.CapabilityClient;
import com.smart.rag.infrastructure.llm.ChatCapable;
import com.smart.rag.infrastructure.llm.LlmCapability;
import com.smart.rag.infrastructure.llm.ToolCallingCapable;
import com.smart.rag.infrastructure.llm.client.HttpClientFactory;
import com.smart.rag.infrastructure.llm.client.generic.GenericChatClient;
import com.smart.rag.infrastructure.llm.metrics.LlmMetrics;
import com.smart.rag.infrastructure.llm.ModelCandidate;
import com.smart.rag.infrastructure.llm.resilience.CircuitBreaker;
import com.smart.rag.infrastructure.llm.resilience.ProbeHandler;
import com.smart.rag.infrastructure.llm.resilience.ResilientChatClient;
import com.smart.rag.infrastructure.llm.resilience.ResilientToolCallingChatClient;
import com.smart.rag.infrastructure.llm.resilience.RetryPolicy;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * CHAT 策略 — 含 ProviderClientFactory 自动发现 + ChatCapable/ToolCallingCapable 自动检测
 * <p>
 * 与 Embedding/Rerank 同构（{@link AbstractProviderFactoryAwareStrategy}）：
 * 当 {@link ProviderClientFactory} 注册了 {@code providerId:CHAT} 工厂时（如百炼
 * {@code BailianChatClientFactory}，DashScope 原生协议 SDK 客户端），委托工厂创建；
 * 否则使用通用 {@link GenericChatClient}（OpenAI 兼容 API）。
 */
@Component
public class ChatCapabilityStrategy extends AbstractProviderFactoryAwareStrategy {

    private final HttpClientFactory httpClientFactory;

    public ChatCapabilityStrategy(List<ProviderClientFactory> factories,
                                   HttpClientFactory httpClientFactory) {
        super(factories);
        this.httpClientFactory = httpClientFactory;
    }

    @Override public LlmCapability capability() { return LlmCapability.CHAT; }

    @Override
    protected CapabilityClient createGenericClient(String baseUrl, String endpoint,
                                                    String apiKey, ModelCandidate candidate) {
        return new GenericChatClient(baseUrl, endpoint, apiKey, candidate, httpClientFactory);
    }

    @Override
    public CapabilityClient wrapWithResilience(CapabilityClient raw,
                                                CircuitBreaker cb,
                                                RetryPolicy retry,
                                                @Nullable ProbeHandler probe,
                                                @Nullable LlmMetrics metrics) {
        if (!(raw instanceof ChatCapable chatCapable)) {
            throw new IllegalStateException(
                "CHAT client '" + raw.candidateId() + "' does not implement ChatCapable");
        }
        ResilientChatClient resilient = new ResilientChatClient(
            chatCapable, cb, retry, probe, metrics);
        if (raw instanceof ToolCallingCapable) {
            return new ResilientToolCallingChatClient(resilient);
        }
        return resilient;
    }
}
