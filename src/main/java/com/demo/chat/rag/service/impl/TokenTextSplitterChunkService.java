package com.demo.chat.rag.service.impl;

import com.demo.chat.rag.config.DocumentProperties;
import com.demo.chat.rag.service.DocumentChunkService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class TokenTextSplitterChunkService implements DocumentChunkService {

    private static final Logger log = LoggerFactory.getLogger(TokenTextSplitterChunkService.class);

    private final DocumentProperties documentProperties;

    public TokenTextSplitterChunkService(DocumentProperties documentProperties) {
        this.documentProperties = documentProperties;
    }

    @Override
    public List<Document> chunk(List<Document> documents, String sourceFileName) {
        TokenTextSplitter splitter = TokenTextSplitter.builder()
                .withChunkSize(documentProperties.getChunkSize())
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
