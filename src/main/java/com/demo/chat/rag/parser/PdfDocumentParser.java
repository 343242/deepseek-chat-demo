package com.demo.chat.rag.parser;

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

    @Override
    public List<String> supportedMimeTypes() {
        return List.of("application/pdf");
    }

    @Override
    public List<Document> parse(Resource resource, String mimeType) {
        log.debug("Parsing PDF with PagePdfDocumentReader: file={}", resource.getFilename());

        PdfDocumentReaderConfig config = PdfDocumentReaderConfig.builder()
                .withPageTopMargin(0)
                .withPageExtractedTextFormatter(ExtractedTextFormatter.builder()
                        .withNumberOfTopTextLinesToDelete(0)
                        .build())
                .withPagesPerDocument(1)
                .build();

        PagePdfDocumentReader reader = new PagePdfDocumentReader(resource, config);
        List<Document> documents = reader.get();

        for (Document doc : documents) {
            doc.getMetadata().put("parser", "pdf-page");
            doc.getMetadata().put("mimeType", mimeType);
        }

        log.debug("PDF parsed: {} pages from {}", documents.size(), resource.getFilename());
        return documents;
    }
}
