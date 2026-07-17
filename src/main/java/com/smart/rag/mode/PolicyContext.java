package com.smart.rag.mode;

import java.util.List;

/**
 * 策略约束 — 回答应遵守的规则列表
 *
 * @param constraints  约束文本列表（注入 LLM prompt）
 * @param ragRestricted 是否限制 RAG 检索范围（预留，当前版本不使用）
 */
public record PolicyContext(
        List<String> constraints,
        boolean ragRestricted
) {
}
