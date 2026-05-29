package com.smart.rag.agent.tool.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * docDetail Tool 请求参数
 *
 * @param docIds    文档 ID 列表（逗号分隔）
 * @param queryText 查询文本（用于 ts_headline 高亮，可空）
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DocDetailRequest(
    String docIds,
    String queryText
) {}
