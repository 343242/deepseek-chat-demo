package com.smart.rag.rag.parser;

import org.jspecify.annotations.NonNull;
import org.opendataloader.pdf.api.Config;
import org.opendataloader.pdf.api.OpenDataLoaderPDF;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
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
 */
@Component
public class OpenDataLoaderPdfParser implements DocumentParser {

    private static final Logger log = LoggerFactory.getLogger(OpenDataLoaderPdfParser.class);

    @Override
    public List<String> supportedMimeTypes() {
        return List.of("application/pdf");
    }

    @Override
    public List<Document> parse(Resource resource, String mimeType) {
        Path tempPdf = null;
        Path outputDir = null;

        try {
            // 1. 将 Resource 写入临时文件
            tempPdf = Files.createTempFile("rag-pdf-", ".pdf");
            try (var in = resource.getInputStream();
                 var out = Files.newOutputStream(tempPdf)) {
                in.transferTo(out);
            }
            log.debug("PDF written to temp file: {} ({} bytes)", tempPdf, Files.size(tempPdf));

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
        try (var stream = Files.newDirectoryStream(outputDir, "*.md")) {
            for (Path mdFile : stream) {
                String content = Files.readString(mdFile);
                if (!content.isBlank()) {
                    return content;
                }
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
