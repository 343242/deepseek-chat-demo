package com.smart.rag.rag.parser;

import com.smart.rag.rag.config.DocumentProperties;
import org.apache.fesod.sheet.FesodSheet;
import org.apache.fesod.sheet.context.AnalysisContext;
import org.apache.fesod.sheet.read.listener.ReadListener;
import org.apache.fesod.sheet.read.metadata.ReadSheet;
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
import java.util.regex.Pattern;

/**
 * XLSX 专用解析器
 * <p>
 * 使用 Apache Fesod（EasyExcel Apache 孵化版）流式读取 XLSX 文件，保留表格结构信息：
 * <ul>
 *   <li>每个 Sheet 独立处理</li>
 *   <li>启发式表头检测（detectHeader）</li>
 *   <li>流式分块：大 Sheet 按行数阈值边读边输出，避免全量加载到内存</li>
 *   <li>Markdown table 格式输出</li>
 * </ul>
 * <p>
 * 相比 Tika 的优势：保留 Sheet 名称、行列结构，避免全部拍平为纯文本。
 * Fesod {@link ReadListener} 流式读取 API 确保大文件不 OOM。
 */
@Component
public class ExcelDocumentParser implements DocumentParser {

    private static final Logger log = LoggerFactory.getLogger(ExcelDocumentParser.class);

    /** 预编译正则：纯数字（整数或小数，可能为负） */
    private static final Pattern NUMERIC_PATTERN = Pattern.compile("-?\\d+(\\.\\d+)?");
    /** 预编译正则：日期格式（YYYY-MM-DD, YYYY/MM/DD 等） */
    private static final Pattern DATE_PATTERN = Pattern.compile("\\d{4}[-/]\\d{1,2}[-/]\\d{1,2}.*");

    private final DocumentProperties documentProperties;

    public ExcelDocumentParser(DocumentProperties documentProperties) {
        this.documentProperties = documentProperties;
    }

    @Override
    public List<String> supportedMimeTypes() {
        return List.of(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        );
    }

    @Override
    public List<Document> parse(Resource resource, String mimeType) {
        String fileName = resource.getFilename();
        log.debug("Parsing XLSX with Apache Fesod: file={}", fileName);

        List<Document> documents = new ArrayList<>();

        try (InputStream is = resource.getInputStream()) {
            // 单次打开 InputStream：先获取 Sheet 元数据，再逐 Sheet 流式读取
            var readerBuilder = FesodSheet.read(is);
            List<ReadSheet> readSheets = readerBuilder.build().excelExecutor().sheetList();
            int sheetCount = readSheets.size();

            for (int sheetIndex = 0; sheetIndex < sheetCount; sheetIndex++) {
                String sheetName = readSheets.get(sheetIndex).getSheetName();
                try {
                    processSheetStreaming(readerBuilder, sheetIndex, sheetName, sheetCount,
                            fileName, mimeType, documents);
                } catch (Exception e) {
                    log.warn("Failed to parse sheet '{}' in file '{}': {}", sheetName, fileName, e.getMessage());
                }
            }

        } catch (DocumentParseException e) {
            throw e;
        } catch (Exception e) {
            throw new DocumentParseException(fileName, "excel", "Unexpected error", e);
        }

        log.debug("XLSX parsed: {} documents from {}", documents.size(), fileName);
        return documents;
    }

    // ======================== 流式 Sheet 处理 ========================

    /**
     * 流式读取单个 Sheet，使用 {@link ReadListener} 边读边分块输出。
     * <p>
     * 内存中最多持有 {@code excelRowsPerChunk} 行数据，而非整个 Sheet。
     * 第一行用于表头检测，检测完成后立即开始分块。
     *
     * @param builder    Fesod builder（复用同一 InputStream）
     * @param sheetIndex Sheet 序号
     * @param sheetName  Sheet 名称
     * @param sheetCount 总 Sheet 数
     * @param fileName   文件名
     * @param mimeType   MIME 类型
     * @param documents  输出文档列表
     */
    private void processSheetStreaming(org.apache.fesod.sheet.read.builder.ExcelReaderBuilder readerBuilder,
                                       int sheetIndex, String sheetName,
                                       int sheetCount, String fileName, String mimeType,
                                       List<Document> documents) {
        int rowsPerChunk = documentProperties.getExcelRowsPerChunk();

        StreamingSheetListener listener = new StreamingSheetListener(
                rowsPerChunk, sheetIndex, sheetName, sheetCount, fileName, mimeType, documents);

        // headRowNumber(0)：不使用注解映射，每行返回 Map<Integer, String>
        readerBuilder
                .headRowNumber(0)
                .registerReadListener(listener)
                .sheet(sheetIndex)
                .doRead();
    }

    /**
     * 流式 Sheet 读取监听器。
     * <p>
     * 状态机：
     * <ol>
     *   <li>收到第一行 → 表头检测 → 确定列名</li>
     *   <li>累积数据行到 chunk buffer</li>
     *   <li>buffer 满了 → flush 为一个 Document → 清空 buffer</li>
     *   <li>全部读完 → flush 剩余行</li>
     * </ol>
     * <p>
     * 内存中最多持有 {@code rowsPerChunk} 行数据。
     */
    private class StreamingSheetListener implements ReadListener<Map<Integer, String>> {

        private final int rowsPerChunk;
        private final int sheetIndex;
        private final String sheetName;
        private final int sheetCount;
        private final String fileName;
        private final String mimeType;
        private final List<Document> documents;

        /** 表头列名 */
        private List<String> headers;
        /** 是否检测到表头 */
        private boolean hasHeader;
        /** 当前 chunk 缓冲区 */
        private List<Map<Integer, String>> chunkBuffer;
        /** 已处理的数据行数（不含表头行） */
        private int dataRowCount;
        /** 总行数（含表头行，如有） */
        private int totalRowCount;
        /** 当前 chunk 序号 */
        private int chunkIndex;

        StreamingSheetListener(int rowsPerChunk, int sheetIndex, String sheetName,
                               int sheetCount, String fileName, String mimeType,
                               List<Document> documents) {
            this.rowsPerChunk = rowsPerChunk;
            this.sheetIndex = sheetIndex;
            this.sheetName = sheetName;
            this.sheetCount = sheetCount;
            this.fileName = fileName;
            this.mimeType = mimeType;
            this.documents = documents;
            this.chunkBuffer = new ArrayList<>(rowsPerChunk);
            this.dataRowCount = 0;
            this.totalRowCount = 0;
            this.chunkIndex = 0;
        }

        @Override
        public void invoke(Map<Integer, String> data, AnalysisContext context) {
            totalRowCount++;

            // 第一行：表头检测
            if (totalRowCount == 1) {
                hasHeader = detectHeader(data);
                if (hasHeader) {
                    headers = extractHeaders(data);
                    return; // 表头行不进入数据缓冲区
                } else {
                    headers = generateColumnNames(getMaxColumn(data));
                }
            }

            // 累积到 chunk buffer
            chunkBuffer.add(data);
            dataRowCount++;

            // buffer 满了，flush
            if (chunkBuffer.size() >= rowsPerChunk) {
                flushChunk();
            }
        }

        @Override
        public void doAfterAllAnalysed(AnalysisContext context) {
            // flush 剩余行
            if (!chunkBuffer.isEmpty() || (dataRowCount == 0 && hasHeader)) {
                flushChunk();
            }

            // 空 Sheet
            if (totalRowCount == 0) {
                log.debug("Sheet '{}' is empty, skipping", sheetName);
                return;
            }

            log.debug("Sheet '{}' parsed: {} total rows, {} data rows, {} chunks",
                    sheetName, totalRowCount, dataRowCount, chunkIndex);
        }

        /**
         * 将当前 chunk buffer 输出为一个 Document，然后清空 buffer。
         */
        private void flushChunk() {
            String tableMd = buildMarkdownTable(headers, chunkBuffer);
            documents.add(buildDocument(tableMd, sheetIndex, sheetName, sheetCount,
                    totalRowCount, chunkIndex, hasHeader, fileName, mimeType));
            chunkBuffer = new ArrayList<>(rowsPerChunk);
            chunkIndex++;
        }
    }

    // ======================== 表头检测 ========================

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
    private boolean detectHeader(Map<Integer, String> firstRow) {
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

    // ======================== 列名处理 ========================

    /**
     * 从第一行提取表头名称。
     */
    private List<String> extractHeaders(Map<Integer, String> headerRow) {
        int maxCol = getMaxColumn(headerRow);
        List<String> headers = new ArrayList<>(maxCol);
        for (int i = 0; i < maxCol; i++) {
            String val = headerRow.get(i);
            headers.add(val != null && !val.isBlank() ? val.trim() : generateColumnName(i));
        }
        return headers;
    }

    /**
     * 生成 A, B, C, ... Z, AA, AB, ... 列名。
     */
    private List<String> generateColumnNames(int count) {
        List<String> names = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            names.add(generateColumnName(i));
        }
        return names;
    }

    /**
     * 根据列索引生成列名（A, B, C, ... Z, AA, AB, ...）。
     */
    private String generateColumnName(int index) {
        StringBuilder sb = new StringBuilder(3);
        int n = index;
        do {
            sb.insert(0, (char) ('A' + (n % 26)));
            n = n / 26 - 1;
        } while (n >= 0);
        return sb.toString();
    }

    /**
     * 获取单行中的最大列数（key + 1）。
     */
    private int getMaxColumn(Map<Integer, String> row) {
        int max = 0;
        for (Integer key : row.keySet()) {
            if (key + 1 > max) {
                max = key + 1;
            }
        }
        return max == 0 ? 1 : max;
    }

    // ======================== Markdown 构建 ========================

    /**
     * 将行数据转为 Markdown 表格。
     * <p>
     * 优化：cell 值只在包含需要转义的字符时才做 replace 操作。
     *
     * @param headers 表头
     * @param rows    数据行
     * @return Markdown 表格字符串
     */
    private String buildMarkdownTable(List<String> headers, List<Map<Integer, String>> rows) {
        int colCount = headers.size();
        // 预估容量：表头行 + 分隔行 + 数据行（每行约 colCount * 15 字符）
        int estimatedSize = (2 + rows.size()) * (colCount * 20);
        StringBuilder sb = new StringBuilder(Math.min(estimatedSize, 65536));

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

    // ======================== Metadata 构建 ========================

    /**
     * 构建 Excel Document 的 metadata。
     *
     * @param rowCount 该 Sheet 的总行数（含表头行，如有）
     */
    private Document buildDocument(String content, int sheetIndex, String sheetName,
                                   int sheetCount, int rowCount, int chunkIndex,
                                   boolean hasHeader, String fileName, String mimeType) {
        Map<String, Object> meta = new HashMap<>(10);
        meta.put("parser", "excel");
        meta.put("mimeType", mimeType);
        meta.put("sheetName", sheetName);
        meta.put("sheetIndex", sheetIndex);
        meta.put("sheetCount", sheetCount);
        meta.put("rowCount", rowCount);
        meta.put("chunkIndex", chunkIndex);
        meta.put("hasHeader", hasHeader);
        meta.put("source", fileName != null ? fileName : "unknown");
        return new Document(content, meta);
    }
}
