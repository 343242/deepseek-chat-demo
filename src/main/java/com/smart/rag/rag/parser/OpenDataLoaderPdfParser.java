package com.smart.rag.rag.parser;

import com.smart.rag.rag.config.DocumentProperties;
import org.apache.commons.io.input.BoundedInputStream;
import org.jspecify.annotations.NonNull;
import org.opendataloader.pdf.api.Config;
import org.opendataloader.pdf.api.OpenDataLoaderPDF;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;

/**
 * OpenDataLoader PDF 解析器 — 结构化 PDF 提取
 * <p>
 * 使用 OpenDataLoader PDF Core（确定性启发式引擎）替代 PdfBox 纯文本提取。
 * 保留文档语义结构：标题层级、表格、阅读顺序、列表等，输出 Markdown 格式。
 * <p>
 * 集成策略：
 * <ol>
 *   <li>将 MinIO Resource 写入临时文件</li>
 *   <li>调用 OpenDataLoaderPDF.processFile() 输出 Markdown</li>
 *   <li>读取 Markdown 内容作为单个 Document 返回</li>
 *   <li>清理临时文件和输出目录</li>
 *   <li>后续由 Parent-Child 分块策略按 Markdown 标题层级切分</li>
 * </ol>
 * <p>
 * R2-M1: 写临时文件前用 {@link BoundedInputStream} 在流级别约束读取上限，
 * 上限直接从 {@link DocumentProperties#getMaxFileSize()} 读取（默认 50MB）。
 * 不依赖 {@code resource.contentLength()}（MinIO 流返回 -1 导致 size 检查失效）。
 * 超限抛 {@link DocumentParseException}，避免磁盘填充与下游 OOM。
 */
@Component
public class OpenDataLoaderPdfParser implements DocumentParser {

    private static final Logger log = LoggerFactory.getLogger(OpenDataLoaderPdfParser.class);

    private final DocumentProperties documentProperties;

    public OpenDataLoaderPdfParser(DocumentProperties documentProperties) {
        this.documentProperties = documentProperties;
    }

    @Override
    public List<String> supportedMimeTypes() {
        return List.of("application/pdf");
    }

    @Override
    public List<Document> parse(Resource resource, String mimeType) {
        Path tempPdf = null;
        Path outputDir = null;

        try {
            // R2-M1: 读取上限直接来自 DocumentProperties（与上游校验一致），
            // 并用 BoundedInputStream 在流级别兜底 —— MinIO 流 contentLength()=-1 时
            // 不再依赖 Resource 元信息，避免超大 PDF 填满磁盘。
            long maxBytes = DataSize.parse(documentProperties.getMaxFileSize()).toBytes();

            // 1. 将 Resource 写入临时文件（有界读取：maxBytes+1 以检测溢出）
            tempPdf = Files.createTempFile("rag-pdf-", ".pdf");
            long written;
            try (InputStream raw = resource.getInputStream();
                 // 允许比上限多读 1 字节以判定是否真的超限
                 BoundedInputStream bounded = BoundedInputStream.builder()
                         .setInputStream(raw).setMaxCount(maxBytes + 1).get();
                 var out = Files.newOutputStream(tempPdf)) {
                written = bounded.transferTo(out);
            }
            if (written > maxBytes) {
                throw new DocumentParseException(
                        resource.getFilename(), "opendataloader",
                        String.format("PDF 超过最大允许大小 %s（拒绝写入以避免磁盘填充）",
                                documentProperties.getMaxFileSize()));
            }
            log.debug("PDF written to temp file: {} ({} bytes)", tempPdf, written);

            // 2. 创建输出目录
            outputDir = Files.createTempDirectory("rag-odl-");

            // 3. 调用 OpenDataLoader（只输出 Markdown）
            Config config = new Config();
            config.setOutputFolder(outputDir.toAbsolutePath().toString());
            config.setGenerateMarkdown(true);
            config.setGenerateJSON(false);
            config.setGenerateHtml(false);
            config.setGeneratePDF(false);

            OpenDataLoaderPDF.processFile(tempPdf.toAbsolutePath().toString(), config);
            log.debug("OpenDataLoader processed: {}", tempPdf.getFileName());

            // 4. 读取输出的 Markdown 文件
            String markdown = readMarkdownOutput(outputDir);

            if (markdown == null || markdown.isBlank()) {
                log.warn("OpenDataLoader returned empty markdown for {}, falling back to empty result",
                        resource.getFilename());
                return List.of();
            }

            // 5. 作为单个 Document 返回（保留完整结构，由后续 Parent-Child 分块处理）
            Document doc = new Document(markdown);
            doc.getMetadata().put("parser", "opendataloader");
            doc.getMetadata().put("mimeType", mimeType);
            doc.getMetadata().put("source", resource.getFilename());
            // 统计 Markdown 标题数作为结构化程度指标
            long headingCount = markdown.lines().filter(line -> line.startsWith("#")).count();
            doc.getMetadata().put("headingCount", headingCount);

            log.info("OpenDataLoader parsed: {} → {} chars markdown", resource.getFilename(), markdown.length());
            return List.of(doc);

        } catch (DocumentParseException e) {
            // R2-M1: 超限异常已有具体消息，原样上抛避免被通用消息覆盖
            throw e;
        } catch (Exception e) {
            throw new DocumentParseException(
                    resource.getFilename(), "opendataloader",
                    "PDF parsing failed", e);
        } finally {
            // 6. 清理临时文件和输出目录
            safeDelete(tempPdf);
            safeDeleteRecursive(outputDir);
        }
    }

    /**
     * 从输出目录中读取 Markdown 文件。
     * OpenDataLoader 输出文件名格式：{originalName}_output.md
     */
    private String readMarkdownOutput(Path outputDir) throws IOException {
        List<Path> mdFiles = new ArrayList<>();
        try (var stream = Files.newDirectoryStream(outputDir, "*.md")) {
            for (Path mdFile : stream) {
                mdFiles.add(mdFile);
            }
        }
        if (mdFiles.size() > 1) {
            log.warn("Multiple ({}) markdown outputs found in {}, using the first non-empty one: {}",
                    mdFiles.size(), outputDir, mdFiles);
        }
        for (Path mdFile : mdFiles) {
            String content = Files.readString(mdFile);
            if (!content.isBlank()) {
                return content;
            }
        }
        return null;
    }

    private void safeDelete(Path path) {
        if (path != null) {
            try {
                Files.deleteIfExists(path);
            } catch (IOException e) {
                log.warn("Failed to delete temp file: {}", path, e);
            }
        }
    }

    private void safeDeleteRecursive(Path dir) {
        if (dir == null || !Files.exists(dir)) return;
        try {
            Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                @Override
                public @NonNull FileVisitResult visitFile(@NonNull Path file, @NonNull BasicFileAttributes attrs) throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public @NonNull FileVisitResult postVisitDirectory(@NonNull Path d, IOException exc) throws IOException {
                    Files.delete(d);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            log.warn("Failed to delete temp directory: {}", dir, e);
        }
    }
}
