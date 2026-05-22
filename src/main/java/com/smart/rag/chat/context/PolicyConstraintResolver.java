package com.smart.rag.chat.context;

/**
 * 策略约束解析策略
 * <p>
 * 基于用户画像和请求参数，生成回答时应遵守的约束列表。
 * 这是 CAG 中最核心的策略——将业务规则转化为 LLM 可理解的指令。
 */
public interface PolicyConstraintResolver {

    /**
     * 解析策略约束
     *
     * @param user       用户画像（可能为 null，表示解析失败时的降级）
     * @param ragEnabled 是否启用 RAG 检索
     * @return 策略约束
     */
    PolicyContext resolve(UserContext user, boolean ragEnabled);
}
