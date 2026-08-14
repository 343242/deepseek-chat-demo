package com.smart.rag.rag.service;

import com.smart.rag.rag.config.DocumentProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;

/**
 * 预览输出策略单点（设计 §4）。
 * <p>
 * 按规范 MIME 与文件大小产出 {@link PreviewStrategy}；{@code previewable} 与
 * preview 端点共用本策略，保证列表提示与端点行为一致。
 * 透传（PDF）不受大小限制；渲染路径的输入上限来自
 * {@code app.document.max-preview-file-size}（默认 5MB），只影响文本预览，
 * 不影响原文件下载。
 */
@Component
public class DocumentPreviewPolicy {

    private final long maxPreviewBytes;

    public DocumentPreviewPolicy(DocumentProperties documentProperties) {
        this.maxPreviewBytes = DataSize.parse(documentProperties.getMaxPreviewFileSize()).toBytes();
    }

    /** 文本预览的输入大小上限（字节） */
    public long maxPreviewBytes() {
        return maxPreviewBytes;
    }

    /**
     * 按规范 MIME 与文件大小解析输出策略。
     * <p>
     * 大小参数应为「数据库声明大小与 MinIO stat 真实大小的较大值」（保守判定）；
     * 仅渲染路径参与大小判定，透传路径忽略大小。
     */
    public PreviewStrategy strategyFor(String canonicalMimeType, long conservativeSizeBytes) {
        return switch (canonicalMimeType == null ? "" : canonicalMimeType) {
            case DocumentMimePolicy.MIME_PDF ->
                    new PreviewStrategy.PassThrough(DocumentMimePolicy.MIME_PDF);
            case DocumentMimePolicy.MIME_TXT -> transform("text/plain; charset=UTF-8",
                    TransformKind.DETECT_CHARSET, conservativeSizeBytes);
            case DocumentMimePolicy.MIME_MARKDOWN -> transform("text/html; charset=UTF-8",
                    TransformKind.RENDER_MARKDOWN, conservativeSizeBytes);
            case DocumentMimePolicy.MIME_HTML -> transform("text/html; charset=UTF-8",
                    TransformKind.SANITIZE_HTML, conservativeSizeBytes);
            default -> new PreviewStrategy.Deny(DenyReason.UNSUPPORTED_TYPE);
        };
    }

    /**
     * {@code DocumentDTO.previewable} 的计算入口：策略为 PassThrough 或 Transform 时为 true。
     */
    public boolean previewable(String canonicalMimeType, long fileSizeBytes) {
        PreviewStrategy strategy = strategyFor(canonicalMimeType, fileSizeBytes);
        return strategy instanceof PreviewStrategy.PassThrough || strategy instanceof PreviewStrategy.Transform;
    }

    private PreviewStrategy transform(String responseContentType, TransformKind kind, long sizeBytes) {
        if (sizeBytes > maxPreviewBytes) {
            return new PreviewStrategy.Deny(DenyReason.PREVIEW_TOO_LARGE);
        }
        return new PreviewStrategy.Transform(responseContentType, kind, maxPreviewBytes);
    }
}
