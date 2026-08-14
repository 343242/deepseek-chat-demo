package com.smart.rag.rag.parser;

import org.apache.poi.xslf.usermodel.XSLFTable;
import org.apache.poi.xslf.usermodel.XSLFTableCell;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * PPT 表格 → Markdown 渲染器。
 * <p>
 * 从 {@link PptDocumentParser} 抽取的表格渲染协作类，
 * 含病态大表格（rows*cols）OOM 防御上限。
 */
final class PptTableRenderer {

    private static final Logger log = LoggerFactory.getLogger(PptTableRenderer.class);

    /** R2-L2: 病态大表格（rows*cols）OOM 防御上限 */
    static final int MAX_TABLE_ROWS = 500;
    static final int MAX_TABLE_COLS = 50;

    private PptTableRenderer() {
    }

    /**
     * 将 XSLFTable 转为 Markdown 表格字符串。
     *
     * @param table PPT 表格
     * @return Markdown 格式表格，空表格返回 null
     */
    static String tableToMarkdown(XSLFTable table) {
        int rows = table.getNumberOfRows();
        int cols = table.getNumberOfColumns();
        if (rows == 0 || cols == 0) {
            return null;
        }

        int effRows = Math.min(rows, MAX_TABLE_ROWS);
        int effCols = Math.min(cols, MAX_TABLE_COLS);
        if (effRows < rows || effCols < cols) {
            log.warn("PPT table truncated: rows {}→{}, cols {}→{}", rows, effRows, cols, effCols);
        }

        StringBuilder sb = new StringBuilder();

        // 表头行
        sb.append("| ");
        for (int c = 0; c < effCols; c++) {
            if (c > 0) sb.append(" | ");
            sb.append(getCellText(table.getCell(0, c)));
        }
        sb.append(" |\n");

        // 分隔行
        sb.append("|");
        sb.repeat("---|", effCols);
        sb.append("\n");

        // 数据行
        for (int r = 1; r < effRows; r++) {
            sb.append("| ");
            for (int c = 0; c < effCols; c++) {
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
    private static String getCellText(XSLFTableCell cell) {
        if (cell == null) return "";
        String text = cell.getText();
        return text == null ? "" : text.replace("|", "\\|").replace("\n", " ").trim();
    }
}
