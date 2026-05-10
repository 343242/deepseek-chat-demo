package com.demo.chat.rag.parser;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.reader.ExtractedTextFormatter;
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

    @Override
    public List<String> supportedMimeTypes() {
        // Tika 是万能兜底，不注册到工厂路由表（由工厂直接作为 defaultParser）
        return List.of();
    }

    @Override
    public List<Document> parse(Resource resource, String mimeType) {
        log.debug("Parsing with Tika: mime={}, file={}", mimeType, resource.getFilename());

        ExtractedTextFormatter formatter = ExtractedTextFormatter.builder()
                .withNumberOfTopTextLinesToDelete(0)
                .build();

        TikaDocumentReader reader = new TikaDocumentReader(resource, formatter);
        List<Document> documents = reader.get();

        // 为每个文档附加解析器标识元数据
        for (Document doc : documents) {
            doc.getMetadata().put("parser", "tika");
            doc.getMetadata().put("mimeType", mimeType);
        }

        log.debug("Tika parsed {} segments from {}", documents.size(), resource.getFilename());
        return documents;
    }
}
