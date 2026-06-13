package com.smart.rag.infrastructure.llm.strategy;

import com.smart.rag.infrastructure.llm.CapabilityClient;
import com.smart.rag.infrastructure.llm.ChatCapable;
import com.smart.rag.infrastructure.llm.LlmCapability;
import com.smart.rag.infrastructure.llm.ModelCandidate;
import com.smart.rag.infrastructure.llm.ToolCallingCapable;
import com.smart.rag.infrastructure.llm.client.HttpClientFactory;
import com.smart.rag.infrastructure.llm.client.generic.GenericChatClient;
import com.smart.rag.infrastructure.llm.config.ProviderConfig;
import com.smart.rag.infrastructure.llm.metrics.LlmMetrics;
import com.smart.rag.infrastructure.llm.resilience.CircuitBreaker;
import com.smart.rag.infrastructure.llm.resilience.ProbeHandler;
import com.smart.rag.infrastructure.llm.resilience.ResilientChatClient;
import com.smart.rag.infrastructure.llm.resilience.ResilientToolCallingChatClient;
import com.smart.rag.infrastructure.llm.resilience.RetryPolicy;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

/** CHAT 策略 — ChatCapable + ToolCallingCapable 自动检测 */
@Component
public class ChatCapabilityStrategy implements CapabilityStrategy {

    private final HttpClientFactory httpClientFactory;

    public ChatCapabilityStrategy(HttpClientFactory httpClientFactory) {
        this.httpClientFactory = httpClientFactory;
    }

    @Override public LlmCapability capability() { return LlmCapability.CHAT; }

    @Override
    public String resolveEndpoint(ProviderConfig config) {
        return config.getEndpoint(capability());
    }

    @Override
    public CapabilityClient createClient(String baseUrl, String endpoint,
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
