package com.smart.rag.infrastructure.ai.model;

/**
 * Provider-neutral model option settings.
 * <p>
 * This value object is the boundary type accepted by AI infrastructure. Business
 * modules may load these values from any source, but infrastructure must not
 * depend on their persistence entity.
 */
public record ModelOptionSettings(
        Double temperature,
        Integer maxTokens,
        Double topP,
        Double frequencyPenalty,
        Double presencePenalty
) {}
