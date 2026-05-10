package com.demo.chat.rag.parser;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 基于 Apache Tika 的文档解析器
 * <p>
 * 支持 PDF、DOC/DOCX、PPT/PPTX、HTML、TXT 等多种格式。
 * 由 {@link DocumentParserFactory} 在无特定解析器匹配时作为默认解析器使用。
 * </p>
 */
@Slf4j
@Component
public class TikaDocumentParser implements DocumentParser {

    @Override
    public List<String> supportedMimeTypes() {
        // Tika 是万能解析器，理论上不限定 MIME 类型
        // 这里列出项目明确支持的格式
        return List.of(
                "application/pdf",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                "application/msword",
                "application/vnd.ms-powerpoint",
                "text/plain",
                "text/markdown",
                "text/html"
        );
    }

    @Override
    public List<Document> parse(Resource resource, String mimeType) {
        log.debug("Parsing document with Tika, mimeType: {}, resource: {}", mimeType, resource.getFilename());
        TikaDocumentReader reader = new TikaDocumentReader(resource);
        List<Document> documents = reader.read();
        log.debug("Tika parsed {} document segments from {}", documents.size(), resource.getFilename());
        return documents;
    }
}
