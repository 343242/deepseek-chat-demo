package com.smart.rag.infrastructure.llm.strategy;

import com.smart.rag.infrastructure.llm.CapabilityClient;
import com.smart.rag.infrastructure.llm.LlmCapability;
import com.smart.rag.infrastructure.llm.ModelCandidate;
import com.smart.rag.infrastructure.llm.RerankCapable;
import com.smart.rag.infrastructure.llm.client.generic.GenericRerankClient;
import com.smart.rag.infrastructure.llm.config.ProviderConfig;
import com.smart.rag.infrastructure.llm.resilience.CircuitBreaker;
import com.smart.rag.infrastructure.llm.resilience.ProbeHandler;
import com.smart.rag.infrastructure.llm.resilience.RetryPolicy;
import com.smart.rag.infrastructure.llm.resilience.ResilientRerankClient;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

/** RERANKING 策略 */
@Component
public class RerankCapabilityStrategy implements CapabilityStrategy {

    @Override public LlmCapability capability() { return LlmCapability.RERANKING; }

    @Override
    public String resolveEndpoint(ProviderConfig config) {
        return config.endpoints().get(capability().name());
    }

    @Override
    public CapabilityClient createClient(String baseUrl, String endpoint,
                                          String apiKey, ModelCandidate candidate) {
        return new GenericRerankClient(baseUrl, endpoint, apiKey, candidate);
    }

    @Override
    public CapabilityClient wrapWithResilience(CapabilityClient raw,
                                                CircuitBreaker cb,
                                                RetryPolicy retry,
                                                @Nullable ProbeHandler probe) {
        return new ResilientRerankClient((RerankCapable) raw, cb, retry);
    }
}
