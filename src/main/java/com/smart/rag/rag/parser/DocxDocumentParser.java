package com.smart.rag.rag.parser;

import com.smart.rag.rag.config.DocumentProperties;
import org.apache.commons.io.input.BoundedInputStream;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
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
 * DOCX 专用解析器
 * <p>
 * 使用 Apache POI 直接解析 DOCX 文件，保留文档结构信息：
 * <ul>
 *   <li>标题层级（heading level）作为元数据</li>
 *   <li>段落索引作为元数据</li>
 *   <li>按段落切分为独立 Document</li>
 * </ul>
 * <p>
 * 相比 Tika 的优势：
 * <ul>
 *   <li>保留标题层级（h1/h2/h3...），对 Parent-Child 分块有价值——标题天然是父文档边界</li>
 *   <li>段落级别粒度，便于溯源和精准检索</li>
 *   <li>避免 Tika 的通用解析管线开销</li>
 * </ul>
 */
@Component
public class DocxDocumentParser implements DocumentParser {

    private static final Logger log = LoggerFactory.getLogger(DocxDocumentParser.class);

    /**
     * 段落总数上限（R2-H2）：防止恶意 docx 用海量空段落撑爆内存绕过压缩比检查。
     * 计数覆盖所有段落（含空段落），超出即抛 {@link DocumentParseException}。
     */
    private static final int MAX_PARAGRAPHS = 50_000;

    /** 英文版 Word 标题样式：Heading1, Heading 2, ...（大小写不敏感） */
    private static final Pattern HEADING_EN_PATTERN = Pattern.compile("(?i)heading\\s*\\d+");
    private static final Pattern HEADING_EN_STRIP = Pattern.compile("(?i)heading\\s*");
    /** 中文版 Word 标题样式：标题 1, 标题 2, ... */
    private static final Pattern HEADING_ZH_PATTERN = Pattern.compile("标题\\s*\\d+");
    private static final Pattern HEADING_ZH_STRIP = Pattern.compile("标题\\s*");

    private final DocumentProperties documentProperties;

    /** 便捷无参构造（测试/独立使用），使用默认配置 */
    public DocxDocumentParser() {
        this(new DocumentProperties());
    }

    @Autowired
    public DocxDocumentParser(DocumentProperties documentProperties) {
        this.documentProperties = documentProperties;
    }

    @Override
    public List<String> supportedMimeTypes() {
        return List.of(
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        );
    }

    @Override
    public List<Document> parse(Resource resource, String mimeType) {
        String fileName = resource.getFilename();
        log.debug("Parsing DOCX with Apache POI: file={}", fileName);

        List<Document> documents = new ArrayList<>();
        long maxBytes = DataSize.parse(documentProperties.getMaxFileSize()).toBytes();

        try (InputStream is = resource.getInputStream();
             // 流级读取上限（MinIO 流 contentLength()=-1 时元信息检查失效，故在流级兜底）
             BoundedInputStream bounded = BoundedInputStream.builder()
                     .setInputStream(is).setMaxCount(maxBytes).get();
             XWPFDocument doc = new XWPFDocument(bounded)) {

            List<XWPFParagraph> paragraphs = doc.getParagraphs();
            int paragraphIndex = 0;
            int processedCount = 0;

            for (XWPFParagraph para : paragraphs) {
                // R2-H2: 计数所有段落（含空段落），超限即中止，避免恶意构造拖垮内存。
                // L-C1: 此 cap 只限段落数；单个段落的 run/文本量由 POI getText() 内部处理，
                // 理论上一个病态超大段落仍可能 OOM（实际罕见）。如需更严防御可加单段落字符预算。
                if (++processedCount > MAX_PARAGRAPHS) {
                    throw new DocumentParseException(
                            fileName != null ? fileName : "unknown", "docx",
                            String.format("段落数超过上限 %d（疑似解压炸弹）", MAX_PARAGRAPHS));
                }

                String text = para.getText();
                if (text == null || text.isBlank()) {
                    continue;
                }

                Map<String, Object> metadata = new HashMap<>();
                metadata.put("parser", "docx");
                metadata.put("mimeType", mimeType);
                metadata.put("paragraphIndex", paragraphIndex);

                // 保留标题层级信息
                String style = para.getStyle();
                if (style != null) {
                    String headingLevel = extractHeadingLevel(style);
                    if (headingLevel != null) {
                        metadata.put("headingLevel", headingLevel);
                    }
                }

                documents.add(new Document(text, metadata));
                paragraphIndex++;
            }

        } catch (DocumentParseException e) {
            throw e;
        } catch (Exception e) {
            throw new DocumentParseException(
                    fileName != null ? fileName : "unknown", "docx", "Unexpected error", e);
        }

        log.debug("DOCX parsed: {} paragraphs from {}", documents.size(), fileName);
        return documents;
    }

    /**
     * 从 Word 样式名称中提取标题层级。
     * Word 标题样式通常为 "Heading1", "Heading2", ... 或 "标题 1", "标题 2", ...
     *
     * @return 标题层级（如 "h1", "h2"），非标题段落返回 null
     */
    private String extractHeadingLevel(String style) {
        if (HEADING_EN_PATTERN.matcher(style).matches()) {
            String level = HEADING_EN_STRIP.matcher(style).replaceAll("");
            return "h" + level;
        }
        // 中文版 Word
        if (HEADING_ZH_PATTERN.matcher(style).matches()) {
            String level = HEADING_ZH_STRIP.matcher(style).replaceAll("");
            return "h" + level;
        }
        return null;
    }
}
