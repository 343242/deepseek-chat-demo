package com.smart.rag.rag.parser;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("PlainTextDocumentParser")
class PlainTextDocumentParserTest {

    private final PlainTextDocumentParser parser = new PlainTextDocumentParser();

    private Resource toResource(String text) {
        return new ByteArrayResource(text.getBytes());
    }

    @Nested
    @DisplayName("supportedMimeTypes")
    class SupportedMimeTypes {

        @Test
        @DisplayName("支持 text/plain")
        void supports_text_plain() {
            assertThat(parser.supportedMimeTypes()).containsExactly("text/plain");
        }
    }

    @Nested
    @DisplayName("正常解析")
    class NormalParsing {

        @Test
        @DisplayName("单段落文本解析为一个 Document")
        void single_paragraph() {
            Resource resource = toResource("Hello World");

            List<Document> docs = parser.parse(resource, "text/plain");

            assertThat(docs).hasSize(1);
            assertThat(docs.get(0).getText()).isEqualTo("Hello World");
            assertThat(docs.get(0).getMetadata()).containsEntry("parser", "plain-text");
            assertThat(docs.get(0).getMetadata()).containsEntry("mimeType", "text/plain");
            assertThat(docs.get(0).getMetadata()).containsEntry("paragraphIndex", 0);
        }

        @Test
        @DisplayName("多个段落按空行分隔")
        void multiple_paragraphs() {
            Resource resource = toResource("First paragraph\n\nSecond paragraph\n\nThird paragraph");

            List<Document> docs = parser.parse(resource, "text/plain");

            assertThat(docs).hasSize(3);
            assertThat(docs.get(0).getText()).isEqualTo("First paragraph");
            assertThat(docs.get(1).getText()).isEqualTo("Second paragraph");
            assertThat(docs.get(2).getText()).isEqualTo("Third paragraph");
        }

        @Test
        @DisplayName("段落索引从 0 递增")
        void paragraph_indices_increment() {
            Resource resource = toResource("Para A\n\nPara B");

            List<Document> docs = parser.parse(resource, "text/plain");

            assertThat(docs.get(0).getMetadata()).containsEntry("paragraphIndex", 0);
            assertThat(docs.get(1).getMetadata()).containsEntry("paragraphIndex", 1);
        }

        @Test
        @DisplayName("段落 trim 后为空则跳过")
        void empty_paragraphs_skipped() {
            Resource resource = toResource("Hello\n\n   \n\nWorld");

            List<Document> docs = parser.parse(resource, "text/plain");

            assertThat(docs).hasSize(2);
            assertThat(docs.get(0).getText()).isEqualTo("Hello");
            assertThat(docs.get(1).getText()).isEqualTo("World");
        }
    }

    @Nested
    @DisplayName("边界情况")
    class EdgeCases {

        @Test
        @DisplayName("空文件返回空列表")
        void empty_file_returns_empty_list() {
            Resource resource = toResource("");

            List<Document> docs = parser.parse(resource, "text/plain");

            assertThat(docs).isEmpty();
        }

        @Test
        @DisplayName("纯空白内容返回空列表")
        void blank_content_returns_empty_list() {
            Resource resource = toResource("   \n  \n  ");

            List<Document> docs = parser.parse(resource, "text/plain");

            assertThat(docs).isEmpty();
        }

        @Test
        @DisplayName("Windows 换行符 CRLF 也能正常分段")
        void crlf_line_breaks() {
            Resource resource = toResource("First\r\n\r\nSecond");

            List<Document> docs = parser.parse(resource, "text/plain");

            assertThat(docs).hasSize(2);
            assertThat(docs.get(0).getText()).isEqualTo("First");
            assertThat(docs.get(1).getText()).isEqualTo("Second");
        }

        @Test
        @DisplayName("连续多个空行仍只产生两段")
        void multiple_blank_lines() {
            Resource resource = toResource("A\n\n\n\n\nB");

            List<Document> docs = parser.parse(resource, "text/plain");

            assertThat(docs).hasSize(2);
        }

        @Test
        @DisplayName("无空行的整块文本作为单段落")
        void no_blank_lines_single_paragraph() {
            Resource resource = toResource("Line 1\nLine 2\nLine 3");

            List<Document> docs = parser.parse(resource, "text/plain");

            assertThat(docs).hasSize(1);
            assertThat(docs.get(0).getText()).isEqualTo("Line 1\nLine 2\nLine 3");
        }

        @Test
        @DisplayName("UTF-8 中文内容正常解析")
        void utf8_chinese_content() {
            Resource resource = toResource("你好世界\n\n这是第二段");

            List<Document> docs = parser.parse(resource, "text/plain");

            assertThat(docs).hasSize(2);
            assertThat(docs.get(0).getText()).isEqualTo("你好世界");
            assertThat(docs.get(1).getText()).isEqualTo("这是第二段");
        }
    }
}
