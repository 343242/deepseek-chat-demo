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

@DisplayName("EncodingDetector")
class EncodingDetectorTest {

    private static final String CHINESE_TEXT = "你好世界\n\n这是第二段内容";

    private Resource toResource(String text, Charset charset) {
        return new ByteArrayResource(text.getBytes(charset));
    }

    private Resource toResource(byte[] bytes) {
        return new ByteArrayResource(bytes);
    }

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
    }

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
    }

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
            // GB18030 独有字符（㐀，U+3400）+ 常见中文，确保触发 GB18030 检测
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
    }
}
