package com.smart.rag.user.dto;

import java.time.OffsetDateTime;

/**
 * 用户视图对象（管理后台列表/详情展示）
 * <p>
 * 替代原来 toSafeMap 返回的 Map<String, Object>。
 * 只暴露安全字段，不含 password。
 */
public record UserVO(
    Long id,
    String username,
    String nickname,
    String email,
    String phone,
    String avatar,
    Integer status,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt
) {}
