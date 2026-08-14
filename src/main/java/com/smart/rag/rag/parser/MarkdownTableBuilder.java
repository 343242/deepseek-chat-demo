package com.smart.rag.rag.parser;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Excel 行数据 → Markdown 表格构建器。
 * <p>
 * 从 {@link ExcelDocumentParser} 抽取的表格构建协作类。
 */
final class MarkdownTableBuilder {

    /** StringBuilder 初始容量上限：预估容量再大也不超过 64KB，避免病态列数撑爆内存 */
    private static final int MAX_INITIAL_CAPACITY = 65_536;
    /** 表头行 + 分隔行（Markdown 表格固定的 2 个非数据行） */
    private static final int NON_DATA_ROWS = 2;
    /** 每列每行预估字符数（用于 StringBuilder 容量预估） */
    private static final int CHARS_PER_CELL_ESTIMATE = 20;

    private MarkdownTableBuilder() {
    }

    /**
     * 将行数据转为 Markdown 表格。
     * <p>
     * 优化：cell 值只在包含需要转义的字符时才做 replace 操作。
     *
     * @param headers 表头
     * @param rows    数据行
     * @return Markdown 表格字符串
     */
    static String buildMarkdownTable(List<String> headers, List<Map<Integer, String>> rows) {
        int colCount = headers.size();
        // 预估容量：（表头行+分隔行+数据行）× 每行约 colCount * 20 字符
        int estimatedSize = (NON_DATA_ROWS + rows.size()) * (colCount * CHARS_PER_CELL_ESTIMATE);
        StringBuilder sb = new StringBuilder(Math.min(estimatedSize, MAX_INITIAL_CAPACITY));

        // Header row
        sb.append("| ");
        sb.append(String.join(" | ", headers));
        sb.append(" |\n");

        // Separator row
        sb.append("|");
        for (int c = 0; c < colCount; c++) {
            sb.append("---|");
        }
        sb.append("\n");

        // Data rows
        for (Map<Integer, String> row : rows) {
            sb.append("| ");
            for (int c = 0; c < colCount; c++) {
                if (c > 0) sb.append(" | ");
                String val = row.get(c);
                if (val != null) {
                    sb.append(escapeCell(val));
                }
            }
            sb.append(" |\n");
        }

        return sb.toString();
    }

    /**
     * 转义 cell 值中的管道符和换行符。
     * <p>
     * 优化：先用 {@link String#indexOf} 检查是否包含需要转义的字符，
     * 避免对大多数不含特殊字符的 cell 做无谓的 replace。
     *
     * @param val 原始 cell 值
     * @return 转义后的值
     */
    private static String escapeCell(String val) {
        boolean hasPipe = val.indexOf('|') >= 0;
        boolean hasNewline = val.indexOf('\n') >= 0;

        if (!hasPipe && !hasNewline) {
            return val.trim();
        }

        String result = val;
        if (hasPipe) result = result.replace("|", "\\|");
        if (hasNewline) result = result.replace("\n", " ");
        return result.trim();
    }

    /**
     * 根据列索引生成列名（A, B, C, ... Z, AA, AB, ...）。
     */
    static String generateColumnName(int index) {
        StringBuilder sb = new StringBuilder(3);
        int n = index;
        do {
            sb.insert(0, (char) ('A' + (n % 26)));
            n = n / 26 - 1;
        } while (n >= 0);
        return sb.toString();
    }

    /**
     * 生成 A, B, C, ... Z, AA, AB, ... 列名。
     */
    static List<String> generateColumnNames(int count) {
        List<String> names = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            names.add(generateColumnName(i));
        }
        return names;
    }
}
