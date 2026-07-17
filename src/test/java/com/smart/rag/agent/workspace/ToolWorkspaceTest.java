package com.smart.rag.agent.workspace;

import com.smart.rag.rag.retrieval.RetrievedDocument;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * ToolWorkspace 编号稳定性 + RetrievedDocument.from 单元测试（Phase 10.1/10.2/10.3）。
 * <p>
 * 验证 R1：编号扛住多次 tool call（不重置）、rerank replaceRetrievedDocs（不重排）、dedup（不烧号）。
 */
class ToolWorkspaceTest {

    /** 直接全参构造（测试用，refNumber=0 由 workspace 赋值） */
    private static RetrievedDocument rd(String chunkId, String documentId, String fileName) {
        return new RetrievedDocument(chunkId, documentId, fileName, null, 0,
            "content-" + chunkId, 0.9, "src", -1, Map.of());
    }

    @Nested
    @DisplayName("RetrievedDocument.from(Document)")
    class FromDocument {

        @Test
        @DisplayName("提取 chunkId/documentId/fileName/page，refNumber 默认 0")
        void from_extractsAllFields() {
            Document d = new Document("chunk-1", "hello",
                Map.of("documentId", "doc-1", "fileName", "a.pdf", "page_number", 3));
            RetrievedDocument rd = RetrievedDocument.from(d);

            assertThat(rd.chunkId()).isEqualTo("chunk-1");
            assertThat(rd.documentId()).isEqualTo("doc-1");
            assertThat(rd.fileName()).isEqualTo("a.pdf");
            assertThat(rd.page()).isEqualTo(3);
            assertThat(rd.refNumber()).isZero();
            assertThat(rd.content()).isEqualTo("hello");
        }

        @Test
        @DisplayName("fileName 缺失时降级为 documentId")
        void from_degradesFileNameToDocumentId() {
            Document d = new Document("chunk-1", "hello", Map.of("documentId", "doc-1"));
            assertThat(RetrievedDocument.from(d).fileName()).isEqualTo("doc-1");
        }

        @Test
        @DisplayName("fileName 与 documentId 均缺失时降级为「未知」")
        void from_degradesFileNameToUnknown() {
            Document d = new Document("chunk-1", "hello", Map.of());
            assertThat(RetrievedDocument.from(d).fileName()).isEqualTo("未知");
        }

        @Test
        @DisplayName("page_number 字符串形式也能解析")
        void from_parsesStringPage() {
            Document d = new Document("chunk-1", "hello",
                Map.of("documentId", "doc-1", "page_number", "7"));
            assertThat(RetrievedDocument.from(d).page()).isEqualTo(7);
        }
    }

    @Nested
    @DisplayName("编号稳定性")
    class RefNumberStability {

        @Test
        @DisplayName("addRetrievedDocs 分配递增编号 [1,2,3…]")
        void add_assignsIncrementingNumbers() {
            ToolWorkspace ws = new ToolWorkspace(1L, null);
            ws.addRetrievedDocs(List.of(rd("c1", "d1", "f1"), rd("c2", "d2", "f2"), rd("c3", "d3", "f3")));

            assertThat(ws.getRetrievedDocs())
                .extracting(RetrievedDocument::chunkId, RetrievedDocument::refNumber)
                .containsExactly(tuple("c1", 1), tuple("c2", 2), tuple("c3", 3));
        }

        @Test
        @DisplayName("多次 add（跨 tool call）编号不重置，继续递增")
        void multipleAdds_doNotResetCounter() {
            ToolWorkspace ws = new ToolWorkspace(1L, null);
            ws.addRetrievedDocs(List.of(rd("c1", "d1", "f1")));       // [1]
            ws.addRetrievedDocs(List.of(rd("c2", "d2", "f2")));       // [2]

            assertThat(ws.getRetrievedDocs())
                .extracting(RetrievedDocument::chunkId, RetrievedDocument::refNumber)
                .containsExactly(tuple("c1", 1), tuple("c2", 2));
        }

        @Test
        @DisplayName("dedup 跳过已见 chunk 不烧号（新编号连续，不跳）")
        void dedup_doesNotBurnNumbers() {
            ToolWorkspace ws = new ToolWorkspace(1L, null);
            ws.addRetrievedDocs(List.of(rd("c1", "d1", "f1"), rd("c2", "d2", "f2")));  // [1,2]
            // c1 已见被跳过，c3 新增；c3 应得 [3] 而非 [4]（c1 跳过不占号）
            ws.addRetrievedDocsDeduplicated(List.of(rd("c1", "d1", "f1"), rd("c3", "d3", "f3")));

            assertThat(ws.getRetrievedDocs())
                .extracting(RetrievedDocument::chunkId, RetrievedDocument::refNumber)
                .containsExactly(tuple("c1", 1), tuple("c2", 2), tuple("c3", 3));
        }

        @Test
        @DisplayName("replaceRetrievedDocs（rerank）保留旧编号、新 chunkId 续号")
        void replace_preservesOldNumbers() {
            ToolWorkspace ws = new ToolWorkspace(1L, null);
            ws.addRetrievedDocs(List.of(rd("c1", "d1", "f1"), rd("c2", "d2", "f2"), rd("c3", "d3", "f3")));
            // rerank 重排：c2/c1 顺序调换 + 新增 c4
            ws.replaceRetrievedDocs(List.of(rd("c2", "d2", "f2"), rd("c1", "d1", "f1"), rd("c4", "d4", "f4")));

            assertThat(ws.getRetrievedDocs())
                .extracting(RetrievedDocument::chunkId, RetrievedDocument::refNumber)
                // c2/c1 复用旧编号（2/1），c4 续号（4）；顺序按 replace 入参（rerank 重排生效）
                .containsExactly(tuple("c2", 2), tuple("c1", 1), tuple("c4", 4));
        }

        @Test
        @DisplayName("withRefNumber 不可变重建，保留其余字段")
        void withRefNumber_isImmutable() {
            RetrievedDocument rd = rd("c1", "d1", "f1");
            RetrievedDocument numbered = rd.withRefNumber(5);

            assertThat(numbered.refNumber()).isEqualTo(5);
            assertThat(numbered.chunkId()).isEqualTo("c1");
            assertThat(numbered.fileName()).isEqualTo("f1");
            assertThat(rd.refNumber()).isZero();  // 原对象不变
        }
    }
}
