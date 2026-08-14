package com.smart.rag.rag.service;

import org.jspecify.annotations.Nullable;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;

/**
 * preview / download 的传输结果封闭类型（设计 §7）。
 * <p>
 * {@link RangeNotSatisfiable} 是传输协议结果，直接由 Controller 映射为真实 HTTP 416，
 * 不经过全局异常处理器。权限、文档不存在、不可预览、预览过大和对象存储失败仍走
 * 项目现有业务异常响应。
 */
public sealed interface DocumentFileResult permits
        DocumentFileResult.Body, DocumentFileResult.Metadata, DocumentFileResult.RangeNotSatisfiable {

    /** GET 成功结果：携带惰性（透传）或已生成（渲染）的 Resource */
    record Body(
            HttpStatus status,
            Resource resource,
            long contentLength,
            long totalSize,
            long offset,
            String responseContentType,
            String fileName,
            Disposition disposition,
            RangeCapability rangeCapability
    ) implements DocumentFileResult {}

    /** HEAD 元数据结果：不含内容流；渲染路径 contentLength 为 null（不渲染即不知长度） */
    record Metadata(
            HttpStatus status,
            @Nullable Long contentLength,
            String responseContentType,
            String fileName,
            Disposition disposition,
            RangeCapability rangeCapability
    ) implements DocumentFileResult {}

    /** 透传 GET 的范围不可满足（起点越界或空范围）：Controller 构造真实 416 */
    record RangeNotSatisfiable(long totalSize) implements DocumentFileResult {}
}
