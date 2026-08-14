package com.smart.rag.rag.service;

import com.smart.rag.infrastructure.exception.ClientException;
import com.smart.rag.infrastructure.exception.RemoteException;
import com.smart.rag.infrastructure.exception.errorcode.ClientErrorCode;
import com.smart.rag.infrastructure.exception.errorcode.RemoteErrorCode;
import com.smart.rag.rag.parser.EncodingDetector;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.safety.Safelist;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * 预览渲染服务（设计 §4.2 / §4.3）。
 * <p>
 * 输入为对象完整内容流，读取以 {@code maxInputBytes} 硬性截断（stat 与实际内容
 * 不一致时兜底）；按 {@link TransformKind} 做编码检测解码、CommonMark 渲染与
 * Jsoup 净化，统一输出 UTF-8 字节。生成结果限制为输入上限的两倍，超出按预览过大拒绝。
 * <p>
 * 净化与浏览器隔离（CSP + iframe sandbox）是两道独立边界：本类净化的对象是
 * 预览输出，MinIO 始终保留原始字节（download 忠实返回原文件）。
 */
@Service
public class DocumentRenderService {

    private static final Logger log = LoggerFactory.getLogger(DocumentRenderService.class);

    /** 预览专用 Safelist：仅无主动行为的排版标签；协议白名单 http/https；强制 rel */
    private static final Safelist PREVIEW_SAFELIST = Safelist.none()
            .addTags("h1", "h2", "h3", "h4", "h5", "h6",
                    "p", "br", "blockquote", "pre", "code",
                    "ul", "ol", "li", "table", "thead", "tbody", "tr", "th", "td",
                    "strong", "b", "em", "i", "del", "s", "a")
            .addAttributes("a", "href", "title")
            .addProtocols("a", "href", "http", "https")
            .addEnforcedAttribute("a", "rel", "noopener noreferrer");

    private static final Parser MARKDOWN_PARSER = Parser.builder().build();
    private static final HtmlRenderer MARKDOWN_RENDERER = HtmlRenderer.builder().build();

    /** 生成结果相对输入上限的最大膨胀倍数 */
    private static final int MAX_OUTPUT_MULTIPLIER = 2;

    /**
     * 有界读取内容流并渲染为 UTF-8 预览输出。
     *
     * @param kind         输出变换类型
     * @param content      对象完整内容流（方法消费但不关闭，由调用方负责）
     * @param maxInputBytes 输入大小上限（来自预览策略）
     * @param fileName     文件名（仅用于日志与编码检测提示）
     * @return UTF-8 编码的渲染结果
     * @throws ClientException DOCUMENT_PREVIEW_TOO_LARGE（输入或生成结果超限）
     */
    public byte[] render(TransformKind kind, InputStream content, long maxInputBytes, String fileName) {
        byte[] input = readBounded(content, maxInputBytes);
        String text = EncodingDetector.detectAndDecode(input, fileName);
        byte[] output = switch (kind) {
            case DETECT_CHARSET -> text.getBytes(StandardCharsets.UTF_8);
            case RENDER_MARKDOWN -> sanitize(MARKDOWN_RENDERER.render(MARKDOWN_PARSER.parse(text)))
                    .getBytes(StandardCharsets.UTF_8);
            case SANITIZE_HTML -> sanitize(text).getBytes(StandardCharsets.UTF_8);
        };
        if (output.length > maxInputBytes * MAX_OUTPUT_MULTIPLIER) {
            log.info("Preview render output exceeded cap: file={}, in={}, out={}, cap={}",
                    fileName, input.length, output.length, maxInputBytes * MAX_OUTPUT_MULTIPLIER);
            throw new ClientException(ClientErrorCode.DOCUMENT_PREVIEW_TOO_LARGE);
        }
        return output;
    }

    /** Jsoup 净化：剥离 script/style/base/meta/form/iframe/object/embed/svg/math/图片、
     * 事件属性、style/class/id 与 javascript:/data: URL（不在 Safelist 内的一律丢弃） */
    private String sanitize(String html) {
        return Jsoup.clean(html, "", PREVIEW_SAFELIST,
                new Document.OutputSettings().prettyPrint(false));
    }

    /** 有界读取：上限 +1 字节以区分恰好等于上限的情况；底层流故障按存储不可用翻译 */
    private byte[] readBounded(InputStream content, long maxInputBytes) {
        try {
            byte[] bytes = content.readNBytes((int) Math.min(maxInputBytes + 1, Integer.MAX_VALUE));
            if (bytes.length > maxInputBytes) {
                throw new ClientException(ClientErrorCode.DOCUMENT_PREVIEW_TOO_LARGE);
            }
            return bytes;
        } catch (IOException e) {
            throw new RemoteException(RemoteErrorCode.FILE_STORAGE_UNAVAILABLE, "文件存储暂不可用", e);
        }
    }
}
