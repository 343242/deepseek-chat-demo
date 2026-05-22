package com.smart.rag.chat.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * DeepSeek /models API 的响应体
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ModelsResponse(
    String object,
    List<ModelInfo> data
) {}
