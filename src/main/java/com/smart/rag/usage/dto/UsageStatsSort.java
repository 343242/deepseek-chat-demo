package com.smart.rag.usage.dto;

/**
 * 聚合排序键 — GET /api/usage/stats?sort=
 * <p>
 * 枚举绑定即白名单；XML 内 &lt;choose&gt; 映射到聚合别名列，不拼接用户输入。
 */
public enum UsageStatsSort {
    TOTAL_TOKENS,
    REQUEST_COUNT,
    AVG_DURATION_MS
}
