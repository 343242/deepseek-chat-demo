package com.smart.rag.rag.parser;

import com.smart.rag.rag.config.DocumentProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.reader.ExtractedTextFormatter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 通用文档解析器（Tika 兜底）
 * <p>
 * 支持 PDF、DOC/DOCX、PPT/PPTX、HTML 等多种格式。
 * 仅在无专用解析器匹配时使用。配置了 ExtractedTextFormatter 进行文本清洗。
 * </p>
 */
@Component
public class TikaDocumentParser implements DocumentParser {

    private static final Logger log = LoggerFactory.getLogger(TikaDocumentParser.class);

    private final DocumentProperties documentProperties;

    /** 便捷无参构造（测试/独立使用），使用默认配置 */
    public TikaDocumentParser() {
        this(new DocumentProperties());
    }

    @Autowired
    public TikaDocumentParser(DocumentProperties documentProperties) {
        this.documentProperties = documentProperties;
    }

    @Override
    public List<String> supportedMimeTypes() {
        // Tika 是万能兜底，不注册到工厂路由表（由工厂直接作为 defaultParser）
        return List.of();
    }

    @Override
    public boolean isFallback() {
        return true;
    }

    @Override
    public List<Document> parse(Resource resource, String mimeType) {
        log.debug("Parsing with Tika: mime={}, file={}", mimeType, resource.getFilename());

        checkSize(resource);

        ExtractedTextFormatter formatter = ExtractedTextFormatter.builder()
                .withNumberOfTopTextLinesToDelete(0)
                .build();

        List<Document> documents;
        try {
            TikaDocumentReader reader = new TikaDocumentReader(resource, formatter);
            documents = reader.get();
        } catch (Exception e) {
            throw new DocumentParseException(resource.getFilename(), "tika", "Failed to parse with Tika", e);
        }

        // 为每个文档附加解析器标识元数据
        for (Document doc : documents) {
            doc.getMetadata().put("parser", "tika");
            doc.getMetadata().put("mimeType", mimeType);
        }

        log.debug("Tika parsed {} segments from {}", documents.size(), resource.getFilename());
        return documents;
    }

    /**
     * WHY: TikaDocumentReader 只接受 Resource，无法在流级别包一层 BoundedInputStream，
     * 故退化为元信息级检查。contentLength() 对 MinIO 流可能返回 -1，此时跳过检查
     * （上游上传校验与各专用解析器的流级兜底仍生效）。
     */
    private void checkSize(Resource resource) {
        long maxBytes = org.springframework.util.unit.DataSize
                .parse(documentProperties.getMaxFileSize()).toBytes();
        long contentLength;
        try {
            contentLength = resource.contentLength();
        } catch (Exception e) {
            log.debug("Cannot determine content length for {}, skipping size check",
                    resource.getFilename());
            return;
        }
        if (contentLength > maxBytes) {
            throw new DocumentParseException(
                    resource.getFilename(), "tika",
                    String.format("文件超过最大允许大小 %s", documentProperties.getMaxFileSize()));
        }
    }
}
