package com.demo.chat.rag.parser;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 纯文本专用解析器
 * <p>
 * 自动检测文本编码（GBK/GB2312/GB18030/Big5 等），转码为 UTF-8 后解析。
 * 编码检测基于 Mozilla UniversalDetector（juniversalchardet），对 CJK 编码准确率高。
 * <p>
 * 不经过 Tika 通用解析管线，减少不必要的开销。
 * 按段落（空行分隔）切分为独立 Document，便于后续分块处理。
 */
@Component
public class PlainTextDocumentParser implements DocumentParser {

    private static final Logger log = LoggerFactory.getLogger(PlainTextDocumentParser.class);

    /** 纯文本文件大小上限：50MB，防止 readAllBytes OOM */
    private static final long MAX_TEXT_FILE_SIZE = 50L * 1024 * 1024;

    @Override
    public List<String> supportedMimeTypes() {
        return List.of("text/plain");
    }

    @Override
    public List<Document> parse(Resource resource, String mimeType) {
        log.debug("Parsing plain text: file={}", resource.getFilename());

        try (InputStream is = resource.getInputStream()) {
            // 文件大小上限检查，防止超大文件 OOM
            long contentLength = resource.contentLength();
            if (contentLength > MAX_TEXT_FILE_SIZE) {
                throw new DocumentParseException(
                        resource.getFilename(), "plain-text",
                        String.format("文本文件过大（%d MB），上限 %d MB",
                                contentLength / (1024 * 1024), MAX_TEXT_FILE_SIZE / (1024 * 1024)),
                        null);
            }

            byte[] bytes = is.readAllBytes();
            String content = EncodingDetector.detectAndDecode(bytes, resource.getFilename());

            if (content.isBlank()) {
                return List.of();
            }

            // 按空行分段落
            String[] paragraphs = content.split("(?:\\r?\\n){2,}");

            Map<String, Object> metadata = new HashMap<>();
            metadata.put("parser", "plain-text");
            metadata.put("mimeType", mimeType);

            List<Document> documents = new java.util.ArrayList<>();
            for (int i = 0; i < paragraphs.length; i++) {
                String text = paragraphs[i].trim();
                if (text.isEmpty()) {
                    continue;
                }
                Map<String, Object> paraMeta = new HashMap<>(metadata);
                paraMeta.put("paragraphIndex", i);
                documents.add(new Document(text, paraMeta));
            }

            log.debug("Plain text parsed: {} paragraphs from {}", documents.size(), resource.getFilename());
            return documents;

        } catch (DocumentParseException e) {
            throw e;
        } catch (Exception e) {
            throw new DocumentParseException(
                    resource.getFilename(), "plain-text",
                    "Failed to parse plain text", e);
        }
    }
}
