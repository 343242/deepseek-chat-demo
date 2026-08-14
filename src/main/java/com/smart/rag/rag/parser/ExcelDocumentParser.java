package com.smart.rag.rag.parser;

import com.smart.rag.rag.config.DocumentProperties;
import org.apache.commons.io.input.BoundedInputStream;
import org.apache.fesod.sheet.FesodSheet;
import org.apache.fesod.sheet.context.AnalysisContext;
import org.apache.fesod.sheet.read.listener.ReadListener;
import org.apache.fesod.sheet.read.metadata.ReadSheet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * XLSX 专用解析器
 * <p>
 * 使用 Apache Fesod（EasyExcel Apache 孵化版）流式读取 XLSX 文件，保留表格结构信息：
 * <ul>
 *   <li>每个 Sheet 独立处理</li>
 *   <li>启发式表头检测（{@link HeaderDetector}）</li>
 *   <li>流式分块：大 Sheet 按行数阈值边读边输出，避免全量加载到内存</li>
 *   <li>Markdown table 格式输出（{@link MarkdownTableBuilder}）</li>
 * </ul>
 * <p>
 * 相比 Tika 的优势：保留 Sheet 名称、行列结构，避免全部拍平为纯文本。
 * Fesod {@link ReadListener} 流式读取 API 确保大文件不 OOM。
 */
@Component
public class ExcelDocumentParser implements DocumentParser {

    private static final Logger log = LoggerFactory.getLogger(ExcelDocumentParser.class);

    /** Excel Document 的 metadata 条目数（load factor 0.75 下容量 16 避免扩容） */
    private static final int META_INITIAL_CAPACITY = 16;

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
        long maxBytes = DataSize.parse(documentProperties.getMaxFileSize()).toBytes();

        try (InputStream is = resource.getInputStream();
             // 流级读取上限（MinIO 流 contentLength()=-1 时元信息检查失效，故在流级兜底）
             BoundedInputStream bounded = BoundedInputStream.builder()
                     .setInputStream(is).setMaxCount(maxBytes).get()) {
            // 单次打开 InputStream：先获取 Sheet 元数据，再逐 Sheet 流式读取
            var readerBuilder = FesodSheet.read(bounded);

            // ExcelReader 是 Closeable，sheetList() 阶段结束后及时关闭；
            // 后续 doRead 由 builder 重新构建 reader（原逻辑即复用同一 builder）
            List<ReadSheet> readSheets;
            try (var reader = readerBuilder.build()) {
                readSheets = reader.excelExecutor().sheetList();
            }
            int sheetCount = readSheets.size();

            for (int sheetIndex = 0; sheetIndex < sheetCount; sheetIndex++) {
                String sheetName = readSheets.get(sheetIndex).getSheetName();
                try {
                    processSheetStreaming(readerBuilder, sheetIndex, sheetName, sheetCount,
                            fileName, mimeType, documents);
                } catch (Exception e) {
                    // 传入异常对象本身，保留堆栈（e.getMessage() 可能为 null）
                    log.warn("Failed to parse sheet '{}' in file '{}'", sheetName, fileName, e);
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
     * 流式 Sheet 读取监听器（静态嵌套类，无外部类隐式引用）。
     * <p>
     * 状态机：
     * <ol>
     *   <li>收到第一行 → 表头检测（{@link HeaderDetector}）→ 确定列名</li>
     *   <li>累积数据行到 chunk buffer</li>
     *   <li>buffer 满了 → flush 为一个 Document → 清空 buffer</li>
     *   <li>全部读完 → flush 剩余行</li>
     * </ol>
     * <p>
     * 内存中最多持有 {@code rowsPerChunk} 行数据。
     */
    private static final class StreamingSheetListener implements ReadListener<Map<Integer, String>> {

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
                hasHeader = HeaderDetector.detectHeader(data);
                if (hasHeader) {
                    headers = HeaderDetector.extractHeaders(data);
                    return; // 表头行不进入数据缓冲区
                } else {
                    headers = MarkdownTableBuilder.generateColumnNames(HeaderDetector.getMaxColumn(data));
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
            String tableMd = MarkdownTableBuilder.buildMarkdownTable(headers, chunkBuffer);
            documents.add(buildDocument(tableMd, sheetIndex, sheetName, sheetCount,
                    totalRowCount, chunkIndex, hasHeader, fileName, mimeType));
            chunkBuffer = new ArrayList<>(rowsPerChunk);
            chunkIndex++;
        }

        /**
         * 构建 Excel Document 的 metadata。
         *
         * @param rowCount 该 Sheet 的总行数（含表头行，如有）
         */
        private static Document buildDocument(String content, int sheetIndex, String sheetName,
                                              int sheetCount, int rowCount, int chunkIndex,
                                              boolean hasHeader, String fileName, String mimeType) {
            Map<String, Object> meta = new HashMap<>(META_INITIAL_CAPACITY);
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
}
