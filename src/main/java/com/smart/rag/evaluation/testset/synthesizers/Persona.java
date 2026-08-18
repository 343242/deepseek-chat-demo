package com.smart.rag.evaluation.testset.synthesizers;

/**
 * 出题 persona（对应 ragas {@code Persona}）。由配置固定注入，不 LLM 生成。
 */
public record Persona(String name, String roleDescription) {
}
