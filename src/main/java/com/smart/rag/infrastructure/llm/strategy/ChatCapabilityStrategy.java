package com.smart.rag.infrastructure.llm.strategy;

import com.smart.rag.infrastructure.llm.CapabilityClient;
import com.smart.rag.infrastructure.llm.ChatCapable;
import com.smart.rag.infrastructure.llm.LlmCapability;
import com.smart.rag.infrastructure.llm.ModelCandidate;
import com.smart.rag.infrastructure.llm.client.generic.GenericChatClient;
import com.smart.rag.infrastructure.llm.config.ProviderConfig;
import com.smart.rag.infrastructure.llm.resilience.CircuitBreaker;
import com.smart.rag.infrastructure.llm.resilience.ProbeHandler;
import com.smart.rag.infrastructure.llm.resilience.RetryPolicy;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

/** CHAT 策略 — ChatCapable + ToolCallingCapable 自动检测 */
@Component
public class ChatCapabilityStrategy implements CapabilityStrategy {

    @Override public LlmCapability capability() { return LlmCapability.CHAT; }

    @Override
    public String resolveEndpoint(ProviderConfig config) {
        return config.endpoints().get(capability().name());
    }

    @Override
    public CapabilityClient createClient(String baseUrl, String endpoint,
                                          String apiKey, ModelCandidate candidate) {
        return new GenericChatClient(baseUrl, endpoint, apiKey, candidate);
    }

    @Override
    public CapabilityClient wrapWithResilience(CapabilityClient raw,
                                                CircuitBreaker cb,
                                                RetryPolicy retry,
                                                @Nullable ProbeHandler probe) {
        return new com.smart.rag.infrastructure.llm.resilience.ResilientChatClient(
            (ChatCapable) raw, cb, retry, probe);
    }
}
