package com.smart.rag.agent.intent;

import java.util.List;

/**
 * 意图分类结果
 *
 * @param intent     意图分类
 * @param confidence 分类置信度
 * @param subQueries 分解后的子问题列表（第一版始终为空列表，后续迭代启用查询分解）
 */
public record IntentResult(
    AgentIntent intent,
    double confidence,
    List<String> subQueries
) {
    /** 是否需要查询分解 */
    public boolean hasSubQueries() {
        return subQueries != null && !subQueries.isEmpty();
    }
}
