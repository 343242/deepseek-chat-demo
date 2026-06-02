package com.smart.rag.infrastructure.fallback;

/**
 * 动态模型候选配置
 *
 * @param id               候选唯一标识
 * @param provider         提供商名称
 * @param model            模型名称
 * @param priority         优先级（越小越优先），默认 100
 * @param enabled          是否启用，默认 true
 * @param supportsThinking 是否支持思考模式，默认 false
 */
public record ModelCandidate(
        String id,
        String provider,
        String model,
        int priority,
        boolean enabled,
        boolean supportsThinking
) {

    public ModelCandidate {
        if (priority <= 0) {
            priority = 100;
        }
    }

    /**
     * 复合模型 ID：provider/model
     */
    public String compositeId() {
        return provider + "/" + model;
    }
}
