package com.smart.rag.chat.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 多 Provider 场景下的模型信息（增强版 ModelInfo）
 * <p>
 * 在原有 ModelInfo 基础上增加 providerId 和 displayName，
 * 用于多厂商聚合场景下的模型展示和路由。
 * <p>
 * 与 ModelInfo 的关系：
 * <ul>
 *   <li>ModelInfo — 厂商 API 返回的原始模型数据（JSON 反序列化用）</li>
 *   <li>ProviderModelInfo — 聚合后的展示数据（含厂商信息，面向前端）</li>
 * </ul>
 * <p>
 * 设计决策：不修改原有 ModelInfo record，避免破坏 DeepSeek API 的 JSON 反序列化。
 * 这是适配器模式的体现：将厂商特有的数据结构转换为统一的展示结构。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ProviderModelInfo(
        /** 模型 ID（如 "deepseek-chat"、"glm-4-air"） */
        String id,

        /** 厂商 ID（如 "deepseek"、"zhipu"） */
        String providerId,

        /** 厂商显示名称（如 "DeepSeek"、"智谱 AI"） */
        String providerName,

        /** 复合 ID（如 "deepseek/deepseek-chat"），用于 API 请求 */
        String compositeId,

        /** 模型归属方 */
        String ownedBy,

        /** 创建时间戳 */
        Long created
) {
    /**
     * 从 ModelInfo + Provider 信息构建
     */
    public static ProviderModelInfo from(ModelInfo modelInfo, String providerId, String providerName) {
        return new ProviderModelInfo(
                modelInfo.id(),
                providerId,
                providerName,
                providerId + "/" + modelInfo.id(),
                modelInfo.ownedBy(),
                modelInfo.created()
        );
    }
}
