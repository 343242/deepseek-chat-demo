package com.smart.rag.rag.parser;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
@DisplayName("DocumentParserFactory")
class DocumentParserFactoryTest {

    private DocumentParserFactory createFactory(DocumentParser... parsers) {
        TikaDocumentParser tika = new TikaDocumentParser();
        return new DocumentParserFactory(List.of(parsers), tika);
    }

    @Nested
    @DisplayName("MIME 路由")
    class MimeRouting {

        @Test
        @DisplayName("text/plain → PlainTextDocumentParser")
        void text_plain_routes_to_plain_text_parser() {
            PlainTextDocumentParser plainText = new PlainTextDocumentParser();
            DocumentParserFactory factory = createFactory(plainText, new MarkdownDocumentParser());

            assertThat(factory.getParser("text/plain")).isSameAs(plainText);
        }

        @Test
        @DisplayName("text/markdown → MarkdownDocumentParser")
        void text_markdown_routes_to_markdown_parser() {
            MarkdownDocumentParser markdown = new MarkdownDocumentParser();
            DocumentParserFactory factory = createFactory(new PlainTextDocumentParser(), markdown);

            assertThat(factory.getParser("text/markdown")).isSameAs(markdown);
        }

        @Test
        @DisplayName("text/x-markdown → MarkdownDocumentParser")
        void text_x_markdown_routes_to_markdown_parser() {
            MarkdownDocumentParser markdown = new MarkdownDocumentParser();
            DocumentParserFactory factory = createFactory(new PlainTextDocumentParser(), markdown);

            assertThat(factory.getParser("text/x-markdown")).isSameAs(markdown);
        }

        @Test
        @DisplayName("application/pdf → OpenDataLoaderPdfParser")
        void pdf_routes_to_odl_parser() {
            OpenDataLoaderPdfParser pdfParser = new OpenDataLoaderPdfParser();
            DocumentParserFactory factory = createFactory(pdfParser);

            assertThat(factory.getParser("application/pdf")).isSameAs(pdfParser);
        }

        @Test
        @DisplayName("未知 MIME → TikaDocumentParser 兜底")
        void unknown_mime_falls_back_to_tika() {
            DocumentParserFactory factory = createFactory(new PlainTextDocumentParser());

            DocumentParser parser = factory.getParser("application/vnd.unknown");
            assertThat(parser).isInstanceOf(TikaDocumentParser.class);
        }

        @Test
        @DisplayName("null MIME → TikaDocumentParser 兜底")
        void null_mime_falls_back_to_tika() {
            DocumentParserFactory factory = createFactory(new PlainTextDocumentParser());

            DocumentParser parser = factory.getParser(null);
            assertThat(parser).isInstanceOf(TikaDocumentParser.class);
        }

        @Test
        @DisplayName("空字符串 MIME → TikaDocumentParser 兜底")
        void empty_mime_falls_back_to_tika() {
            DocumentParserFactory factory = createFactory();

            DocumentParser parser = factory.getParser("");
            assertThat(parser).isInstanceOf(TikaDocumentParser.class);
        }
    }

    @Nested
    @DisplayName("工厂构造")
    class FactoryConstruction {

        @Test
        @DisplayName("无特定解析器时所有 MIME 走 Tika")
        void no_specific_parsers_all_route_to_tika() {
            DocumentParserFactory factory = createFactory();

            assertThat(factory.getParser("text/plain")).isInstanceOf(TikaDocumentParser.class);
            assertThat(factory.getParser("application/pdf")).isInstanceOf(TikaDocumentParser.class);
        }

        @Test
        @DisplayName("TikaDocumentParser 不注册到路由表")
        void tika_not_registered_in_route_map() {
            TikaDocumentParser tika = new TikaDocumentParser();
            // Tika 返回空 supportedMimeTypes，不会覆盖其他解析器
            DocumentParserFactory factory = createFactory(new PlainTextDocumentParser());

            // text/plain 仍然路由到 PlainTextDocumentParser，不会因 Tika 被覆盖
            assertThat(factory.getParser("text/plain")).isInstanceOf(PlainTextDocumentParser.class);
        }
    }
}
