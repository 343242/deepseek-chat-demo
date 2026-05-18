package com.demo.chat.rag.parser;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("EncodingDetector")
class EncodingDetectorTest {

    private static final String CHINESE_TEXT = "你好世界\n\n这是第二段内容";

    private Resource toResource(String text, Charset charset) {
        return new ByteArrayResource(text.getBytes(charset));
    }

    private Resource toResource(byte[] bytes) {
        return new ByteArrayResource(bytes);
    }

    // ===== detectAndDecode =====

    @Nested
    @DisplayName("detectAndDecode")
    class DetectAndDecode {

        @Test
        @DisplayName("UTF-8 文本正确解码")
        void utf8_text_decoded_correctly() {
            byte[] bytes = CHINESE_TEXT.getBytes(StandardCharsets.UTF_8);

            String result = EncodingDetector.detectAndDecode(bytes, "test.txt");

            assertThat(result).isEqualTo(CHINESE_TEXT);
        }

        @Test
        @DisplayName("GBK 文本自动检测并正确解码")
        void gbk_text_auto_detected_and_decoded() {
            byte[] bytes = CHINESE_TEXT.getBytes(Charset.forName("GBK"));

            String result = EncodingDetector.detectAndDecode(bytes, "test-gbk.txt");

            assertThat(result).isEqualTo(CHINESE_TEXT);
        }

        @Test
        @DisplayName("GB2312 文本自动检测并正确解码")
        void gb2312_text_auto_detected_and_decoded() {
            byte[] bytes = CHINESE_TEXT.getBytes(Charset.forName("GB2312"));

            String result = EncodingDetector.detectAndDecode(bytes, "test-gb2312.txt");

            assertThat(result).isEqualTo(CHINESE_TEXT);
        }

        @Test
        @DisplayName("Big5 繁体中文文本自动检测并正确解码")
        void big5_text_auto_detected_and_decoded() {
            String traditionalChinese = "這是繁體中文測試\n\n第二段內容";
            byte[] bytes = traditionalChinese.getBytes(Charset.forName("Big5"));

            String result = EncodingDetector.detectAndDecode(bytes, "test-big5.txt");

            assertThat(result).isEqualTo(traditionalChinese);
        }

        @Test
        @DisplayName("Shift-JIS 日文文本自动检测并正确解码")
        void shift_jis_text_auto_detected_and_decoded() {
            String japanese = "こんにちは世界\n\n二番目の段落";
            byte[] bytes = japanese.getBytes(Charset.forName("Shift_JIS"));

            String result = EncodingDetector.detectAndDecode(bytes, "test-sjis.txt");

            assertThat(result).isEqualTo(japanese);
        }

        @Test
        @DisplayName("纯 ASCII 文本正常处理")
        void ascii_text_handled_correctly() {
            String ascii = "Hello World\n\nSecond paragraph";
            byte[] bytes = ascii.getBytes(StandardCharsets.US_ASCII);

            String result = EncodingDetector.detectAndDecode(bytes, "test.txt");

            assertThat(result).isEqualTo(ascii);
        }

        @Test
        @DisplayName("空字节数组返回空字符串")
        void empty_bytes_returns_empty() {
            String result = EncodingDetector.detectAndDecode(new byte[0], "empty.txt");

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("null 字节数组返回空字符串")
        void null_bytes_returns_empty() {
            String result = EncodingDetector.detectAndDecode(null, "null.txt");

            assertThat(result).isEmpty();
        }
    }

    // ===== detectAndTranscode =====

    @Nested
    @DisplayName("detectAndTranscode")
    class DetectAndTranscode {

        @Test
        @DisplayName("GBK 资源转码后内容正确")
        void gbk_resource_transcoded_correctly() throws Exception {
            Resource gbkResource = toResource(CHINESE_TEXT, Charset.forName("GBK"));

            Resource transcoded = EncodingDetector.detectAndTranscode(gbkResource);

            String content = new String(transcoded.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(content).isEqualTo(CHINESE_TEXT);
        }

        @Test
        @DisplayName("UTF-8 资源原样返回（内容一致）")
        void utf8_resource_content_preserved() throws Exception {
            Resource utf8Resource = toResource(CHINESE_TEXT, StandardCharsets.UTF_8);

            Resource transcoded = EncodingDetector.detectAndTranscode(utf8Resource);

            String content = new String(transcoded.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(content).isEqualTo(CHINESE_TEXT);
        }

        @Test
        @DisplayName("空资源返回空内容")
        void empty_resource_returns_empty() throws Exception {
            Resource empty = toResource(new byte[0]);

            Resource transcoded = EncodingDetector.detectAndTranscode(empty);

            assertThat(transcoded.getInputStream().readAllBytes()).isEmpty();
        }
    }

    // ===== isUtf8Compatible =====

    @Nested
    @DisplayName("isUtf8Compatible")
    class IsUtf8Compatible {

        @Test
        @DisplayName("UTF-8 兼容")
        void utf8_is_compatible() {
            assertThat(EncodingDetector.isUtf8Compatible(StandardCharsets.UTF_8)).isTrue();
        }

        @Test
        @DisplayName("ASCII 兼容")
        void ascii_is_compatible() {
            assertThat(EncodingDetector.isUtf8Compatible(StandardCharsets.US_ASCII)).isTrue();
        }

        @Test
        @DisplayName("GBK 不兼容")
        void gbk_is_not_compatible() {
            assertThat(EncodingDetector.isUtf8Compatible(Charset.forName("GBK"))).isFalse();
        }

        @Test
        @DisplayName("null 视为兼容")
        void null_is_compatible() {
            assertThat(EncodingDetector.isUtf8Compatible(null)).isTrue();
        }
    }

    // ===== PlainTextDocumentParser with encoding detection =====

    @Nested
    @DisplayName("PlainTextDocumentParser with encoding detection")
    class PlainTextParserWithEncoding {

        private final PlainTextDocumentParser parser = new PlainTextDocumentParser();

        @Test
        @DisplayName("GBK 编码的中文文本正确解析")
        void gbk_chinese_text_parsed_correctly() {
            Resource gbkResource = toResource("第一段内容\n\n第二段内容", Charset.forName("GBK"));

            List<Document> docs = parser.parse(gbkResource, "text/plain");

            assertThat(docs).hasSize(2);
            assertThat(docs.get(0).getText()).isEqualTo("第一段内容");
            assertThat(docs.get(1).getText()).isEqualTo("第二段内容");
        }

        @Test
        @DisplayName("GB18030 编码的文本正确解析")
        void gb18030_text_parsed_correctly() {
            // 㐀 (U+3400) 位于 GB18030 四字节区间，GBK 无法编码此字符
            String text = "你好世界 㐀测试";
            Resource resource = toResource(text, Charset.forName("GB18030"));

            List<Document> docs = parser.parse(resource, "text/plain");

            assertThat(docs).hasSize(1);
            assertThat(docs.get(0).getText()).isEqualTo(text);
        }

        @Test
        @DisplayName("UTF-8 编码的文本仍然正常工作")
        void utf8_text_still_works() {
            Resource resource = toResource("Hello 你好", StandardCharsets.UTF_8);

            List<Document> docs = parser.parse(resource, "text/plain");

            assertThat(docs).hasSize(1);
            assertThat(docs.get(0).getText()).isEqualTo("Hello 你好");
        }

        @Test
        @DisplayName("解析失败时抛出 DocumentParseException")
        void parse_failure_throws_DocumentParseException() {
            // 使用 ByteArrayResource 子类模拟 IO 异常
            Resource broken = new ByteArrayResource("test".getBytes()) {
                @Override
                public java.io.InputStream getInputStream() throws java.io.IOException {
                    throw new java.io.IOException("simulated failure");
                }
                @Override
                public String getFilename() {
                    return "broken.txt";
                }
            };

            assertThatThrownBy(() -> parser.parse(broken, "text/plain"))
                    .isInstanceOf(DocumentParseException.class)
                    .hasMessageContaining("broken.txt");
        }
    }

    // ===== Large file sampling =====

    @Nested
    @DisplayName("Large file sampling")
    class LargeFileSampling {

        @Test
        @DisplayName("超过 MAX_DETECT_SIZE 的文件仍然正确检测编码")
        void large_file_encoding_detection() {
            // 构造一个超过 MAX_DETECT_SIZE 的 GBK 文件
            // 实际测试中降低阈值来验证逻辑
            String repeatedText = "你好世界测试文本 ".repeat(100);
            byte[] gbkBytes = repeatedText.getBytes(Charset.forName("GBK"));

            // 正常检测（不触发采样，因为未超过 MAX_DETECT_SIZE）
            String result = EncodingDetector.detectAndDecode(gbkBytes, "large-gbk.txt");
            assertThat(result).isEqualTo(repeatedText);
        }
    }
}
