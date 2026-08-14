package com.smart.rag.rag.service;

/**
 * 预览输出策略（设计 §4）。由 {@link DocumentPreviewPolicy} 按规范 MIME 与文件大小产出，
 * preview 端点与 {@code DocumentDTO.previewable} 共用同一决策。
 */
public sealed interface PreviewStrategy permits
        PreviewStrategy.PassThrough, PreviewStrategy.Transform, PreviewStrategy.Deny {

    /** 透传路径（PDF）：浏览器内置阅读器渲染，后端惰性流输出并支持 Range */
    record PassThrough(String responseContentType) implements PreviewStrategy {}

    /** 渲染路径（TXT/MD/HTML）：有界全量读取后编码检测/渲染/净化，统一 UTF-8 输出，不支持 Range */
    record Transform(String responseContentType, TransformKind kind, long maxInputBytes)
            implements PreviewStrategy {}

    /** 拒绝预览（OOXML 只允许下载，或文本超出预览大小上限） */
    record Deny(DenyReason reason) implements PreviewStrategy {}
}
