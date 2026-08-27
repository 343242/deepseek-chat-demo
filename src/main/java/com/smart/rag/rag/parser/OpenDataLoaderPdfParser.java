package com.smart.rag.rag.parser;

import com.smart.rag.rag.config.DocumentProperties;
import com.smart.rag.rag.etl.ImageMetrics;
import com.smart.rag.rag.parser.odl.ImageManifest;
import com.smart.rag.rag.parser.odl.ImageNumberer;
import com.smart.rag.rag.parser.odl.OdlConfigs;
import com.smart.rag.rag.parser.odl.OdlResourceCleaner;
import com.smart.rag.rag.parser.odl.PlaceholderMarkdownGenerator;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.commons.io.input.BoundedInputStream;
import org.opendataloader.pdf.api.Config;
import org.opendataloader.pdf.processors.DocumentProcessor;
import org.opendataloader.pdf.processors.ExtractionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.regex.Pattern;

/**
 * OpenDataLoader PDF 解析器 — 结构化 PDF 提取（图片提取后台化改造，design §6.2）。
 * <p>
 * 前台管线（文本即最终形态，图片全部移出关键路径）：
 * <ol>
 *   <li>MinIO 流 → 有界写入 temp PDF（现状不变，R2-M1）</li>
 *   <li>{@code extractContents}（images=off + threads=N）——零图片开销</li>
 *   <li>{@link ImageNumberer} 按 (页,序) 遍历 contents → {@link ImageManifest}</li>
 *   <li>{@link PlaceholderMarkdownGenerator} 直写内存 Writer（占位符=确定性图片 URL）</li>
 *   <li>H3 完整性断言：占位符出现次数 == manifest 数，不等整体失败（fail-closed）</li>
 *   <li>清理镜像（九步）+ 删除 temp PDF</li>
 * </ol>
 * 分段计时（P1/M1）：下载/写盘、preprocessing+逐页提取、排序等由 extractContents 整体承载、
 * Markdown 生成四段进日志与指标，实测分布作为性能口径的最终裁决。
 */
@Component
public class OpenDataLoaderPdfParser implements DocumentParser {

    private static final Logger log = LoggerFactory.getLogger(OpenDataLoaderPdfParser.class);

    /**
     * H3 断言的正则（design §6.2）：占位符 URL 形态
     * {@code ](/api/documents/{docId}/images/p{page}-{seq}.{ext})}
     */
    private static final Pattern PLACEHOLDER =
            Pattern.compile("\\]\\(/api/documents/\\d+/images/p\\d+-\\d+\\.[a-z]+\\)");

    /** 中-4 降级剥离用：完整占位符形态 */
    private static final Pattern PLACEHOLDER_FULL =
            Pattern.compile("!\\[image\\]\\(/api/documents/\\d+/images/[^)]*\\)");

    private final DocumentProperties documentProperties;
    private final ImageMetrics imageMetrics;
    private final Timer parseTimer;

    public OpenDataLoaderPdfParser(DocumentProperties documentProperties, ImageMetrics imageMetrics,
                                   MeterRegistry meterRegistry) {
        this.documentProperties = documentProperties;
        this.imageMetrics = imageMetrics;
        this.parseTimer = Timer.builder("rag.document.parse.seconds")
                .description("ODL 前台解析耗时（含下载写盘/提取/Markdown 生成）")
                .tag("parser", "opendataloader")
                .register(meterRegistry);
    }

    @Override
    public List<String> supportedMimeTypes() {
        return List.of("application/pdf");
    }

    @Override
    public List<Document> parse(Resource resource, String mimeType) {
        // 非 ETL 调用方无文档身份：documentId=-1 的占位符为惰性文本（无清单落库）
        return parseWithManifest(resource, mimeType,
                new ParseContext(-1L, null, null, resource != null ? resource.getFilename() : null))
                .documents();
    }

    @Override
    public ParsedOutput parseWithManifest(Resource resource, String mimeType, ParseContext ctx) {
        long start = System.nanoTime();
        Path tempPdf = null;
        try {
            long maxBytes = DataSize.parse(documentProperties.getMaxFileSize()).toBytes();

            // 1. 写 temp（有界读取，R2-M1）
            long t0 = System.nanoTime();
            tempPdf = Files.createTempFile("rag-pdf-", ".pdf");
            long written;
            try (InputStream raw = resource.getInputStream();
                 BoundedInputStream bounded = BoundedInputStream.builder()
                         .setInputStream(raw).setMaxCount(maxBytes + 1).get();
                 var out = Files.newOutputStream(tempPdf)) {
                written = bounded.transferTo(out);
            }
            if (written > maxBytes) {
                throw new DocumentParseException(
                        name(ctx, resource), "opendataloader",
                        String.format("PDF 超过最大允许大小 %s（拒绝写入以避免磁盘填充）",
                                documentProperties.getMaxFileSize()));
            }
            long downloadMs = elapsedMs(t0);

            // 2. 提取（严重-1：extractContents 必须在 try 保护域内——它不触发
            //    closePdfResources，异常时清理镜像在 finally 兜底）
            long t1 = System.nanoTime();
            Config config = OdlConfigs.foreground(documentProperties);
            ExtractionResult result = DocumentProcessor.extractContents(tempPdf.toString(), config);
            long extractMs = elapsedMs(t1);

            // 3. 编号 + 占位符 Markdown
            long t2 = System.nanoTime();
            ImageManifest manifest = ImageNumberer.number(result.getContents());
            String markdown;
            try (StringWriter sw = new StringWriter()) {
                try (PlaceholderMarkdownGenerator gen =
                             new PlaceholderMarkdownGenerator(sw, config, manifest,
                                     ctx != null ? ctx : new ParseContext(-1L, null, null, name(ctx, resource)))) {
                    gen.writeToMarkdown(result.getContents());
                }
                markdown = sw.toString();
            }
            long mdMs = elapsedMs(t2);

            // 4. H3 完整性断言（硬门禁；中-4 降级档位：非严格模式下剥离占位符纯文本索引）
            ImageManifest effectiveManifest = manifest;
            try {
                assertPlaceholderIntegrity(markdown, manifest, name(ctx, resource));
            } catch (DocumentParseException e) {
                if (documentProperties.isOdlPlaceholderStrict()) {
                    throw e;
                }
                log.error("H3 placeholder integrity failed, degrading to plain text (strict=false): {}",
                        name(ctx, resource), e);
                imageMetrics.placeholderIntegrityDegraded();
                markdown = PLACEHOLDER_FULL.matcher(markdown).replaceAll("");
                effectiveManifest = new ImageManifest(List.of());   // 该文档 manifest 不落库不投递
            }
            manifest = effectiveManifest;

            if (markdown.isBlank()) {
                log.warn("OpenDataLoader returned empty markdown for {}, returning empty result",
                        name(ctx, resource));
                return ParsedOutput.of(List.of());
            }

            // 5. Document 组装（现状元数据逻辑不变）+ imagePlaceholderCount（中-7 对账数据基础）
            Document doc = new Document(markdown);
            doc.getMetadata().put("parser", "opendataloader");
            doc.getMetadata().put("mimeType", mimeType);
            doc.getMetadata().put("source", name(ctx, resource));
            doc.getMetadata().put("imagePlaceholderCount", manifest.size());
            long headingCount = markdown.lines().filter(line -> line.startsWith("#")).count();
            doc.getMetadata().put("headingCount", headingCount);

            log.info("OpenDataLoader parsed: {} → {} chars markdown, {} images "
                            + "[download={}ms, extract={}ms, markdown={}ms]",
                    name(ctx, resource), markdown.length(), manifest.size(),
                    downloadMs, extractMs, mdMs);
            parseTimer.record(Duration.ofNanos(System.nanoTime() - start));
            return new ParsedOutput(List.of(doc), manifest);

        } catch (DocumentParseException e) {
            throw e;
        } catch (Exception e) {
            throw new DocumentParseException(
                    name(ctx, resource), "opendataloader", "PDF parsing failed", e);
        } finally {
            // 6. 清理镜像（必须在提取线程执行）+ temp 生命周期回归 parse() 内
            OdlResourceCleaner.cleanupMirror();
            safeDelete(tempPdf);
        }
    }

    /**
     * H3 完整性断言（v1.3 修正计数口径：按出现次数统计——writeTable 整行一次输出，
     * 表格一行两个含图单元格即同行 ≥2 占位符，按行计数必偏小）。
     * L1（v1.6）：失败信息区分方向——"&gt; manifest"多为正文伪造占位符形态文本，
     * "&lt; manifest"多为 writeToMarkdown 截断/吞异常。
     */
    private void assertPlaceholderIntegrity(String markdown, ImageManifest manifest, String fileName) {
        long placeholderCount = PLACEHOLDER.matcher(markdown).results().count();
        boolean hasMissingFallback = markdown.contains("/images/missing");
        if (placeholderCount != manifest.size() || hasMissingFallback) {
            String direction = placeholderCount > manifest.size()
                    ? "regex-gt-manifest(疑正文伪造)" : "regex-lt-manifest(疑截断)";
            throw new DocumentParseException(fileName, "opendataloader",
                    "占位符数量(" + placeholderCount + "/" + manifest.size() + ")不一致[" + direction + "]"
                            + "或存在 missing 兜底，拒绝索引残缺文档");
        }
    }

    private static String name(ParseContext ctx, Resource resource) {
        if (ctx != null && ctx.fileName() != null) {
            return ctx.fileName();
        }
        return resource != null ? resource.getFilename() : null;
    }

    private static long elapsedMs(long fromNanos) {
        return (System.nanoTime() - fromNanos) / 1_000_000;
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
}
