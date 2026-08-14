package com.smart.rag.rag.parser;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Excel 表头启发式检测器。
 * <p>
 * 从 {@link ExcelDocumentParser} 抽取的表头处理协作类。
 */
final class HeaderDetector {

    /** 预编译正则：纯数字（整数或小数，可能为负） */
    private static final Pattern NUMERIC_PATTERN = Pattern.compile("-?\\d+(\\.\\d+)?");
    /** 预编译正则：日期格式（YYYY-MM-DD, YYYY/MM/DD 等） */
    private static final Pattern DATE_PATTERN = Pattern.compile("\\d{4}[-/]\\d{1,2}[-/]\\d{1,2}.*");

    private HeaderDetector() {
    }

    /**
     * 启发式判断第一行是否为表头。
     * <p>
     * 规则：
     * <ol>
     *   <li>默认第一行为表头（最常见情况）</li>
     *   <li>如果第一行所有非空值都是纯数字或日期格式 → 判定为无表头，用 A, B, C... 做列名</li>
     * </ol>
     *
     * @param firstRow 第一行数据（列索引 → 单元格文本）
     * @return true 表示第一行是表头
     */
    static boolean detectHeader(Map<Integer, String> firstRow) {
        if (firstRow == null || firstRow.isEmpty()) {
            return false;
        }

        boolean allNumericOrDate = true;
        boolean hasNonEmpty = false;

        for (String value : firstRow.values()) {
            if (value == null || value.isBlank()) {
                continue;
            }
            hasNonEmpty = true;
            String trimmed = value.trim();

            if (NUMERIC_PATTERN.matcher(trimmed).matches()) {
                continue;
            }
            if (DATE_PATTERN.matcher(trimmed).matches()) {
                continue;
            }

            allNumericOrDate = false;
            break;
        }

        return !(allNumericOrDate && hasNonEmpty);
    }

    /**
     * 从第一行提取表头名称。
     */
    static List<String> extractHeaders(Map<Integer, String> headerRow) {
        int maxCol = getMaxColumn(headerRow);
        List<String> headers = new ArrayList<>(maxCol);
        for (int i = 0; i < maxCol; i++) {
            String val = headerRow.get(i);
            headers.add(val != null && !val.isBlank() ? val.trim() : MarkdownTableBuilder.generateColumnName(i));
        }
        return headers;
    }

    /**
     * 获取单行中的最大列数（key + 1）。
     */
    static int getMaxColumn(Map<Integer, String> row) {
        int max = 0;
        for (Integer key : row.keySet()) {
            if (key + 1 > max) {
                max = key + 1;
            }
        }
        return max == 0 ? 1 : max;
    }
}
