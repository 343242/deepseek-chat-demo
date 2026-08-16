package com.smart.rag.rag.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 批量删除文档请求
 *
 * @param ids 待删除文档 ID 列表（service 层去重）
 */
public record BatchDeleteRequest(
        @NotEmpty(message = "文档 ID 列表不能为空")
        @Size(max = 50, message = "单次最多删除 50 个文档")
        List<@NotNull(message = "文档 ID 不能为空") @Positive(message = "文档 ID 必须为正数") Long> ids
) {}
