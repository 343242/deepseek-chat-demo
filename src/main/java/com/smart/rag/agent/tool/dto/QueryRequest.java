package com.smart.rag.agent.tool.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 检索类 Tool 的通用请求参数
 *
 * @param query 查询文本
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record QueryRequest(String query) {}
