package com.smart.rag.rag.parser.odl;

import com.smart.rag.rag.parser.DocumentParseException;
import com.smart.rag.rag.parser.ParseContext;
import org.opendataloader.pdf.api.Config;
import org.opendataloader.pdf.markdown.MarkdownGenerator;
import org.verapdf.wcag.algorithms.entities.content.ImageChunk;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;

/**
 * 占位符 Markdown 生成器（design §6.2）——子类化 ODL {@link MarkdownGenerator}，
 * 复用标题/表格/阅读顺序全部逻辑，仅覆写两条图片分发路径：
 * <ul>
 *   <li>{@code writeImage(ImageChunk)}：受 {@code isImageSupported} 门控（构造器置 true）；</li>
 *   <li>{@code writePicture(SemanticPicture)}：无条件分发（本地模式不可达，hybrid 安全位）。</li>
 * </ul>
 * 位置对齐协议（v1.5 高-2）：第 {@code ++seq} 次出现 ↔ manifest 有序清单第 seq 条。
 * 两侧（本生成器与 {@link ImageNumberer}）各自独立全局递增计数器，对同一 contents
 * 对象图的同构遍历保证对齐，错位即 H3 计数断言失败。
 */
public class PlaceholderMarkdownGenerator extends MarkdownGenerator {

    private final ImageManifest manifest;
    private final ParseContext ctx;

    /** 生成器侧独立全局计数器（按出现编号，非对象身份） */
    private int seq = 0;

    public PlaceholderMarkdownGenerator(Writer writer, Config config,
                                        ImageManifest manifest, ParseContext ctx) {
        super(writer, config);
        this.isImageSupported = true;   // protected 字段：打开 ImageChunk 分发门控
        this.manifest = manifest;
        this.ctx = ctx;
    }

    @Override
    protected void writeImage(ImageChunk image) {
        writePlaceholder();
    }

    @Override
    protected void writePicture(org.opendataloader.pdf.entities.SemanticPicture picture) {
        writePlaceholder();
    }

    private void writePlaceholder() {
        if (seq >= manifest.size()) {
            throw new DocumentParseException(ctx.fileName(), "opendataloader",
                    "占位符出现次数(" + (seq + 1) + ")超过 manifest 条目数(" + manifest.size() + ")");
        }
        ImageManifest.ImageEntry entry = manifest.entries().get(seq++);
        String url = "/api/documents/" + ctx.documentId() + "/images/" + entry.urlName();
        // L4：URL 字符集护栏——显式校验并抛异常（JVM 默认不开 -ea）。当前形态
        // [A-Za-z0-9/.\-] 对 Markdown 安全；一旦 URL 形态变化（query/fragment/中文），
        // 此处失败强制走转义路径。
        if (!url.matches("[A-Za-z0-9./\\-]+")) {
            throw new DocumentParseException(ctx.fileName(), "opendataloader",
                    "占位符 URL 字符集不变量被破坏: " + url);
        }
        try {
            markdownWriter.write("![image](" + url + ")");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
