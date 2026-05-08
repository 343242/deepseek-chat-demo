package com.demo.deepseekchat.chat.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * DeepSeek /models API 返回的模型信息
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ModelInfo(
    String id,
    String object,
    long created,

    @JsonProperty("owned_by")
    String ownedBy
) {}
