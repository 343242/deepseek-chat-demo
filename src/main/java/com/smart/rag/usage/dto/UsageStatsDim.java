package com.smart.rag.usage.dto;

/**
 * 聚合分组维度 — GET /api/usage/stats?dim=
 * <p>
 * 枚举绑定即白名单：非法值在参数转换期被拒绝，不进入 SQL。
 * USER 维度跨用户聚合，仅管理员（usage:view:all）可用（Controller @PreAuthorize 限定）。
 */
public enum UsageStatsDim {
    MODEL,
    SCENE,
    USER
}
