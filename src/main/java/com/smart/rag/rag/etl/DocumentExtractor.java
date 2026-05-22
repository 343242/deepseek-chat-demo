package com.smart.rag.rag.etl;

import com.smart.rag.rag.parser.DocumentParser;
import com.smart.rag.rag.parser.DocumentParserFactory;
import com.smart.rag.rag.service.FileStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 基于 MinIO + Tika 的文档提取器
 * <p>
 * 从 MinIO 下载文件，根据 MIME 类型选择 Parser 解析为 Document 列表。
 * </p>
 */
@Component
public class DocumentExtractor implements Extractor {

    private static final Logger log = LoggerFactory.getLogger(DocumentExtractor.class);

    private final FileStorageService fileStorageService;
    private final DocumentParserFactory parserFactory;

    public DocumentExtractor(FileStorageService fileStorageService,
                             DocumentParserFactory parserFactory) {
        this.fileStorageService = fileStorageService;
        this.parserFactory = parserFactory;
    }

    @Override
    public List<Document> extract(String bucket, String objectKey, String mimeType) {
        Resource fileResource = fileStorageService.download(bucket, objectKey);
        DocumentParser parser = parserFactory.getParser(mimeType);
        List<Document> documents = parser.parse(fileResource, mimeType);
        log.info("Extracted {} segments (mime={})", documents.size(), mimeType);
        return documents;
    }
}
