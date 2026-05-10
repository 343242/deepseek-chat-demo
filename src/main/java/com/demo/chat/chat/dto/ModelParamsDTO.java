package com.demo.chat.chat.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * 模型参数配置 DTO（用于 API 请求/响应）
 * <p>
 * 校验规则确保参数在合理范围内：
 * <ul>
 *   <li>temperature: 0.0 ~ 2.0</li>
 *   <li>maxTokens: 1 ~ 128000</li>
 *   <li>topP: 0.0 ~ 1.0</li>
 *   <li>frequencyPenalty: -2.0 ~ 2.0</li>
 *   <li>presencePenalty: -2.0 ~ 2.0</li>
 * </ul>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ModelParamsDTO(
    String modelId,

    @DecimalMin(value = "0.0", message = "temperature 最小为 0.0")
    @DecimalMax(value = "2.0", message = "temperature 最大为 2.0")
    Double temperature,

    @Min(value = 1, message = "maxTokens 最小为 1")
    @Max(value = 128000, message = "maxTokens 最大为 128000")
    Integer maxTokens,

    @DecimalMin(value = "0.0", message = "topP 最小为 0.0")
    @DecimalMax(value = "1.0", message = "topP 最大为 1.0")
    Double topP,

    @DecimalMin(value = "-2.0", message = "frequencyPenalty 最小为 -2.0")
    @DecimalMax(value = "2.0", message = "frequencyPenalty 最大为 2.0")
    Double frequencyPenalty,

    @DecimalMin(value = "-2.0", message = "presencePenalty 最小为 -2.0")
    @DecimalMax(value = "2.0", message = "presencePenalty 最大为 2.0")
    Double presencePenalty
) {}
