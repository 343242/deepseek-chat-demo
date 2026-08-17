package com.smart.rag.usage.dto;

import org.jspecify.annotations.Nullable;

import java.time.OffsetDateTime;

/**
 * 用量查询过滤条件 — 四个查询端点共用。
 * <p>
 * {@code userId} 为目标用户：本人查询时由 Controller 填当前登录用户；
 * 管理员（usage:view:all）可传任意 userId 查他人。null 表示不过滤（仅 dim=USER 全员聚合时出现，
 * 该场景由 Controller 的 @PreAuthorize 限定为管理员）。
 * <p>
 * {@code scene} 为场景名字符串（白名单由 Controller 的 UsageScene 枚举绑定保证）——
 * SQL 绑定表达式（#{}）按 getter 反射解析，不支持枚举方法调用，Mapper 层一律用 String。
 */
public record UsageQueryFilter(
    @Nullable Long userId,
    @Nullable String scene,
    @Nullable String model,
    @Nullable String conversation,
    @Nullable OffsetDateTime start,
    @Nullable OffsetDateTime end
) {
}
