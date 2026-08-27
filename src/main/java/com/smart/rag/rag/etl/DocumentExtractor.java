package com.smart.rag.rag.etl;

import com.smart.rag.rag.parser.DocumentParser;
import com.smart.rag.rag.parser.DocumentParserFactory;
import com.smart.rag.rag.parser.ParseContext;
import com.smart.rag.rag.service.FileStorageService;
import com.smart.rag.rag.service.ObjectReadRange;
import com.smart.rag.rag.service.StoredObjectContent;
import com.smart.rag.rag.service.StoredObjectHandle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 基于 MinIO + Tika 的文档提取器
 * <p>
 * 经统一 {@code FileStorageService.open} 契约读取对象，根据规范 MIME 选择 Parser
 * 解析为 Document 列表。内容流惰性建立，解析完成或抛异常时关闭。
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
        return extractWithManifest(bucket, objectKey, mimeType, null).documents();
    }

    @Override
    public ExtractWithManifest extractWithManifest(String bucket, String objectKey, String mimeType, Long documentId) {
        StoredObjectHandle handle = fileStorageService.open(bucket, objectKey);
        StoredObjectContent content = handle.content(new ObjectReadRange.Full());
        Resource fileResource = content.resource();
        DocumentParser parser = parserFactory.getParser(mimeType);
        try {
            var parsed = parser.parseWithManifest(fileResource, mimeType,
                    new ParseContext(documentId, bucket, objectKey, objectKey));
            log.info("Extracted {} segments, {} image entries (mime={})",
                    parsed.documents().size(), parsed.imageManifest().size(), mimeType);
            return new ExtractWithManifest(parsed.documents(), parsed.imageManifest().entries());
        } finally {
            // 确保解析抛异常时也关闭底层 MinIO GetObjectResponse，防止 HTTP 连接泄漏
            if (fileResource instanceof java.io.Closeable closeable) {
                try {
                    closeable.close();
                } catch (java.io.IOException e) {
                    log.warn("Failed to close MinIO resource after extract", e);
                }
            }
        }
    }
}
