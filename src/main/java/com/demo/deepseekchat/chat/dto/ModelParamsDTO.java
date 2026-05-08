package com.demo.deepseekchat.chat.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 模型参数配置 DTO（用于 API 请求/响应）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ModelParamsDTO(
    String modelId,
    Double temperature,
    Integer maxTokens,
    Double topP,
    Double frequencyPenalty,
    Double presencePenalty
) {}
