package com.smart.rag.chat.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.smart.rag.infrastructure.llm.CapabilityClient;

/**
 * 模型展示 VO（模型目录用）。
 * <p>
 * 比裸 candidateId 字符串多携带 provider / 原始模型名 / 能力标签 / 可用状态，
 * 供前端模型选择器和能力筛选渲染。对应端点 {@code GET /api/models/detail}。
 *
 * @param id         候选唯一标识（对应 YAML candidate.id，请求时用此值）
 * @param provider   供应商 ID（如 "deepseek"、"zhipu"）
 * @param model      发给 LLM API 的原始模型名（如 "deepseek-chat"）
 * @param capability 能力标签：CHAT / EMBEDDING / RERANKING
 * @param available  是否可用（未被运行时禁用）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ModelVO(
    String id,
    String provider,
    String model,
    String capability,
    boolean available
) {

    public static ModelVO of(CapabilityClient client, boolean available) {
        return new ModelVO(
            client.candidateId(),
            client.providerId(),
            client.modelName(),
            client.capability() != null ? client.capability().name() : null,
            available
        );
    }
}
