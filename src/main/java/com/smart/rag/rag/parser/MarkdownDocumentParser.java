package com.smart.rag.rag.parser;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.markdown.MarkdownDocumentReader;
import org.springframework.ai.reader.markdown.config.MarkdownDocumentReaderConfig;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Markdown 专用解析器
 * <p>
 * 使用 Spring AI 的 {@link MarkdownDocumentReader}，
 * 保留标题层级作为元数据，支持代码块/引用块独立分割。
 * <p>
 * 相比 Tika 的优势：
 * <ul>
 *   <li>标题层级保留为元数据（h1/h2/h3...）</li>
 *   <li>代码块可独立分割（技术文档场景）</li>
 *   <li>水平分割线可作为文档边界</li>
 *   <li>对后续 Parent-Child 分块特别有价值——标题天然是父文档边界</li>
 * </ul>
 */
@Component
public class MarkdownDocumentParser implements DocumentParser {

    private static final Logger log = LoggerFactory.getLogger(MarkdownDocumentParser.class);

    @Override
    public List<String> supportedMimeTypes() {
        return List.of("text/markdown", "text/x-markdown");
    }

    @Override
    public List<Document> parse(Resource resource, String mimeType) {
        log.debug("Parsing Markdown: file={}", resource.getFilename());

        // 编码检测：确保传入 MarkdownDocumentReader 的内容是 UTF-8
        Resource transcoded = EncodingDetector.detectAndTranscode(resource);

        MarkdownDocumentReaderConfig config = MarkdownDocumentReaderConfig.builder()
                .withHorizontalRuleCreateDocument(true)
                .withIncludeCodeBlock(true)
                .withIncludeBlockquote(false)
                .build();

        MarkdownDocumentReader reader = new MarkdownDocumentReader(transcoded, config);
        List<Document> documents = reader.get();

        for (Document doc : documents) {
            doc.getMetadata().put("parser", "markdown");
            doc.getMetadata().put("mimeType", mimeType);
        }

        log.debug("Markdown parsed: {} sections from {}", documents.size(), resource.getFilename());
        return documents;
    }
}
