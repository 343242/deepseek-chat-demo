package com.smart.rag.modelconfig.dto;

import com.smart.rag.modelconfig.entity.LlmModelConfig;
import java.time.OffsetDateTime;

/**
 * BYOK 配置回显 VO（design §12.2 — 脱敏，不含明文 api_key）。
 * <p>
 * {@code apiKeyMasked} 为 {@code <prefix>***<last4>}（如 {@code sk-***5678}），
 * 由 {@code LlmModelConfigService.maskKey} 生成；解密失败回 {@code ****}。
 */
public record LlmConfigVO(
        Long id,
        Long userId,
        String capabilityType,
        String providerCode,
        String baseUrl,
        String modelName,
        String displayName,
        Integer dimension,
        Boolean supportsStreaming,
        Boolean supportsThinking,
        Integer priority,
        Boolean isDefault,
        Integer status,
        String apiKeyMasked,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
    public static LlmConfigVO from(LlmModelConfig e, String apiKeyMasked) {
        return new LlmConfigVO(
                e.getId(), e.getUserId(), e.getCapabilityType(),
                e.getProviderCode(), e.getBaseUrl(), e.getModelName(), e.getDisplayName(),
                e.getDimension(), e.getSupportsStreaming(), e.getSupportsThinking(),
                e.getPriority(), e.getIsDefault(), e.getStatus(), apiKeyMasked,
                e.getCreatedAt(), e.getUpdatedAt());
    }
}
