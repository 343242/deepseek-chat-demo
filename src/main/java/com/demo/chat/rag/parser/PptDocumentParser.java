package com.demo.chat.rag.parser;

import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFGroupShape;
import org.apache.poi.xslf.usermodel.XSLFNotes;
import org.apache.poi.xslf.usermodel.XSLFPictureShape;
import org.apache.poi.xslf.usermodel.XSLFShape;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTable;
import org.apache.poi.xslf.usermodel.XSLFTableCell;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * PPTX 专用解析器
 * <p>
 * 使用 Apache POI XSLF 直接解析 PPTX 文件，保留幻灯片结构信息：
 * <ul>
 *   <li>Slide 序号和总数</li>
 *   <li>Shape 类型（content / table / notes）</li>
 *   <li>同一 Slide 的 Title + Content 文本合并为一个 Document</li>
 *   <li>Table 转为 Markdown 表格，独立输出</li>
 *   <li>Notes 独立输出</li>
 * </ul>
 * <p>
 * 相比 Tika 的优势：保留 Slide 结构、表格格式、备注信息，避免全部拍平为纯文本。
 */
@Component
public class PptDocumentParser implements DocumentParser {

    private static final Logger log = LoggerFactory.getLogger(PptDocumentParser.class);

    /** GroupShape 递归深度上限，防止恶意嵌套 */
    private static final int MAX_GROUP_DEPTH = 5;

    @Override
    public List<String> supportedMimeTypes() {
        return List.of(
                "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        );
    }

    @Override
    public List<Document> parse(Resource resource, String mimeType) {
        String fileName = resource.getFilename();
        log.debug("Parsing PPTX with Apache POI XSLF: file={}", fileName);

        List<Document> documents = new ArrayList<>();

        try (InputStream is = resource.getInputStream();
             XMLSlideShow slideShow = new XMLSlideShow(is)) {

            List<XSLFSlide> slides = slideShow.getSlides();
            int slideCount = slides.size();

            for (int slideIndex = 0; slideIndex < slideCount; slideIndex++) {
                XSLFSlide slide = slides.get(slideIndex);
                processSlide(slide, slideIndex, slideCount, fileName, mimeType, documents);
            }

        } catch (DocumentParseException e) {
            throw e;
        } catch (Exception e) {
            throw new DocumentParseException(fileName, "ppt", "Unexpected error", e);
        }

        log.debug("PPTX parsed: {} documents from {}", documents.size(), fileName);
        return documents;
    }

    /**
     * 处理单个 Slide，按 Shape 类型分别生成 Document。
     * <p>
     * 合并策略（方案 A）：同一 Slide 的 Title 和 Content 文本合并为一个 Document，
     * Table 和 Notes 各自独立输出。
     *
     * @param slide      当前 Slide
     * @param slideIndex Slide 序号（0-based）
     * @param slideCount 总 Slide 数
     * @param fileName   文件名
     * @param mimeType   MIME 类型
     * @param documents  输出文档列表
     */
    private void processSlide(XSLFSlide slide, int slideIndex, int slideCount,
                              String fileName, String mimeType, List<Document> documents) {
        StringBuilder textBuffer = new StringBuilder();
        boolean hasImage = false;

        for (XSLFShape shape : slide.getShapes()) {
            try {
                if (shape instanceof XSLFTextShape textShape) {
                    collectTextShape(textShape, textBuffer);
                } else if (shape instanceof XSLFTable table) {
                    // 先 flush 已累积的文本
                    flushText(textBuffer, hasImage, slideIndex, slideCount, fileName, mimeType, documents);
                    textBuffer = new StringBuilder();
                    hasImage = false;

                    String tableMd = tableToMarkdown(table);
                    if (tableMd != null && !tableMd.isBlank()) {
                        Map<String, Object> meta = buildMetadata(slideIndex, slideCount, fileName, mimeType, "table");
                        meta.put("hasImage", false);
                        documents.add(new Document(tableMd, meta));
                    }
                } else if (shape instanceof XSLFPictureShape) {
                    hasImage = true;
                } else if (shape instanceof XSLFGroupShape groupShape) {
                    processGroupShape(groupShape, textBuffer, 1, fileName);
                }
            } catch (Exception e) {
                log.warn("Failed to process shape on slide {}: {}", slideIndex, e.getMessage());
            }
        }

        // 处理 Notes
        try {
            XSLFNotes notes = slide.getNotes();
            if (notes != null) {
                StringBuilder notesBuffer = new StringBuilder();
                for (XSLFShape notesShape : notes.getShapes()) {
                    if (notesShape instanceof XSLFTextShape textShape) {
                        String text = textShape.getText();
                        if (text != null && !text.isBlank()) {
                            if (!notesBuffer.isEmpty()) {
                                notesBuffer.append("\n");
                            }
                            notesBuffer.append(text.trim());
                        }
                    }
                }
                if (!notesBuffer.isEmpty()) {
                    Map<String, Object> meta = buildMetadata(slideIndex, slideCount, fileName, mimeType, "notes");
                    meta.put("hasImage", false);
                    documents.add(new Document(notesBuffer.toString(), meta));
                }
            }
        } catch (Exception e) {
            log.warn("Failed to process notes on slide {}: {}", slideIndex, e.getMessage());
        }

        // flush 剩余文本
        flushText(textBuffer, hasImage, slideIndex, slideCount, fileName, mimeType, documents);
    }

    /**
     * 从 XSLFTextShape 提取文本，追加到缓冲区。
     * Title 占位符的文本前置标记。
     *
     * @param textShape  文本形状
     * @param textBuffer 文本缓冲区
     */
    private void collectTextShape(XSLFTextShape textShape, StringBuilder textBuffer) {
        String text = textShape.getText();
        if (text == null || text.isBlank()) {
            return;
        }

        if (!textBuffer.isEmpty()) {
            textBuffer.append("\n");
        }

        // Title 类型文本前置标记
        if (isTitlePlaceholder(textShape)) {
            textBuffer.append("# ").append(text.trim());
        } else {
            textBuffer.append(text.trim());
        }
    }

    /**
     * 判断 TextShape 是否为标题占位符。
     */
    private boolean isTitlePlaceholder(XSLFTextShape textShape) {
        try {
            var placeholder = textShape.getPlaceholder();
            // Using enum name comparison for robustness
            if (placeholder == null) return false;
            String name = placeholder.name();
            return name.contains("TITLE") || name.contains("CENTER");
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 递归处理 GroupShape，提取内部文本。
     *
     * @param group      分组形状
     * @param textBuffer 文本缓冲区
     * @param depth      当前递归深度
     * @param fileName   文件名（用于日志）
     */
    private void processGroupShape(XSLFGroupShape group, StringBuilder textBuffer, int depth, String fileName) {
        if (depth > MAX_GROUP_DEPTH) {
            log.warn("GroupShape recursion depth exceeded {} in file={}, stopping", MAX_GROUP_DEPTH, fileName);
            return;
        }

        for (XSLFShape shape : group.getShapes()) {
            try {
                if (shape instanceof XSLFTextShape textShape) {
                    collectTextShape(textShape, textBuffer);
                } else if (shape instanceof XSLFGroupShape innerGroup) {
                    processGroupShape(innerGroup, textBuffer, depth + 1, fileName);
                }
            } catch (Exception e) {
                log.warn("Failed to process shape in GroupShape (depth={}): {}", depth, e.getMessage());
            }
        }
    }

    /**
     * 将缓冲区中的文本 flush 为 Document。如果缓冲区为空但有图片，生成标记性 Document。
     */
    private void flushText(StringBuilder textBuffer, boolean hasImage,
                           int slideIndex, int slideCount, String fileName, String mimeType,
                           List<Document> documents) {
        String text = textBuffer.toString().trim();
        if (text.isEmpty() && !hasImage) {
            return;
        }

        // 纯图片 Slide
        if (text.isEmpty() && hasImage) {
            text = "[图片幻灯片]";
        }

        Map<String, Object> meta = buildMetadata(slideIndex, slideCount, fileName, mimeType, "content");
        meta.put("hasImage", hasImage);
        documents.add(new Document(text, meta));
    }

    /**
     * 将 XSLFTable 转为 Markdown 表格字符串。
     *
     * @param table PPT 表格
     * @return Markdown 格式表格
     */
    private String tableToMarkdown(XSLFTable table) {
        int rows = table.getNumberOfRows();
        int cols = table.getNumberOfColumns();
        if (rows == 0 || cols == 0) {
            return null;
        }

        StringBuilder sb = new StringBuilder();

        // 表头行
        sb.append("| ");
        for (int c = 0; c < cols; c++) {
            if (c > 0) sb.append(" | ");
            sb.append(getCellText(table.getCell(0, c)));
        }
        sb.append(" |\n");

        // 分隔行
        sb.append("|");
        sb.repeat("---|", cols);
        sb.append("\n");

        // 数据行
        for (int r = 1; r < rows; r++) {
            sb.append("| ");
            for (int c = 0; c < cols; c++) {
                if (c > 0) sb.append(" | ");
                sb.append(getCellText(table.getCell(r, c)));
            }
            sb.append(" |\n");
        }

        return sb.toString();
    }

    /**
     * 安全获取单元格文本。
     */
    private String getCellText(XSLFTableCell cell) {
        if (cell == null) return "";
        String text = cell.getText();
        return text == null ? "" : text.replace("|", "\\|").replace("\n", " ").trim();
    }

    /**
     * 构建基础 metadata Map。
     */
    private Map<String, Object> buildMetadata(int slideIndex, int slideCount, String fileName, String mimeType, String shapeType) {
        Map<String, Object> meta = new HashMap<>();
        meta.put("parser", "ppt");
        meta.put("mimeType", mimeType);
        meta.put("slideIndex", slideIndex);
        meta.put("slideCount", slideCount);
        meta.put("shapeType", shapeType);
        meta.put("source", fileName != null ? fileName : "unknown");
        return meta;
    }
}
