package com.smart.rag.rag.parser;

import com.smart.rag.rag.config.DocumentProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.reader.pdf.config.PdfDocumentReaderConfig;
import org.springframework.ai.reader.ExtractedTextFormatter;
import org.springframework.core.io.Resource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * PDF 专用解析器（PdfBox 降级方案）
 * <p>
 * 使用 Spring AI 的 {@link PagePdfDocumentReader}（基于 Apache PdfBox），
 * 按页切分并附加页码元数据（startPageNumber / endPageNumber）。
 * <p>
 * 当 {@link OpenDataLoaderPdfParser} 不可用（opendataloader-pdf-core 未引入）时自动激活。
 * 相比 OpenDataLoader 的劣势：不保留文档结构（标题/表格/多栏），纯文本按页切分。
 */
@Component
@ConditionalOnMissingBean(OpenDataLoaderPdfParser.class)
public class PdfDocumentParser implements DocumentParser {

    private static final Logger log = LoggerFactory.getLogger(PdfDocumentParser.class);

    /** 页面顶部边距（points），0 表示不做裁剪 */
    private static final int PAGE_TOP_MARGIN = 0;
    /** 每页删除的顶部文本行数，0 表示保留全部 */
    private static final int TOP_TEXT_LINES_TO_DELETE = 0;
    /** 每个 Document 包含的页数（1 = 按页切分） */
    private static final int PAGES_PER_DOCUMENT = 1;

    private final DocumentProperties documentProperties;

    public PdfDocumentParser(DocumentProperties documentProperties) {
        this.documentProperties = documentProperties;
    }

    @Override
    public List<String> supportedMimeTypes() {
        return List.of("application/pdf");
    }

    @Override
    public List<Document> parse(Resource resource, String mimeType) {
        log.debug("Parsing PDF with PagePdfDocumentReader: file={}", resource.getFilename());

        // WHY: PagePdfDocumentReader 只接受 Resource，无法在流级别包一层 BoundedInputStream，
        // 故退化为元信息级检查。contentLength() 对 MinIO 流可能返回 -1，此时跳过检查
        // （上游上传校验与 OpenDataLoaderPdfParser 的流级兜底仍生效）。
        long maxBytes = org.springframework.util.unit.DataSize
                .parse(documentProperties.getMaxFileSize()).toBytes();
        long contentLength;
        try {
            contentLength = resource.contentLength();
        } catch (Exception e) {
            log.debug("Cannot determine content length for {}, skipping size check",
                    resource.getFilename());
            contentLength = -1;
        }
        if (contentLength > maxBytes) {
            throw new DocumentParseException(
                    resource.getFilename(), "pdf-page",
                    String.format("文件超过最大允许大小 %s", documentProperties.getMaxFileSize()));
        }

        PdfDocumentReaderConfig config = PdfDocumentReaderConfig.builder()
                .withPageTopMargin(PAGE_TOP_MARGIN)
                .withPageExtractedTextFormatter(ExtractedTextFormatter.builder()
                        .withNumberOfTopTextLinesToDelete(TOP_TEXT_LINES_TO_DELETE)
                        .build())
                .withPagesPerDocument(PAGES_PER_DOCUMENT)
                .build();

        List<Document> documents;
        try {
            PagePdfDocumentReader reader = new PagePdfDocumentReader(resource, config);
            documents = reader.get();
        } catch (Exception e) {
            throw new DocumentParseException(resource.getFilename(), "pdf-page", "Failed to parse PDF", e);
        }

        for (Document doc : documents) {
            doc.getMetadata().put("parser", "pdf-page");
            doc.getMetadata().put("mimeType", mimeType);
        }

        log.debug("PDF parsed: {} pages from {}", documents.size(), resource.getFilename());
        return documents;
    }
}
