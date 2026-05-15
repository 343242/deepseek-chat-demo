package com.demo.chat.rag.parser;

import com.demo.chat.rag.config.DocumentProperties;
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
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * XLSX 专用解析器
 * <p>
 * 使用 Apache Fesod（EasyExcel Apache 孵化版）流式读取 XLSX 文件，保留表格结构信息：
 * <ul>
 *   <li>每个 Sheet 独立处理</li>
 *   <li>启发式表头检测（detectHeader）</li>
 *   <li>小 Sheet 整体输出，大 Sheet 按 excelRowsPerChunk 分块</li>
 *   <li>Markdown table 格式输出</li>
 * </ul>
 * <p>
 * 相比 Tika 的优势：保留 Sheet 名称、行列结构，避免全部拍平为纯文本。
 * Fesod 流式读取 API 确保大文件不 OOM。
 */
@Component
public class ExcelDocumentParser implements DocumentParser {

    private static final Logger log = LoggerFactory.getLogger(ExcelDocumentParser.class);

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
            // First pass: get sheet metadata (names, count)
            List<ReadSheet> readSheets = FesodSheet.read(is).build().excelExecutor().sheetList();
            int sheetCount = readSheets.size();

            for (int sheetIndex = 0; sheetIndex < sheetCount; sheetIndex++) {
                String sheetName = readSheets.get(sheetIndex).getSheetName();

                try (InputStream sheetIs = resource.getInputStream()) {
                    processSheet(sheetIs, sheetIndex, sheetName, sheetCount, fileName, mimeType, documents);
                } catch (DocumentParseException e) {
                    throw e;
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

    /**
     * 处理单个 Sheet，读取所有行并按分块策略生成 Document。
     *
     * @param inputStream 输入流
     * @param sheetIndex  Sheet 序号（0-based）
     * @param sheetName   Sheet 名称
     * @param sheetCount  总 Sheet 数
     * @param fileName    文件名
     * @param mimeType    MIME 类型
     * @param documents   输出文档列表
     */
    private void processSheet(InputStream inputStream, int sheetIndex, String sheetName,
                              int sheetCount, String fileName, String mimeType,
                              List<Document> documents) {
        int rowsPerChunk = documentProperties.getExcelRowsPerChunk();

        List<Map<Integer, String>> allRows = Collections.synchronizedList(new ArrayList<>());

        FesodSheet.read(inputStream, (Class<Map<Integer, String>>) null,
                new ReadListener<Map<Integer, String>>() {
                    @Override
                    public void invoke(Map<Integer, String> data, AnalysisContext context) {
                        allRows.add(data);
                    }

                    @Override
                    public void doAfterAllAnalysed(AnalysisContext context) {
                        // Sheet reading complete
                    }
                }
        ).sheet(sheetIndex).doRead();

        if (allRows.isEmpty()) {
            log.debug("Sheet '{}' is empty, skipping", sheetName);
            return;
        }

        int rowCount = allRows.size();

        // Detect header
        boolean hasHeader = detectHeader(allRows.get(0));
        List<String> headers;
        if (hasHeader) {
            headers = extractHeaders(allRows.get(0));
            allRows.remove(0);
        } else {
            headers = generateColumnNames(getMaxColumn(allRows));
        }

        int dataRowCount = allRows.size();
        if (dataRowCount == 0 && hasHeader) {
            // Only header row, no data — output header only as a small table
            String tableMd = buildMarkdownTable(headers, allRows);
            documents.add(buildDocument(tableMd, sheetIndex, sheetName, sheetCount,
                    rowCount, 0, hasHeader, fileName, mimeType));
            return;
        }

        // Small sheet: single document
        if (dataRowCount <= rowsPerChunk) {
            String tableMd = buildMarkdownTable(headers, allRows);
            documents.add(buildDocument(tableMd, sheetIndex, sheetName, sheetCount,
                    dataRowCount, 0, hasHeader, fileName, mimeType));
        } else {
            // Large sheet: chunked
            int chunkIndex = 0;
            for (int from = 0; from < dataRowCount; from += rowsPerChunk) {
                int to = Math.min(from + rowsPerChunk, dataRowCount);
                List<Map<Integer, String>> chunk = allRows.subList(from, to);
                String tableMd = buildMarkdownTable(headers, chunk);
                documents.add(buildDocument(tableMd, sheetIndex, sheetName, sheetCount,
                        dataRowCount, chunkIndex, hasHeader, fileName, mimeType));
                chunkIndex++;
            }
        }
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
    private boolean detectHeader(Map<Integer, String> firstRow) {
        if (firstRow == null || firstRow.isEmpty()) {
            return false;
        }

        // Check if all non-empty values are pure numbers or date-like
        boolean allNumericOrDate = true;
        boolean hasNonEmpty = false;

        for (String value : firstRow.values()) {
            if (value == null || value.isBlank()) {
                continue;
            }
            hasNonEmpty = true;
            String trimmed = value.trim();

            // Pure number check (integer or decimal, possibly negative)
            if (trimmed.matches("-?\\d+(\\.\\d+)?")) {
                continue;
            }

            // Simple date pattern check (YYYY-MM-DD, YYYY/MM/DD, YYYY-MM-DD HH:mm:ss, etc.)
            if (trimmed.matches("\\d{4}[-/]\\d{1,2}[-/]\\d{1,2}.*")) {
                continue;
            }

            allNumericOrDate = false;
            break;
        }

        // If all values are numeric/date and there's at least one non-empty value → no header
        return !(allNumericOrDate && hasNonEmpty);
    }

    /**
     * 从第一行提取表头名称。
     */
    private List<String> extractHeaders(Map<Integer, String> headerRow) {
        int maxCol = getMaxColumn(List.of(headerRow));
        List<String> headers = new ArrayList<>();
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
        List<String> names = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            names.add(generateColumnName(i));
        }
        return names;
    }

    /**
     * 根据列索引生成列名（A, B, C, ... Z, AA, AB, ...）。
     */
    private String generateColumnName(int index) {
        StringBuilder sb = new StringBuilder();
        int n = index;
        do {
            sb.insert(0, (char) ('A' + (n % 26)));
            n = n / 26 - 1;
        } while (n >= 0);
        return sb.toString();
    }

    /**
     * 获取行列表中的最大列数。
     */
    private int getMaxColumn(List<Map<Integer, String>> rows) {
        int max = 0;
        for (Map<Integer, String> row : rows) {
            for (Integer key : row.keySet()) {
                if (key + 1 > max) {
                    max = key + 1;
                }
            }
        }
        return max == 0 ? 1 : max;
    }

    /**
     * 将行数据转为 Markdown 表格。
     *
     * @param headers 表头
     * @param rows    数据行
     * @return Markdown 表格字符串
     */
    private String buildMarkdownTable(List<String> headers, List<Map<Integer, String>> rows) {
        StringBuilder sb = new StringBuilder();
        int colCount = headers.size();

        // Header row
        sb.append("| ");
        sb.append(String.join(" | ", headers));
        sb.append(" |\n");

        // Separator row
        sb.append("|");
        sb.append("---|".repeat(colCount));
        sb.append("\n");

        // Data rows
        for (Map<Integer, String> row : rows) {
            sb.append("| ");
            for (int c = 0; c < colCount; c++) {
                if (c > 0) sb.append(" | ");
                String val = row.get(c);
                sb.append(val != null ? val.replace("|", "\\|").replace("\n", " ").trim() : "");
            }
            sb.append(" |\n");
        }

        return sb.toString();
    }

    /**
     * 构建 Excel Document 的 metadata。
     */
    private Document buildDocument(String content, int sheetIndex, String sheetName,
                                   int sheetCount, int rowCount, int chunkIndex,
                                   boolean hasHeader, String fileName, String mimeType) {
        Map<String, Object> meta = new HashMap<>();
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
