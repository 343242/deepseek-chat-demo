package com.demo.chat.rag.parser;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 基于 Apache Tika 的文档解析器。
 * 由 {@link DocumentParserFactory} 在无特定解析器匹配时作为默认解析器使用。
 */
@Component
public class TikaDocumentParser implements DocumentParser {

    private static final Logger log = LoggerFactory.getLogger(TikaDocumentParser.class);

    @Override
    public List<String> supportedMimeTypes() {
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
