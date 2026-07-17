package com.smart.rag.chat.service;

import com.smart.rag.mode.Reference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ChatReferenceCollector 单测（Phase 10.5）。
 * 验证 <<REF>>[n] 块格式、references 完整性、空 doc 降级、长内容截断。
 */
class ChatReferenceCollectorTest {

    private final ChatReferenceCollector collector = new ChatReferenceCollector();

    @Nested
    @DisplayName("空输入")
    class EmptyInput {

        @Test
        @DisplayName("null 列表返回 (null, null)")
        void nullList_returnsNulls() {
            ChatReferenceCollector.ChatRefResult result = collector.collect(null);
            assertThat(result.refBlock()).isNull();
            assertThat(result.references()).isNull();
        }

        @Test
        @DisplayName("空列表返回 (null, null)")
        void emptyList_returnsNulls() {
            ChatReferenceCollector.ChatRefResult result = collector.collect(List.of());
            assertThat(result.refBlock()).isNull();
            assertThat(result.references()).isNull();
        }
    }

    @Nested
    @DisplayName("refBlock + references 构造")
    class Build {

        @Test
        @DisplayName("多条 doc：refBlock 格式正确 + references 完整")
        void collect_buildsRefBlockAndReferences() {
            Document d1 = new Document("chunk-1", "内容一",
                Map.of("documentId", "doc-1", "fileName", "a.pdf", "page_number", 2));
            Document d2 = new Document("chunk-2", "内容二",
                Map.of("documentId", "doc-2", "fileName", "b.pdf"));

            ChatReferenceCollector.ChatRefResult result = collector.collect(List.of(d1, d2));

            // references 完整
            assertThat(result.references()).hasSize(2);
            Reference r1 = result.references().get(0);
            assertThat(r1.refNumber()).isEqualTo(1);
            assertThat(r1.chunkId()).isEqualTo("chunk-1");
            assertThat(r1.documentId()).isEqualTo("doc-1");
            assertThat(r1.fileName()).isEqualTo("a.pdf");
            assertThat(r1.page()).isEqualTo(2);

            Reference r2 = result.references().get(1);
            assertThat(r2.refNumber()).isEqualTo(2);
            assertThat(r2.page()).isNull();  // d2 无 page_number

            // refBlock 格式：<<REF>>[n] fileName(documentId, p.X)\n 内容 \n<<END>>
            String block = result.refBlock();
            assertThat(block).contains("<<REF>>[1] a.pdf(doc-1, p.2)");
            assertThat(block).contains("内容一");
            assertThat(block).contains("<<END>>");
            assertThat(block).contains("<<REF>>[2] b.pdf(doc-2)");  // 无 page 不输出 p.X
            assertThat(block).contains("来源#n：文件名");  // 引用约定提示
        }

        @Test
        @DisplayName("fileName 缺失降级为 documentId")
        void collect_degradesFileNameToDocumentId() {
            Document d = new Document("chunk-1", "内容",
                Map.of("documentId", "doc-1"));  // 无 fileName
            ChatReferenceCollector.ChatRefResult result = collector.collect(List.of(d));

            assertThat(result.references().get(0).fileName()).isEqualTo("doc-1");
            assertThat(result.refBlock()).contains("<<REF>>[1] doc-1(doc-1)");
        }

        @Test
        @DisplayName("长内容截断（控制注入 token）")
        void collect_truncatesLongContent() {
            String longContent = "x".repeat(1000);
            Document d = new Document("chunk-1", longContent,
                Map.of("documentId", "doc-1", "fileName", "a.pdf"));

            ChatReferenceCollector.ChatRefResult result = collector.collect(List.of(d));

            assertThat(result.refBlock()).contains("...");
            assertThat(result.refBlock()).doesNotContain("x".repeat(1000));
        }
    }
}
