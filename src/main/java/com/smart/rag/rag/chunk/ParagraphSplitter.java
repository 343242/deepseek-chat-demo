package com.smart.rag.rag.chunk;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 段落切分工具（供 {@link StructureAwareChunkStrategy} 使用）
 * <p>
 * 职责：段落切分（双换行 / Markdown 标题）、短段落合并、HTML 块级标签切分与标签清理。
 * 所有正则均预编译为 {@code static final} 常量，避免每次调用重复编译。
 */
final class ParagraphSplitter {

    /** HTML 块级闭合标签（p/div/li/h1-6/section/article/blockquote/pre/table/tr） */
    private static final Pattern HTML_BLOCK_CLOSE = Pattern.compile("(?i)</(?:p|div|li|h[1-6]|section|article|blockquote|pre|table|tr)>");

    /** 任意 HTML 标签 */
    private static final Pattern HTML_TAG = Pattern.compile("<[^>]+>");

    /** 连续空白（标签清理后归一化） */
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    /** Markdown 标题行（ATX heading）前瞻切分点 */
    private static final Pattern MARKDOWN_HEADING = Pattern.compile("(?m)(?=^#{1,6}\\s)");

    /** 空行（段落边界） */
    private static final Pattern BLANK_LINE = Pattern.compile("\\n\\s*\\n");

    private ParagraphSplitter() {
    }

    /**
     * 按双换行或 Markdown 标题切分段落
     */
    static List<String> splitIntoParagraphs(String text) {
        List<String> paragraphs = new ArrayList<>();
        String[] sections = MARKDOWN_HEADING.split(text);

        for (String section : sections) {
            if (section.isBlank()) continue;
            String[] parts = BLANK_LINE.split(section);
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
    static List<String> mergeShortParagraphs(List<String> paragraphs, int minLength) {
        if (paragraphs.isEmpty()) return paragraphs;

        List<String> merged = new ArrayList<>();
        StringBuilder current = new StringBuilder(paragraphs.getFirst());

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
     * 按 HTML 块级标签边界切分文本
     * <p>
     * Tika 输出的 HTML 文本通常已去除标签，但保留了一些结构痕迹（如连续换行）。
     * 如果文本中仍有 HTML 标签（如来自原始 HTML），按标签切。
     * 否则退化到段落切分。
     */
    static List<String> splitHtmlBlocks(String text) {
        // 如果文本中仍有 HTML 标签
        if (text.contains("<")) {
            List<String> blocks = new ArrayList<>();
            // 按 </p> </div> </li> </h1-6> 等块级闭合标签切分
            String[] parts = HTML_BLOCK_CLOSE.split(text);
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
    static String stripHtmlTags(String text) {
        return WHITESPACE.matcher(HTML_TAG.matcher(text).replaceAll(" ")).replaceAll(" ").trim();
    }
}
