package com.smart.rag.rag.parser;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;

import java.io.ByteArrayOutputStream;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * R2-H2: DocxDocumentParser 段落上限测试。
 * <p>
 * 构造一个段落总数超过 {@code MAX_PARAGRAPHS} 的 docx，验证解析抛
 * {@link DocumentParseException} 而非 OOM。
 */
@DisplayName("DocxDocumentParser — R2-H2 解压炸弹防御")
class DocxDocumentParserTest {

    private static final String DOCX_MIME =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    private final DocxDocumentParser parser = new DocxDocumentParser();

    private Resource buildDocxWithParagraphCount(int paragraphCount) throws Exception {
        try (XWPFDocument doc = new XWPFDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (int i = 0; i < paragraphCount; i++) {
                XWPFParagraph p = doc.createParagraph();
                // 交替创建空段落（攻击者常用手段）与短文本段落
                if (i % 2 == 0) {
                    p.createRun().setText("p" + i);
                }
            }
            doc.write(out);
            // ByteArrayResource 可重读，getInputStream() 每次返回新流
            return new ByteArrayResource(out.toByteArray()) {
                @Override
                public String getFilename() {
                    return "bomb.docx";
                }
            };
        }
    }

    @Test
    @DisplayName("段落数超过上限抛 DocumentParseException")
    void paragraph_count_exceeds_cap_throws() throws Exception {
        // MAX_PARAGRAPHS = 50_000；构造 50_001 段触发上限
        Resource bomb = buildDocxWithParagraphCount(50_001);

        assertThatThrownBy(() -> parser.parse(bomb, DOCX_MIME))
                .isInstanceOf(DocumentParseException.class)
                .hasMessageContaining("超过上限");
    }
}
