package com.smart.rag.rag.parser;

import com.smart.rag.rag.config.DocumentProperties;
import org.apache.commons.io.input.BoundedInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 纯文本专用解析器
 * <p>
 * 自动检测文本编码（GBK/GB2312/GB18030/Big5 等），转码为 UTF-8 后解析。
 * 编码检测基于 Mozilla UniversalDetector（juniversalchardet），对 CJK 编码准确率高。
 * <p>
 * 不经过 Tika 通用解析管线，减少不必要的开销。
 * 按段落（空行分隔）切分为独立 Document，便于后续分块处理。
 * <p>
 * R2-M3：读取上限直接从 {@link DocumentProperties#getMaxFileSize()} 读取（默认 50MB），
 * 用 {@link BoundedInputStream} 在流级别兜底，不再依赖 {@code resource.contentLength()}
 * （MinIO 流返回 -1 导致原 size 检查失效）。
 */
@Component
public class PlainTextDocumentParser implements DocumentParser {

    private static final Logger log = LoggerFactory.getLogger(PlainTextDocumentParser.class);

    /** 大文件日志阈值（字节）：超过此值的文本文件内存峰值可能达 3x，记录提示 */
    private static final long LARGE_FILE_LOG_THRESHOLD = 5L * 1024 * 1024;

    /** 段落切分正则：连续 2 个及以上换行（兼容 \r\n 与 \n） */
    private static final Pattern PARAGRAPH_PATTERN = Pattern.compile("(?:\\r?\\n){2,}");

    private final DocumentProperties documentProperties;

    public PlainTextDocumentParser(DocumentProperties documentProperties) {
        this.documentProperties = documentProperties;
    }

    @Override
    public List<String> supportedMimeTypes() {
        return List.of("text/plain");
    }

    @Override
    public List<Document> parse(Resource resource, String mimeType) {
        log.debug("Parsing plain text: file={}", resource.getFilename());

        long maxBytes = DataSize.parse(documentProperties.getMaxFileSize()).toBytes();

        try (InputStream raw = resource.getInputStream();
             // R2-M3: bound the read at maxBytes; allow 1 extra byte to detect overflow
             BoundedInputStream bounded = BoundedInputStream.builder()
                     .setInputStream(raw).setMaxCount(maxBytes + 1).get()) {

            byte[] bytes = bounded.readAllBytes();
            if (bytes.length > maxBytes) {
                throw new DocumentParseException(
                        resource.getFilename(), "plain-text",
                        String.format("文本文件超过上限 %s", documentProperties.getMaxFileSize()));
            }
            if (bytes.length > LARGE_FILE_LOG_THRESHOLD) {
                log.info("Large text file detected ({} MB), memory peak may reach 3x: file={}",
                        bytes.length / (1024 * 1024), resource.getFilename());
            }

            String content = EncodingDetector.detectAndDecode(bytes, resource.getFilename());

            if (content.isBlank()) {
                return List.of();
            }

            // 按空行分段落
            String[] paragraphs = PARAGRAPH_PATTERN.split(content);

            Map<String, Object> metadata = new HashMap<>();
            metadata.put("parser", "plain-text");
            metadata.put("mimeType", mimeType);

            List<Document> documents = new ArrayList<>();
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
