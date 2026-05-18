package com.demo.chat.rag.chunk;

import com.demo.chat.rag.config.DocumentProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 结构感知分块策略（Structure-aware Chunking）
 * <p>
 * 利用文档结构信息（Markdown 标题、PDF 页码、HTML 标签）作为切割点，
 * 避免在结构边界中间切断。对有明确结构的文档效果最好。
 * <p>
 * 自适应逻辑：
 * <pre>
 * Document 列表（来自 Parser）
 *   ↓ 检查 metadata 中的结构标记
 *   ├── parser=markdown → 按 Parser 已拆好的 section 处理
 *   │   超大 section 按段落边界再切，不跨标题层级
 *   ├── parser=pdf-page → 按页边界保留，过短页合并，超长页再切
 *   ├── mimeType=text/html → 按 HTML 块级标签切分
 *   └── 无结构标记 → 退化到双换行段落切分 + 短段落合并
 * </pre>
 *
 * <p>替代原有 {@code ParagraphChunkStrategy}，在保留段落切分能力的同时
 * 增加结构感知。配置中策略名仍为 "paragraph" 以保持向后兼容。</p>
 */
@Component
public class StructureAwareChunkStrategy implements ChunkStrategy {

    private static final Logger log = LoggerFactory.getLogger(StructureAwareChunkStrategy.class);

    /** 超过此字符数的 section/page 需要进一步切分 */
    private static final int LONG_SECTION_THRESHOLD = 3000;

    private final DocumentProperties properties;

    public StructureAwareChunkStrategy(DocumentProperties properties) {
        this.properties = properties;
    }

    @Override
    public String strategyName() {
        return "paragraph";
    }

    @Override
    public List<Document> chunk(List<Document> documents, String sourceFileName) {
        if (documents == null || documents.isEmpty()) {
            return List.of();
        }

        // 检测文档结构类型
        StructureType structureType = detectStructureType(documents);

        List<Document> allChunks = switch (structureType) {
            case MARKDOWN -> chunkMarkdown(documents, sourceFileName);
            case PDF_PAGE -> chunkPdf(documents, sourceFileName);
            case HTML -> chunkHtml(documents, sourceFileName);
            case PLAIN -> chunkPlain(documents, sourceFileName);
        };

        // 回写总 chunk 数
        for (Document chunk : allChunks) {
            chunk.getMetadata().put("totalChunks", allChunks.size());
        }

        log.info("[StructureAware] {} docs → {} chunks (structure={}, source={})",
                documents.size(), allChunks.size(), structureType, sourceFileName);

        return allChunks;
    }

    // ==================== 结构检测 ====================

    private enum StructureType {
        MARKDOWN, PDF_PAGE, HTML, PLAIN
    }

    /**
     * 根据 metadata 检测文档结构类型
     */
    private StructureType detectStructureType(List<Document> documents) {
        // 检查第一个有 metadata 的文档
        for (Document doc : documents) {
            Map<String, Object> meta = doc.getMetadata();
            String parser = metaValue(meta, "parser");
            String mimeType = metaValue(meta, "mimeType");

            if ("markdown".equals(parser)) return StructureType.MARKDOWN;
            if ("pdf-page".equals(parser)) return StructureType.PDF_PAGE;
            if (mimeType != null && mimeType.contains("html")) return StructureType.HTML;
        }

        // 兜底：检查文本内容是否包含 Markdown 标记
        for (Document doc : documents) {
            String text = doc.getText();
            if (text != null && text.lines().anyMatch(line -> line.matches("^#{1,6}\\s.+"))) {
                return StructureType.MARKDOWN;
            }
        }

        return StructureType.PLAIN;
    }

    // ==================== Markdown 结构切分 ====================

    /**
     * Markdown 切分：Parser 已按标题拆分，这里处理超大 section
     * <p>
     * 策略：
     * <ol>
     *   <li>如果 section 不长（≤ threshold），直接作为一个 chunk</li>
     *   <li>如果 section 过长，按段落边界（\n\n）再切</li>
     *   <li>过短的段落合并到上一段</li>
     * </ol>
     */
    private List<Document> chunkMarkdown(List<Document> documents, String sourceFileName) {
        int minLength = properties.getParagraphMinLength();
        List<Document> allChunks = new ArrayList<>();
        int globalIndex = 0;

        for (Document doc : documents) {
            String text = doc.getText();
            if (text == null || text.isBlank()) continue;

            // 短 section 直接保留
            if (text.length() <= LONG_SECTION_THRESHOLD) {
                Document chunk = createChunk(doc.getText(), doc.getMetadata(), sourceFileName, globalIndex, "markdown-section");
                allChunks.add(chunk);
                globalIndex++;
                continue;
            }

            // 长 section：按段落边界再切，保留标题在首段
            List<String> paragraphs = splitIntoParagraphs(text);
            List<String> merged = mergeShortParagraphs(paragraphs, minLength);

            for (String para : merged) {
                if (para.isBlank()) continue;
                Document chunk = createChunk(para, doc.getMetadata(), sourceFileName, globalIndex, "markdown-paragraph");
                allChunks.add(chunk);
                globalIndex++;
            }
        }

        return allChunks;
    }

    // ==================== PDF 页级切分 ====================

    /**
     * PDF 切分：Parser 已按页拆分
     * <p>
     * 策略：
     * <ol>
     *   <li>过短的页（< minLength）合并到下一页</li>
     *   <li>超长的页按段落边界再切</li>
     * </ol>
     */
    private List<Document> chunkPdf(List<Document> documents, String sourceFileName) {
        int minLength = properties.getParagraphMinLength();
        List<Document> allChunks = new ArrayList<>();
        int globalIndex = 0;

        // 先合并过短的连续页
        List<Document> mergedPages = mergeShortPages(documents, minLength);

        for (Document page : mergedPages) {
            String text = page.getText();
            if (text == null || text.isBlank()) continue;

            // 短页直接保留
            if (text.length() <= LONG_SECTION_THRESHOLD) {
                Document chunk = createChunk(text, page.getMetadata(), sourceFileName, globalIndex, "pdf-page");
                allChunks.add(chunk);
                globalIndex++;
                continue;
            }

            // 超长页：按段落边界再切
            List<String> paragraphs = splitIntoParagraphs(text);
            List<String> merged = mergeShortParagraphs(paragraphs, minLength);

            for (String para : merged) {
                if (para.isBlank()) continue;
                Document chunk = createChunk(para, page.getMetadata(), sourceFileName, globalIndex, "pdf-paragraph");
                allChunks.add(chunk);
                globalIndex++;
            }
        }

        return allChunks;
    }

    /**
     * 合并过短的连续页
     */
    private List<Document> mergeShortPages(List<Document> pages, int minLength) {
        if (pages.isEmpty()) return pages;

        List<Document> result = new ArrayList<>();
        StringBuilder currentText = new StringBuilder();
        Map<String, Object> currentMeta = new java.util.HashMap<>(pages.get(0).getMetadata());

        for (Document page : pages) {
            String text = page.getText();
            if (text == null) text = "";

            if (currentText.length() > 0 && currentText.length() >= minLength) {
                result.add(new Document(currentText.toString(), currentMeta));
                currentText = new StringBuilder();
                currentMeta = new java.util.HashMap<>(page.getMetadata());
            }

            if (currentText.length() > 0) {
                currentText.append("\n\n");
            }
            currentText.append(text);
        }

        if (currentText.length() > 0) {
            result.add(new Document(currentText.toString(), currentMeta));
        }

        return result;
    }

    // ==================== HTML 结构切分 ====================

    /**
     * HTML 切分：按块级标签边界识别段落
     * <p>
     * 识别 &lt;h1&gt;-&lt;h6&gt;、&lt;p&gt;、&lt;div&gt;、&lt;li&gt; 作为切分点。
     * HTML 专用 Parser 产出更有结构的 Document，这里做兜底处理。
     */
    private List<Document> chunkHtml(List<Document> documents, String sourceFileName) {
        int minLength = properties.getParagraphMinLength();
        List<Document> allChunks = new ArrayList<>();
        int globalIndex = 0;

        for (Document doc : documents) {
            String text = doc.getText();
            if (text == null || text.isBlank()) continue;

            // 按 HTML 块级标签切分
            List<String> blocks = splitHtmlBlocks(text);
            List<String> merged = mergeShortParagraphs(blocks, minLength);

            for (String block : merged) {
                if (block.isBlank()) continue;
                Document chunk = createChunk(block, doc.getMetadata(), sourceFileName, globalIndex, "html-block");
                allChunks.add(chunk);
                globalIndex++;
            }
        }

        return allChunks;
    }

    /**
     * 按 HTML 块级标签边界切分文本
     * <p>
     * Tika 输出的 HTML 文本通常已去除标签，但保留了一些结构痕迹（如连续换行）。
     * 如果文本中仍有 HTML 标签（如来自原始 HTML），按标签切。
     * 否则退化到段落切分。
     */
    private List<String> splitHtmlBlocks(String text) {
        // 如果文本中仍有 HTML 标签
        if (text.contains("<")) {
            List<String> blocks = new ArrayList<>();
            // 按 </p> </div> </li> </h1-6> 等块级闭合标签切分
            String[] parts = text.split("(?i)</(?:p|div|li|h[1-6]|section|article|blockquote|pre|table|tr)>");
            for (String part : parts) {
                String cleaned = stripHtmlTags(part).trim();
                if (!cleaned.isBlank()) {
                    blocks.add(cleaned);
                }
            }
            return blocks.isEmpty() ? splitIntoParagraphs(text) : blocks;
        }

        // 纯文本退化到段落切分
        return splitIntoParagraphs(text);
    }

    /**
     * 简单去除 HTML 标签（不做完整解析）
     */
    private String stripHtmlTags(String text) {
        return text.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
    }

    // ==================== 纯文本切分（降级路径） ====================

    /**
     * 无结构信息的纯文本切分 — 与原 ParagraphChunkStrategy 等价
     */
    private List<Document> chunkPlain(List<Document> documents, String sourceFileName) {
        int minLength = properties.getParagraphMinLength();
        List<Document> allChunks = new ArrayList<>();
        int globalIndex = 0;

        for (Document doc : documents) {
            String text = doc.getText();
            List<String> paragraphs = splitIntoParagraphs(text);
            List<String> merged = mergeShortParagraphs(paragraphs, minLength);

            for (String para : merged) {
                if (para.isBlank()) continue;
                Document chunk = createChunk(para, doc.getMetadata(), sourceFileName, globalIndex, "paragraph");
                allChunks.add(chunk);
                globalIndex++;
            }
        }

        return allChunks;
    }

    // ==================== 通用工具方法 ====================

    /**
     * 按双换行或 Markdown 标题切分段落
     */
    private List<String> splitIntoParagraphs(String text) {
        List<String> paragraphs = new ArrayList<>();
        String[] sections = text.split("(?m)(?=^#{1,6}\\s)");

        for (String section : sections) {
            if (section.isBlank()) continue;
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

    /**
     * 创建切分后的 Document，保留原始 metadata 并追加切分元数据
     */
    private Document createChunk(String content, Map<String, Object> sourceMeta,
                                 String sourceFileName, int chunkIndex, String chunkType) {
        Document chunk = new Document(content);
        // 继承原始 metadata（如 heading 层级、页码等）
        chunk.getMetadata().putAll(sourceMeta);
        chunk.getMetadata().put("source", sourceFileName);
        chunk.getMetadata().put("chunkIndex", chunkIndex);
        chunk.getMetadata().put("chunkType", chunkType);
        return chunk;
    }

    /**
     * 安全获取 metadata 字符串值
     */
    private String metaValue(Map<String, Object> meta, String key) {
        Object val = meta.get(key);
        return val != null ? val.toString() : null;
    }
}
