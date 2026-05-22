package com.smart.rag.rag.chunk;

import com.smart.rag.rag.config.DocumentProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Token 数分块策略
 * <p>
 * 使用 Spring AI 内置的 {@link TokenTextSplitter} 按 token 数机械切分。
 * 适用于格式不固定、无明确段落边界的文档。
 * </p>
 */
@Component
public class TokenChunkStrategy implements ChunkStrategy {

    private static final Logger log = LoggerFactory.getLogger(TokenChunkStrategy.class);

    private final DocumentProperties properties;

    public TokenChunkStrategy(DocumentProperties properties) {
        this.properties = properties;
    }

    @Override
    public String strategyName() {
        return "token";
    }

    @Override
    public List<Document> chunk(List<Document> documents, String sourceFileName) {
        if (documents == null || documents.isEmpty()) {
            return List.of();
        }

        TokenTextSplitter splitter = TokenTextSplitter.builder()
                .withChunkSize(properties.getChunkSize())
                .build();

        List<Document> allChunks = new ArrayList<>();
        int globalIndex = 0;

        for (Document doc : documents) {
            List<Document> chunks = splitter.apply(List.of(doc));
            for (Document chunk : chunks) {
                chunk.getMetadata().put("source", sourceFileName);
                chunk.getMetadata().put("chunkIndex", globalIndex);
                allChunks.add(chunk);
                globalIndex++;
            }
        }

        for (Document chunk : allChunks) {
            chunk.getMetadata().put("totalChunks", allChunks.size());
        }

        log.info("[TokenChunk] Split {} docs → {} chunks (chunkSize={}, source={})",
                documents.size(), allChunks.size(), properties.getChunkSize(), sourceFileName);

        return allChunks;
    }
}
