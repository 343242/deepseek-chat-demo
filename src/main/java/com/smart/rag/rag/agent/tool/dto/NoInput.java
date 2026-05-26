package com.smart.rag.rag.agent.tool.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 无参数 Tool 的空输入标记类型。
 * <p>
 * 用于 parentDocLookup、knowledgeBaseInfo 等不需要用户输入的工具，
 * 使 FunctionToolCallback 的 inputType 与工具语义一致。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record NoInput() {}
