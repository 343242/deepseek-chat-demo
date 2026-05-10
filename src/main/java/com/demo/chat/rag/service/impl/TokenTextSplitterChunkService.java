package com.demo.chat.rag.service.impl;

import com.demo.chat.rag.config.DocumentProperties;
import com.demo.chat.rag.service.DocumentChunkService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 基于 TokenTextSplitter 的文档分块服务
 * <p>
 * 配置参数来自 application.yml 中的 app.document.chunk-size / chunk-overlap。
 * 每个 chunk 会附加 source、chunkIndex、totalChunks 元数据。
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TokenTextSplitterChunkService implements DocumentChunkService {

    private final DocumentProperties documentProperties;

    @Override
    public List<Document> chunk(List<Document> documents, String sourceFileName) {
        TokenTextSplitter splitter = TokenTextSplitter.builder()
                .withChunkSize(documentProperties.getChunkSize())
                .withOverlap(documentProperties.getChunkOverlap())
                .build();

        List<Document> allChunks = new ArrayList<>();
        int globalIndex = 0;

        for (Document doc : documents) {
            List<Document> chunks = splitter.apply(List.of(doc));

            for (int i = 0; i < chunks.size(); i++) {
                Document chunk = chunks.get(i);
                chunk.getMetadata().put("source", sourceFileName);
                chunk.getMetadata().put("chunkIndex", globalIndex);
                allChunks.add(chunk);
                globalIndex++;
            }
        }

        // 回写 totalChunks
        for (Document chunk : allChunks) {
            chunk.getMetadata().put("totalChunks", allChunks.size());
        }

        log.info("Split {} documents into {} chunks (chunkSize={}, overlap={}, source={})",
                documents.size(), allChunks.size(),
                documentProperties.getChunkSize(), documentProperties.getChunkOverlap(),
                sourceFileName);

        return allChunks;
    }
}
