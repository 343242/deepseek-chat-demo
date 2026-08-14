package com.smart.rag.rag.service;

import com.smart.rag.infrastructure.exception.ClientException;
import com.smart.rag.infrastructure.exception.RemoteException;
import com.smart.rag.infrastructure.exception.errorcode.ClientErrorCode;
import com.smart.rag.infrastructure.exception.errorcode.RemoteErrorCode;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpRange;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * 文件应用服务（设计 §7）：preview / download 的统一编排。
 * <p>
 * 流程：授权（authorizeFileRead，复用统一权限判断）→ 预览策略（先按规范 MIME 拒绝
 * 不支持类型）→ MinIO stat（与声明大小比较，取较大值做保守判定）→ 预览策略按真实
 * 大小执行渲染上限 → 分流 HEAD / GET 透传 / GET 渲染。每个 GET 请求最多打开一个
 * MinIO 内容流；HEAD 与预览超限请求不打开内容流。
 * <p>
 * Range 规则（设计 §8，仅透传 GET）：无 Range 或 If-Range 存在或语法错误或合法多段
 * → 忽略 Range 返回完整 200；单段合法 → 206；起点越界或空范围 → 416。
 * 渲染 GET 不支持 Range（带 Range 头时忽略并返回完整渲染结果）。
 */
@Service
public class DocumentFileService {

    private static final Logger log = LoggerFactory.getLogger(DocumentFileService.class);

    /** 请求意图：preview（inline）或 download（attachment） */
    public enum FilePurpose { PREVIEW, DOWNLOAD }

    private final DocumentApplicationService documentApplicationService;
    private final FileStorageService fileStorageService;
    private final DocumentPreviewPolicy previewPolicy;
    private final DocumentRenderService renderService;

    public DocumentFileService(DocumentApplicationService documentApplicationService,
                               FileStorageService fileStorageService,
                               DocumentPreviewPolicy previewPolicy,
                               DocumentRenderService renderService) {
        this.documentApplicationService = documentApplicationService;
        this.fileStorageService = fileStorageService;
        this.previewPolicy = previewPolicy;
        this.renderService = renderService;
    }

    /**
     * HEAD：只返回元数据，绝不打开内容流。透传 HEAD 返回 stat 长度；渲染 HEAD
     * 不渲染内容，也不承诺 Content-Length。
     */
    public DocumentFileResult head(Long documentId, FilePurpose purpose) {
        Prepared prepared = prepare(documentId, purpose);
        DocumentFileResult result = buildMetadata(prepared, purpose);
        log.info("Document file head: documentId={}, purpose={}, mime={}, totalSize={}",
                documentId, purpose, prepared.auth.canonicalMimeType(), prepared.totalSize);
        return result;
    }

    /**
     * GET：透传路径支持单段 Range，渲染路径始终返回完整生成内容。
     *
     * @param rangeHeader  原始 Range 头（可空）
     * @param ifRangeHeader 原始 If-Range 头（可空；存在时忽略 Range）
     */
    public DocumentFileResult get(Long documentId, FilePurpose purpose,
                                  @Nullable String rangeHeader, @Nullable String ifRangeHeader) {
        Prepared prepared = prepare(documentId, purpose);

        if (purpose == FilePurpose.PREVIEW && prepared.strategy instanceof PreviewStrategy.Transform transform) {
            DocumentFileResult result = renderBody(prepared, transform);
            log.info("Document file get(render): documentId={}, bytes={}",
                    documentId, ((DocumentFileResult.Body) result).contentLength());
            return result;
        }
        return passthroughBody(prepared, purpose, rangeHeader, ifRangeHeader);
    }

    // ==================== 内部流程 ====================

    private record Prepared(AuthorizedDocumentFile auth, StoredObjectHandle handle,
                            long totalSize, @Nullable PreviewStrategy strategy) {}

    private Prepared prepare(Long documentId, FilePurpose purpose) {
        AuthorizedDocumentFile auth = documentApplicationService.authorizeFileRead(documentId);

        // 先按规范 MIME 拒绝不支持类型（stat 之前，OOXML 不触碰对象存储即拒绝）
        if (purpose == FilePurpose.PREVIEW
                && previewPolicy.strategyFor(auth.canonicalMimeType(), 0) instanceof PreviewStrategy.Deny deny
                && deny.reason() == DenyReason.UNSUPPORTED_TYPE) {
            throw new ClientException(ClientErrorCode.DOCUMENT_PREVIEW_UNSUPPORTED);
        }

        StoredObjectHandle handle = fileStorageService.open(auth.bucket(), auth.objectKey());
        long totalSize = handle.totalSize();
        if (auth.declaredFileSize() != totalSize) {
            // 不含对象 key 的不一致告警（设计 §7）；传输与策略以 stat 真实大小为准
            log.warn("Document file size mismatch: documentId={}, declared={}, stat={}",
                    documentId, auth.declaredFileSize(), totalSize);
        }
        long conservativeSize = Math.max(auth.declaredFileSize(), totalSize);

        PreviewStrategy strategy = null;
        if (purpose == FilePurpose.PREVIEW) {
            strategy = previewPolicy.strategyFor(auth.canonicalMimeType(), conservativeSize);
            if (strategy instanceof PreviewStrategy.Deny(DenyReason reason)) {
                throw new ClientException(reason == DenyReason.PREVIEW_TOO_LARGE
                        ? ClientErrorCode.DOCUMENT_PREVIEW_TOO_LARGE
                        : ClientErrorCode.DOCUMENT_PREVIEW_UNSUPPORTED);
            }
        }
        return new Prepared(auth, handle, totalSize, strategy);
    }

    private DocumentFileResult buildMetadata(Prepared prepared, FilePurpose purpose) {
        String fileName = prepared.auth.fileName();
        if (prepared.strategy instanceof PreviewStrategy.Transform transform) {
            return new DocumentFileResult.Metadata(HttpStatus.OK, null,
                    transform.responseContentType(), fileName, Disposition.INLINE, RangeCapability.NONE);
        }
        Disposition disposition = purpose == FilePurpose.PREVIEW ? Disposition.INLINE : Disposition.ATTACHMENT;
        String contentType = purpose == FilePurpose.PREVIEW
                ? DocumentMimePolicy.MIME_PDF
                : prepared.auth.canonicalMimeType();
        return new DocumentFileResult.Metadata(HttpStatus.OK, prepared.totalSize,
                contentType, fileName, disposition, RangeCapability.BYTES);
    }

    private DocumentFileResult renderBody(Prepared prepared, PreviewStrategy.Transform transform) {
        StoredObjectContent content = prepared.handle.content(new ObjectReadRange.Full());
        byte[] rendered;
        try (InputStream in = content.resource().getInputStream()) {
            rendered = renderService.render(transform.kind(), in, transform.maxInputBytes(),
                    prepared.auth.fileName());
        } catch (IOException e) {
            // 打开/关闭内容流的故障按存储不可用翻译（读取阶段由 RenderService 翻译）
            throw new RemoteException(RemoteErrorCode.FILE_STORAGE_UNAVAILABLE, "文件存储暂不可用", e);
        }
        return new DocumentFileResult.Body(HttpStatus.OK,
                new ByteArrayResource(rendered),
                rendered.length, rendered.length, 0,
                transform.responseContentType(), prepared.auth.fileName(),
                Disposition.INLINE, RangeCapability.NONE);
    }

    private DocumentFileResult passthroughBody(Prepared prepared, FilePurpose purpose,
                                               @Nullable String rangeHeader, @Nullable String ifRangeHeader) {
        Disposition disposition = purpose == FilePurpose.PREVIEW ? Disposition.INLINE : Disposition.ATTACHMENT;
        String contentType = purpose == FilePurpose.PREVIEW
                ? DocumentMimePolicy.MIME_PDF
                : prepared.auth.canonicalMimeType();
        String fileName = prepared.auth.fileName();
        long totalSize = prepared.totalSize;

        List<HttpRange> ranges = resolveRanges(rangeHeader, ifRangeHeader);
        if (ranges.size() == 1) {
            HttpRange range = ranges.get(0);
            long start = range.getRangeStart(totalSize);
            long end = range.getRangeEnd(totalSize); // inclusive
            if (start >= totalSize || end < start) {
                log.info("Document file range not satisfiable: documentId-size={}, start={}, end={}",
                        totalSize, start, end);
                return new DocumentFileResult.RangeNotSatisfiable(totalSize);
            }
            long length = end - start + 1;
            StoredObjectContent content = prepared.handle.content(new ObjectReadRange.Bytes(start, length));
            return new DocumentFileResult.Body(HttpStatus.PARTIAL_CONTENT, content.resource(),
                    length, totalSize, start, contentType, fileName, disposition, RangeCapability.BYTES);
        }

        // 无 Range / If-Range 存在 / 语法错误 / 合法多段：忽略 Range，返回完整 200
        StoredObjectContent content = prepared.handle.content(new ObjectReadRange.Full());
        return new DocumentFileResult.Body(HttpStatus.OK, content.resource(),
                totalSize, totalSize, 0, contentType, fileName, disposition, RangeCapability.BYTES);
    }

    /**
     * 解析 Range 头。If-Range 存在（无强校验器可用）与语法错误均视为无 Range
     * （RFC 9110：语法错误视为不存在，416 仅保留给越界/空范围）；多段不实现
     * multipart，同样忽略。
     */
    private static List<HttpRange> resolveRanges(@Nullable String rangeHeader, @Nullable String ifRangeHeader) {
        if (rangeHeader == null || rangeHeader.isBlank() || (ifRangeHeader != null && !ifRangeHeader.isBlank())) {
            return List.of();
        }
        try {
            return HttpRange.parseRanges(rangeHeader);
        } catch (IllegalArgumentException e) {
            return List.of();
        }
    }
}
