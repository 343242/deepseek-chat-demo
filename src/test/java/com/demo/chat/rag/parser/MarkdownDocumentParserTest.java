package com.demo.chat.rag.parser;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("MarkdownDocumentParser")
class MarkdownDocumentParserTest {

    private final MarkdownDocumentParser parser = new MarkdownDocumentParser();

    private Resource toResource(String markdown) {
        return new ByteArrayResource(markdown.getBytes());
    }

    @Nested
    @DisplayName("supportedMimeTypes")
    class SupportedMimeTypes {

        @Test
        @DisplayName("支持 text/markdown 和 text/x-markdown")
        void supports_markdown_types() {
            assertThat(parser.supportedMimeTypes())
                    .containsExactly("text/markdown", "text/x-markdown");
        }
    }

    @Nested
    @DisplayName("正常解析")
    class NormalParsing {

        @Test
        @DisplayName("Markdown 内容解析为多个 Document")
        void markdown_parsed_to_documents() {
            Resource resource = toResource("# Title\n\nParagraph text\n\n## Section\n\nMore text");

            List<Document> docs = parser.parse(resource, "text/markdown");

            assertThat(docs).isNotEmpty();
        }

        @Test
        @DisplayName("每个 Document 附加 parser 和 mimeType 元数据")
        void metadata_attached() {
            Resource resource = toResource("# Hello\n\nWorld");

            List<Document> docs = parser.parse(resource, "text/markdown");

            assertThat(docs).isNotEmpty();
            for (Document doc : docs) {
                assertThat(doc.getMetadata()).containsEntry("parser", "markdown");
                assertThat(doc.getMetadata()).containsEntry("mimeType", "text/markdown");
            }
        }

        @Test
        @DisplayName("水平分割线创建独立 Document")
        void horizontal_rule_splits_document() {
            Resource resource = toResource("Part one\n\n---\n\nPart two");

            List<Document> docs = parser.parse(resource, "text/markdown");

            assertThat(docs.size()).isGreaterThanOrEqualTo(2);
        }
    }

    @Nested
    @DisplayName("边界情况")
    class EdgeCases {

        @Test
        @DisplayName("空 Markdown 返回空列表")
        void empty_markdown_returns_empty() {
            Resource resource = toResource("");

            List<Document> docs = parser.parse(resource, "text/markdown");

            assertThat(docs).isEmpty();
        }

        @Test
        @DisplayName("仅有标题的 Markdown 不报错")
        void only_headings_no_error() {
            Resource resource = toResource("# Title");

            // 仅标题无正文，MarkdownDocumentReader 可能返回空列表，不应抛异常
            List<Document> docs = parser.parse(resource, "text/markdown");
            assertThat(docs).isNotNull();
        }
    }
}
