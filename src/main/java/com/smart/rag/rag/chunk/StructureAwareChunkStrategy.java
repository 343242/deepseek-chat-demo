package com.smart.rag.rag.chunk;

import com.smart.rag.rag.config.DocumentProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;

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
 *
 * <p>本类负责结构检测 + 分发 + chunk 创建；段落切分/合并/HTML 清理
 * 委托给 {@link ParagraphSplitter}。</p>
 */
@Component
public class StructureAwareChunkStrategy implements ChunkStrategy {

    private static final Logger log = LoggerFactory.getLogger(StructureAwareChunkStrategy.class);

    /** 超过此字符数的 section/page 需要进一步切分 */
    private static final int LONG_SECTION_THRESHOLD = 3000;

    /** Markdown 标题行（用于无 metadata 时的兜底检测） */
    private static final Pattern HEADING_LINE = Pattern.compile("^#{1,6}\\s.+");

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
            if (text != null && text.lines().anyMatch(line -> HEADING_LINE.matcher(line).matches())) {
                return StructureType.MARKDOWN;
            }
        }

        return StructureType.PLAIN;
    }

    // ==================== 各结构类型切分 ====================

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
        return chunkDocuments(documents, sourceFileName,
                "markdown-section", "markdown-paragraph", ParagraphSplitter::splitIntoParagraphs);
    }

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
        List<Document> mergedPages = mergeShortPages(documents, minLength);
        return chunkDocuments(mergedPages, sourceFileName,
                "pdf-page", "pdf-paragraph", ParagraphSplitter::splitIntoParagraphs);
    }

    /**
     * HTML 切分：按块级标签边界识别段落
     * <p>
     * 识别 &lt;h1&gt;-&lt;h6&gt;、&lt;p&gt;、&lt;div&gt;、&lt;li&gt; 作为切分点。
     * HTML 专用 Parser 产出更有结构的 Document，这里做兜底处理。
     */
    private List<Document> chunkHtml(List<Document> documents, String sourceFileName) {
        return chunkDocuments(documents, sourceFileName,
                null, "html-block", ParagraphSplitter::splitHtmlBlocks);
    }

    /**
     * 无结构信息的纯文本切分 — 与原 ParagraphChunkStrategy 等价
     */
    private List<Document> chunkPlain(List<Document> documents, String sourceFileName) {
        return chunkDocuments(documents, sourceFileName,
                null, "paragraph", ParagraphSplitter::splitIntoParagraphs);
    }

    // ==================== 通用切分管线 ====================

    /**
     * 通用切分管线：遍历文档 →（可选）短文档直接保留 → 切分 → 合并短段 → 创建 chunk
     *
     * @param directChunkType 非 null 时，长度 ≤ threshold 的文档作为整体保留并标记该类型
     * @param splitChunkType  切分后产出的 chunk 类型标记
     * @param splitter        文本 → 段落列表的切分函数
     */
    private List<Document> chunkDocuments(List<Document> documents, String sourceFileName,
                                          String directChunkType, String splitChunkType,
                                          Function<String, List<String>> splitter) {
        int minLength = properties.getParagraphMinLength();
        List<Document> allChunks = new ArrayList<>();
        int globalIndex = 0;

        for (Document doc : documents) {
            String text = doc.getText();
            if (text == null || text.isBlank()) continue;

            // 短文档直接保留（仅结构化路径启用）
            if (directChunkType != null && text.length() <= LONG_SECTION_THRESHOLD) {
                allChunks.add(createChunk(text, doc.getMetadata(), sourceFileName, globalIndex, directChunkType));
                globalIndex++;
                continue;
            }

            // 长文档：按结构边界切分并合并短段
            List<String> merged = ParagraphSplitter.mergeShortParagraphs(splitter.apply(text), minLength);

            for (String para : merged) {
                if (para.isBlank()) continue;
                allChunks.add(createChunk(para, doc.getMetadata(), sourceFileName, globalIndex, splitChunkType));
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
        Map<String, Object> currentMeta = new HashMap<>(pages.getFirst().getMetadata());

        for (Document page : pages) {
            String text = page.getText();
            if (text == null) text = "";

            if (!currentText.isEmpty() && currentText.length() >= minLength) {
                result.add(new Document(currentText.toString(), currentMeta));
                currentText = new StringBuilder();
                currentMeta = new HashMap<>(page.getMetadata());
            }

            if (!currentText.isEmpty()) {
                currentText.append("\n\n");
            }
            currentText.append(text);
        }

        if (!currentText.isEmpty()) {
            result.add(new Document(currentText.toString(), currentMeta));
        }

        return result;
    }

    // ==================== 通用工具方法 ====================

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
