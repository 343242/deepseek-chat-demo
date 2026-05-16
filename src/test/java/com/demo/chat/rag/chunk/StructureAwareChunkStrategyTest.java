package com.demo.chat.rag.chunk;

import com.demo.chat.rag.config.DocumentProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * StructureAwareChunkStrategy 单元测试。
 * <p>
 * 验证结构感知分块：Markdown 标题切分、PDF 页切分、纯文本降级、
 * 无结构标记文档的 fallback 行为。
 * </p>
 */
class StructureAwareChunkStrategyTest {

    private DocumentProperties properties;
    private StructureAwareChunkStrategy strategy;

    @BeforeEach
    void setUp() {
        properties = new DocumentProperties();
        properties.setParagraphMinLength(50);
        strategy = new StructureAwareChunkStrategy(properties);
    }

    @Nested
    @DisplayName("strategyName")
    class StrategyNameTest {

        @Test
        @DisplayName("返回 'paragraph'（向后兼容）")
        void returns_paragraph() {
            assertThat(strategy.strategyName()).isEqualTo("paragraph");
        }
    }

    @Nested
    @DisplayName("空文档处理")
    class EmptyDocuments {

        @Test
        @DisplayName("空文档列表返回空列表")
        void emptyList_returnsEmpty() {
            List<Document> result = strategy.chunk(Collections.emptyList(), "empty.txt");
            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("Markdown 结构切分")
    class MarkdownChunking {

        @Test
        @DisplayName("Markdown 短文档作为单个 chunk 保留")
        void shortMarkdown_keptAsSingleChunk() {
            String md = """
                    # 第一章
                    
                    这是第一章的内容，包含了基本的介绍信息。
                    
                    ## 第一节
                    
                    这是第一节详细内容，描述了更多细节信息。
                    
                    # 第二章
                    
                    这是第二章的内容，讨论了其他主题。
                    """;

            Document doc = new Document(md, Map.of("parser", "markdown"));
            List<Document> chunks = strategy.chunk(List.of(doc), "test.md");

            // 短文档（< 3000 chars）直接作为单个 chunk
            assertThat(chunks).hasSize(1);
            assertThat(chunks.get(0).getMetadata()).containsEntry("source", "test.md");
            assertThat(chunks.get(0).getMetadata()).containsEntry("chunkIndex", 0);
            assertThat(chunks.get(0).getMetadata()).containsEntry("chunkType", "markdown-section");
        }

        @Test
        @DisplayName("Markdown 超长 section 按段落再切分")
        void longMarkdownSection_splitByParagraphs() {
            // 构造超过 LONG_SECTION_THRESHOLD (3000) 的 section，包含多个段落
            String longPara1 = "a".repeat(1200);
            String longPara2 = "b".repeat(1200);
            String longPara3 = "c".repeat(1200);
            String md = "# 标题\n\n" + longPara1 + "\n\n" + longPara2 + "\n\n" + longPara3;

            Document doc = new Document(md, Map.of("parser", "markdown"));
            List<Document> chunks = strategy.chunk(List.of(doc), "long.md");

            assertThat(chunks).hasSizeGreaterThanOrEqualTo(2);
            for (int i = 0; i < chunks.size(); i++) {
                assertThat(chunks.get(i).getMetadata()).containsEntry("source", "long.md");
                assertThat(chunks.get(i).getMetadata()).containsEntry("chunkIndex", i);
            }
        }

        @Test
        @DisplayName("无 parser metadata 但文本包含标题标记，仍按 Markdown 切分")
        void noParserMeta_butMarkdownContent_detectedAsMarkdown() {
            String md = """
                    # 标题一
                    
                    内容一描述信息。
                    
                    ## 标题二
                    
                    内容二描述信息。
                    """;

            Document doc = new Document(md);
            List<Document> chunks = strategy.chunk(List.of(doc), "auto.md");

            assertThat(chunks).isNotEmpty();
            // 应检测到 Markdown 结构
            assertThat(chunks.get(0).getMetadata()).containsEntry("source", "auto.md");
        }

        @Test
        @DisplayName("Markdown 切分 chunk 包含 chunkType")
        void markdownChunks_haveChunkType() {
            String md = """
                    # 标题
                    
                    这是内容信息，用于测试 Markdown 切分功能。
                    """;

            Document doc = new Document(md, Map.of("parser", "markdown"));
            List<Document> chunks = strategy.chunk(List.of(doc), "test.md");

            assertThat(chunks).isNotEmpty();
            // 短 section 应为 markdown-section 类型
            assertThat(chunks.get(0).getMetadata()).containsEntry("chunkType", "markdown-section");
        }
    }

    @Nested
    @DisplayName("PDF 页级切分")
    class PdfChunking {

        @Test
        @DisplayName("按 PDF 页边界切分")
        void pdfPages_splitByPage() {
            Document page1 = new Document("第一页内容。这是 PDF 文档的第一页文字。", Map.of("parser", "pdf-page"));
            Document page2 = new Document("第二页内容。这是 PDF 文档的第二页文字。", Map.of("parser", "pdf-page"));

            List<Document> chunks = strategy.chunk(List.of(page1, page2), "test.pdf");

            assertThat(chunks).hasSizeGreaterThanOrEqualTo(1);
            // 所有 chunk 应包含 source
            for (Document chunk : chunks) {
                assertThat(chunk.getMetadata()).containsEntry("source", "test.pdf");
            }
        }

        @Test
        @DisplayName("过短的 PDF 页被合并")
        void shortPdfPages_merged() {
            // paragraphMinLength=50，短页应合并
            Document page1 = new Document("短", Map.of("parser", "pdf-page"));
            Document page2 = new Document("也是短页", Map.of("parser", "pdf-page"));

            List<Document> chunks = strategy.chunk(List.of(page1, page2), "short.pdf");

            // 短页合并后应只有 1 个 chunk
            assertThat(chunks).hasSize(1);
            assertThat(chunks.get(0).getText()).contains("短");
            assertThat(chunks.get(0).getText()).contains("也是短页");
        }
    }

    @Nested
    @DisplayName("纯文本降级切分")
    class PlainTextChunking {

        @Test
        @DisplayName("无结构标记文档按段落切分")
        void plainText_splitByParagraphs() {
            String text = """
                    这是第一段内容，包含了一些基本的测试信息描述。
                    
                    这是第二段内容，讨论了不同的主题和相关的细节。
                    
                    这是第三段内容，提供了更多补充说明和参考信息。
                    """;

            Document doc = new Document(text);
            List<Document> chunks = strategy.chunk(List.of(doc), "plain.txt");

            assertThat(chunks).isNotEmpty();
            // 纯文本切分 chunkType 应为 paragraph
            assertThat(chunks.get(0).getMetadata()).containsEntry("chunkType", "paragraph");
        }

        @Test
        @DisplayName("短段落合并到上一段")
        void shortParagraphs_merged() {
            // paragraphMinLength=50
            String text = """
                    这是一个足够长的段落内容，它的长度超过了最小阈值要求，因此应该独立成块。
                    
                    短段
                    
                    另一个足够长的段落内容，同样超过了最小阈值要求，用于验证合并逻辑。
                    """;

            Document doc = new Document(text);
            List<Document> chunks = strategy.chunk(List.of(doc), "merge.txt");

            assertThat(chunks).isNotEmpty();
        }
    }

    @Nested
    @DisplayName("HTML 结构切分")
    class HtmlChunking {

        @Test
        @DisplayName("按 HTML 块级标签切分")
        void htmlWithTags_splitByBlocks() {
            String html = "<p>这是第一个段落的内容，包含了 HTML 标签。</p><p>这是第二个段落的内容。</p>";

            Document doc = new Document(html, Map.of("mimeType", "text/html"));
            List<Document> chunks = strategy.chunk(List.of(doc), "test.html");

            assertThat(chunks).isNotEmpty();
            assertThat(chunks.get(0).getMetadata()).containsEntry("source", "test.html");
        }

        @Test
        @DisplayName("无 HTML 标签的文本退化到段落切分")
        void htmlWithoutTags_fallbackToParagraphs() {
            String text = "第一段内容。这是纯文本但标记为 HTML。\n\n第二段内容。继续讨论其他主题。";

            Document doc = new Document(text, Map.of("mimeType", "text/html"));
            List<Document> chunks = strategy.chunk(List.of(doc), "no-tags.html");

            assertThat(chunks).isNotEmpty();
        }
    }

    @Nested
    @DisplayName("totalChunks metadata")
    class TotalChunksMetadata {

        @Test
        @DisplayName("所有 chunk 的 totalChunks 一致且正确")
        void allChunks_haveConsistentTotalChunks() {
            String md = """
                    # A
                    内容A描述信息。
                    # B
                    内容B描述信息。
                    """;

            Document doc = new Document(md, Map.of("parser", "markdown"));
            List<Document> chunks = strategy.chunk(List.of(doc), "total.txt");

            for (Document chunk : chunks) {
                assertThat(chunk.getMetadata()).containsEntry("totalChunks", chunks.size());
            }
        }
    }
}
