package com.smart.rag.rag.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.document")
public class DocumentProperties {

    /** 最大文件大小（Spring 格式，如 50MB） */
    private String maxFileSize = "50MB";
    /** 批量上传最大文件数 */
    private int maxBatchFiles = 10;
    /** 批量上传总大小上限（Spring 格式，如 200MB） */
    private String maxBatchTotalSize = "200MB";
    /** 允许的 MIME 类型，逗号分隔 */
    private String allowedMimeTypes = "application/pdf,application/vnd.openxmlformats-officedocument.wordprocessingml.document,application/vnd.openxmlformats-officedocument.presentationml.presentation,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet,text/plain,text/markdown,text/x-markdown,text/html";
    /** 文本类预览的输入大小上限（只限制需全量消费的渲染路径，不影响下载与 PDF 透传） */
    private String maxPreviewFileSize = "5MB";

    // === 分块策略 ===
    /** 分块策略: token / paragraph / parent-child */
    private String chunkStrategy = "parent-child";
    /** 基础分块大小（tokens） */
    private int chunkSize = 800;

    // === Parent-Child 策略参数 ===
    /** 父文档大小（tokens），用于 parent-child 策略 */
    private int parentChunkSize = 2000;
    /** 子切分大小（tokens），用于 parent-child 策略 */
    private int childChunkSize = 500;

    // === Excel 解析参数 ===
    /** Excel 单 Sheet 分块行数，默认 200 */
    private int excelRowsPerChunk = 200;

    // === 段落策略参数 ===
    /** 段落最小长度（低于此值合并到上一段） */
    private int paragraphMinLength = 100;

    // === ODL 前台/后台图片提取参数（design §6.6） ===
    /** 前台每文档逐页并行度（多文档并发部署建议 ≈ cores/8，专机可放宽至 cores/2） */
    private int odlThreads = Math.max(1, Runtime.getRuntime().availableProcessors() / 2);
    /** 后台图片提取并发——1:1 映射 ConsumerConfig.concurrency（runner 处理池线程数 = in-flight 许可数） */
    private int odlImageConcurrency = 1;
    /** PAGE_RENDER 渲染 DPI（2.5.5 getPageSubImage(bbox, dpi)） */
    private int odlImageRenderDpi = 144;
    /** 渲染图 JPEG 质量（控制 MinIO 存储体积） */
    private double odlImageJpegQuality = 0.85;
    /** 单图上传上限（编码前像素规模预判 + 编码后精确复核双闸） */
    private long odlImageMaxBytes = 20 * 1024 * 1024;
    /** 单文档图片对象预算：消费侧 seq 超限行 SKIPPED(doc-image-budget) 不上传 */
    private int odlImageMaxPerDoc = 500;
    /** H3 断言失败档位：true=严格失败（fail-closed）；false=降级剥离占位符纯文本索引+告警 */
    private boolean odlPlaceholderStrict = true;

    public String getMaxFileSize() { return maxFileSize; }
    public void setMaxFileSize(String maxFileSize) { this.maxFileSize = maxFileSize; }
    public int getMaxBatchFiles() { return maxBatchFiles; }
    public void setMaxBatchFiles(int maxBatchFiles) { this.maxBatchFiles = maxBatchFiles; }
    public String getMaxBatchTotalSize() { return maxBatchTotalSize; }
    public void setMaxBatchTotalSize(String maxBatchTotalSize) { this.maxBatchTotalSize = maxBatchTotalSize; }
    public String getAllowedMimeTypes() { return allowedMimeTypes; }
    public void setAllowedMimeTypes(String allowedMimeTypes) { this.allowedMimeTypes = allowedMimeTypes; }
    public String getMaxPreviewFileSize() { return maxPreviewFileSize; }
    public void setMaxPreviewFileSize(String maxPreviewFileSize) { this.maxPreviewFileSize = maxPreviewFileSize; }
    public String getChunkStrategy() { return chunkStrategy; }
    public void setChunkStrategy(String chunkStrategy) { this.chunkStrategy = chunkStrategy; }
    public int getChunkSize() { return chunkSize; }
    public void setChunkSize(int chunkSize) { this.chunkSize = chunkSize; }
    public int getParentChunkSize() { return parentChunkSize; }
    public void setParentChunkSize(int parentChunkSize) { this.parentChunkSize = parentChunkSize; }
    public int getChildChunkSize() { return childChunkSize; }
    public void setChildChunkSize(int childChunkSize) { this.childChunkSize = childChunkSize; }
    public int getParagraphMinLength() { return paragraphMinLength; }
    public void setParagraphMinLength(int paragraphMinLength) { this.paragraphMinLength = paragraphMinLength; }
    public int getExcelRowsPerChunk() { return excelRowsPerChunk; }
    public void setExcelRowsPerChunk(int excelRowsPerChunk) { this.excelRowsPerChunk = excelRowsPerChunk; }
    public int getOdlThreads() { return odlThreads; }
    public void setOdlThreads(int odlThreads) { this.odlThreads = odlThreads; }
    public int getOdlImageConcurrency() { return odlImageConcurrency; }
    public void setOdlImageConcurrency(int odlImageConcurrency) { this.odlImageConcurrency = odlImageConcurrency; }
    public int getOdlImageRenderDpi() { return odlImageRenderDpi; }
    public void setOdlImageRenderDpi(int odlImageRenderDpi) { this.odlImageRenderDpi = odlImageRenderDpi; }
    public double getOdlImageJpegQuality() { return odlImageJpegQuality; }
    public void setOdlImageJpegQuality(double odlImageJpegQuality) { this.odlImageJpegQuality = odlImageJpegQuality; }
    public long getOdlImageMaxBytes() { return odlImageMaxBytes; }
    public void setOdlImageMaxBytes(long odlImageMaxBytes) { this.odlImageMaxBytes = odlImageMaxBytes; }
    public int getOdlImageMaxPerDoc() { return odlImageMaxPerDoc; }
    public void setOdlImageMaxPerDoc(int odlImageMaxPerDoc) { this.odlImageMaxPerDoc = odlImageMaxPerDoc; }
    public boolean isOdlPlaceholderStrict() { return odlPlaceholderStrict; }
    public void setOdlPlaceholderStrict(boolean odlPlaceholderStrict) { this.odlPlaceholderStrict = odlPlaceholderStrict; }
}
