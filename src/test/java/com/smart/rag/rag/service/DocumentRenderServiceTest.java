package com.smart.rag.rag.service;

import com.smart.rag.infrastructure.exception.ClientException;
import com.smart.rag.infrastructure.exception.errorcode.ClientErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 预览渲染测试（设计 §4.2 / §4.3）：编码检测统一 UTF-8、CommonMark 渲染、
 * Jsoup 净化（script/事件属性/危险协议剥离）、输入与生成结果双重上限。
 */
@DisplayName("DocumentRenderService — 编码/渲染/净化")
class DocumentRenderServiceTest {

    private DocumentRenderService service;

    @BeforeEach
    void setUp() {
        service = new DocumentRenderService();
    }

    @Test
    @DisplayName("GBK 输入按检测编码解码，输出 UTF-8 且不乱码")
    void gbkDecodedToUtf8() {
        // juniversalchardet 对极短样本不可靠，使用含 ASCII 混排的足够长样本
        String text = "欢迎光临文档预览功能测试。本段文字用于验证 GBK 编码检测与 UTF-8 统一输出，"
                + "包含中文标点、English words and numbers 1234567890，"
                + "以及更多中文字符以提供足够的统计样本供编码探测器判定。";
        byte[] gbk = text.getBytes(Charset.forName("GBK"));

        byte[] out = service.render(TransformKind.DETECT_CHARSET,
                new ByteArrayInputStream(gbk), 4096, "note.txt");

        assertThat(new String(out, StandardCharsets.UTF_8)).isEqualTo(text);
    }

    @Test
    @DisplayName("Markdown 渲染为 HTML，内联 script 被净化剥离")
    void markdownRenderedAndSanitized() {
        String md = "# 标题\n\n**加粗** [链接](https://example.com)\n\n<script>alert(1)</script>\n";

        byte[] out = service.render(TransformKind.RENDER_MARKDOWN,
                new ByteArrayInputStream(md.getBytes(StandardCharsets.UTF_8)), 8192, "readme.md");

        String html = new String(out, StandardCharsets.UTF_8);
        assertThat(html).contains("<h1>");
        assertThat(html).contains("<strong>");
        assertThat(html).contains("https://example.com");
        assertThat(html).doesNotContain("<script").doesNotContain("alert(1)");
    }

    @Test
    @DisplayName("HTML 净化：script/事件属性/javascript:/iframe/form/svg/图片 全部剥离，链接强制 rel")
    void htmlSanitized() {
        String html = """
                <h1 onclick="evil()">T</h1>
                <p style="color:red" class="x" id="y">正文</p>
                <a href="javascript:evil()" title="bad">bad</a>
                <a href="https://ok.com" title="ok">ok</a>
                <a href="http://plain.com">plain</a>
                <img src="https://evil.com/x.png" onerror="evil()">
                <iframe src="https://evil.com"></iframe>
                <form action="/steal"><input name="pw"></form>
                <svg onload="evil()"></svg>
                <script>alert(1)</script>
                <style>body{}</style>
                """;

        byte[] out = service.render(TransformKind.SANITIZE_HTML,
                new ByteArrayInputStream(html.getBytes(StandardCharsets.UTF_8)), 8192, "page.html");

        String clean = new String(out, StandardCharsets.UTF_8);
        assertThat(clean).contains("<h1>T</h1>");
        assertThat(clean).contains("正文");
        assertThat(clean).contains("https://ok.com");
        assertThat(clean).contains("rel=\"noopener noreferrer\"");
        // 危险元素与属性必须全部消失
        assertThat(clean).doesNotContain("onclick")
                .doesNotContain("javascript:")
                .doesNotContain("<iframe")
                .doesNotContain("<form")
                .doesNotContain("<svg")
                .doesNotContain("<img")
                .doesNotContain("<script")
                .doesNotContain("<style")
                .doesNotContain("<input")
                .doesNotContain("style=\"")
                .doesNotContain("class=\"")
                .doesNotContain("id=\"");
        // javascript: 链接被剥离后只剩文本
        assertThat(clean).doesNotContain("href=\"javascript");
    }

    @Test
    @DisplayName("输入超过上限 → DOCUMENT_PREVIEW_TOO_LARGE")
    void inputOverCap_rejected() {
        byte[] big = new byte[100];

        assertThatThrownBy(() -> service.render(TransformKind.DETECT_CHARSET,
                new ByteArrayInputStream(big), 10, "big.txt"))
                .isInstanceOfSatisfying(ClientException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(ClientErrorCode.DOCUMENT_PREVIEW_TOO_LARGE));
    }

    @Test
    @DisplayName("生成结果超过输入上限两倍 → DOCUMENT_PREVIEW_TOO_LARGE")
    void outputOverCap_rejected() {
        // 4 字节输入（"a\n\nb"）渲染为两个段落 ≈18 字节 > 2×4=8 上限
        byte[] md = "a\n\nb".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> service.render(TransformKind.RENDER_MARKDOWN,
                new ByteArrayInputStream(md), 4, "tiny.md"))
                .isInstanceOfSatisfying(ClientException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(ClientErrorCode.DOCUMENT_PREVIEW_TOO_LARGE));
    }
}
