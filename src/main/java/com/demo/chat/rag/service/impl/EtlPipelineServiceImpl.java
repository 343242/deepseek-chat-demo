package com.demo.chat.rag.service.impl;

import com.demo.chat.rag.chunk.ChunkStrategy;
import com.demo.chat.rag.chunk.ChunkStrategyFactory;
import com.demo.chat.rag.config.DocumentProperties;
import com.demo.chat.rag.entity.RagDocument;
import com.demo.chat.rag.mapper.RagDocumentMapper;
import com.demo.chat.rag.parser.DocumentParser;
import com.demo.chat.rag.parser.DocumentParserFactory;
import com.demo.chat.rag.service.EtlPipelineService;
import com.demo.chat.rag.service.FileStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * ETL Pipeline 编排服务实现
 * <p>
 * 流程：Extract(从 MinIO 下载 → Parser 解析) → Transform(策略化分块) → Load(PGvector 写入)
 * 分块策略由 {@link ChunkStrategyFactory} 按 YAML 配置路由。
 * </p>
 */
@Service
public class EtlPipelineServiceImpl implements EtlPipelineService {

    private static final Logger log = LoggerFactory.getLogger(EtlPipelineServiceImpl.class);

    private final FileStorageService fileStorageService;
    private final DocumentParserFactory parserFactory;
    private final ChunkStrategyFactory chunkStrategyFactory;
    private final DocumentProperties documentProperties;
    private final RagDocumentMapper ragDocumentMapper;
    private final VectorStore vectorStore;

    public EtlPipelineServiceImpl(FileStorageService fileStorageService,
                                  DocumentParserFactory parserFactory,
                                  ChunkStrategyFactory chunkStrategyFactory,
                                  DocumentProperties documentProperties,
                                  RagDocumentMapper ragDocumentMapper,
                                  VectorStore vectorStore) {
        this.fileStorageService = fileStorageService;
        this.parserFactory = parserFactory;
        this.chunkStrategyFactory = chunkStrategyFactory;
        this.documentProperties = documentProperties;
        this.ragDocumentMapper = ragDocumentMapper;
        this.vectorStore = vectorStore;
    }

    @Override
    @Transactional
    public int execute(Long documentId, String bucket, String objectKey, String fileName, String mimeType) {
        log.info("ETL pipeline started for document: id={}, file={}, mime={}", documentId, fileName, mimeType);

        RagDocument doc = ragDocumentMapper.selectById(documentId);
        if (doc == null) {
            throw new IllegalArgumentException("Document not found: " + documentId);
        }

        try {
            // === Extract ===
            updateStatus(documentId, "PARSING");
            Resource fileResource = fileStorageService.download(bucket, objectKey);
            DocumentParser parser = parserFactory.getParser(mimeType);
            List<Document> rawDocuments = parser.parse(fileResource, mimeType);
            log.info("Extracted {} segments from {}", rawDocuments.size(), fileName);

            // === Transform (Strategy-based Chunking) ===
            updateStatus(documentId, "CHUNKING");
            ChunkStrategy strategy = chunkStrategyFactory.getStrategy(documentProperties.getChunkStrategy());
            List<Document> chunks = strategy.chunk(rawDocuments, fileName);

            // === Load (写入 PGvector) ===
            updateStatus(documentId, "VECTORIZING");
            vectorStore.add(chunks);
            log.info("Loaded {} chunks into vector store for document {}", chunks.size(), documentId);

            // === Complete ===
            doc.setChunkCount(chunks.size());
            doc.setStatus("COMPLETED");
            doc.setUpdateTime(LocalDateTime.now());
            ragDocumentMapper.updateById(doc);

            log.info("ETL pipeline completed for document: id={}, chunks={}, strategy={}",
                    documentId, chunks.size(), strategy.strategyName());
            return chunks.size();

        } catch (Exception e) {
            log.error("ETL pipeline failed for document: id={}", documentId, e);
            doc.setStatus("FAILED");
            doc.setErrorMessage(truncate(e.getMessage(), 2000));
            doc.setUpdateTime(LocalDateTime.now());
            ragDocumentMapper.updateById(doc);
            throw new RuntimeException("Document processing failed: " + fileName, e);
        }
    }

    private void updateStatus(Long documentId, String status) {
        RagDocument update = new RagDocument();
        update.setId(documentId);
        update.setStatus(status);
        update.setUpdateTime(LocalDateTime.now());
        ragDocumentMapper.updateById(update);
    }

    private static String truncate(String str, int maxLen) {
        if (str == null) return null;
        return str.length() <= maxLen ? str : str.substring(0, maxLen);
    }
}
