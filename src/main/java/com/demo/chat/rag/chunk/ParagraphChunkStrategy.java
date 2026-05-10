package com.demo.chat.rag.chunk;

import com.demo.chat.rag.config.DocumentProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 段落级分块策略
 * <p>
 * 按文本的自然段落边界切分（双换行 \n\n 或 Markdown 标题 #），
 * 过短的段落合并到上一段，避免碎片化。
 * 适用于 Markdown、纯文本等有明确段落边界的文档。
 * </p>
 */
@Component
public class ParagraphChunkStrategy implements ChunkStrategy {

    private static final Logger log = LoggerFactory.getLogger(ParagraphChunkStrategy.class);

    private final DocumentProperties properties;

    public ParagraphChunkStrategy(DocumentProperties properties) {
        this.properties = properties;
    }

    @Override
    public String strategyName() {
        return "paragraph";
    }

    @Override
    public List<Document> chunk(List<Document> documents, String sourceFileName) {
        int minLength = properties.getParagraphMinLength();
        List<Document> allChunks = new ArrayList<>();
        int globalIndex = 0;

        for (Document doc : documents) {
            String text = doc.getText();
            List<String> paragraphs = splitIntoParagraphs(text);

            // 合并过短段落
            List<String> merged = mergeShortParagraphs(paragraphs, minLength);

            for (String para : merged) {
                if (para.isBlank()) continue;
                Document chunk = new Document(para);
                chunk.getMetadata().put("source", sourceFileName);
                chunk.getMetadata().put("chunkIndex", globalIndex);
                chunk.getMetadata().put("chunkType", "paragraph");
                allChunks.add(chunk);
                globalIndex++;
            }
        }

        for (Document chunk : allChunks) {
            chunk.getMetadata().put("totalChunks", allChunks.size());
        }

        log.info("[ParagraphChunk] Split {} docs → {} paragraphs (minLength={}, source={})",
                documents.size(), allChunks.size(), minLength, sourceFileName);

        return allChunks;
    }

    /**
     * 按双换行或 Markdown 标题切分
     */
    private List<String> splitIntoParagraphs(String text) {
        List<String> paragraphs = new ArrayList<>();
        // 先按 Markdown 标题切（保留标题在段首）
        String[] sections = text.split("(?m)(?=^#{1,6}\\s)");

        for (String section : sections) {
            if (section.isBlank()) continue;
            // 每个 section 内再按双换行切
            String[] parts = section.split("\\n\\s*\\n");
            for (String part : parts) {
                String trimmed = part.trim();
                if (!trimmed.isBlank()) {
                    paragraphs.add(trimmed);
                }
            }
        }

        return paragraphs;
    }

    /**
     * 合并过短的段落到上一段
     */
    private List<String> mergeShortParagraphs(List<String> paragraphs, int minLength) {
        if (paragraphs.isEmpty()) return paragraphs;

        List<String> merged = new ArrayList<>();
        StringBuilder current = new StringBuilder(paragraphs.get(0));

        for (int i = 1; i < paragraphs.size(); i++) {
            String para = paragraphs.get(i);
            if (current.length() < minLength) {
                current.append("\n\n").append(para);
            } else {
                merged.add(current.toString());
                current = new StringBuilder(para);
            }
        }
        merged.add(current.toString());

        return merged;
    }
}
