package com.smart.rag.rag.parser;

import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;

import java.io.ByteArrayOutputStream;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * R2-H2: PptDocumentParser 解压炸弹防御测试。
 * <p>
 * 验证一个病理性的 pptx（幻灯片数远超 {@code MAX_SLIDES}，同时也超过 POI
 * {@code ZipSecureFile.maxFileCount}）在解析时抛 {@link DocumentParseException}，
 * 而非 OOM。
 * <p>
 * 说明：POI 默认 {@code maxFileCount=1000}，每张 slide 约占 2~3 个 zip 条目，
 * 故超过约 500 张 slide 的 pptx 会先被 {@code ZipSecureFile} 以 IOException 拦截，
 * 该异常在 {@code PptDocumentParser} 中被包成 {@link DocumentParseException}。
 * {@code MAX_SLIDES=5000} 是在 {@code maxFileCount} 被调高时的纵深防御兜底；
 * 无论哪一层先触发，对调用方都表现为 {@link DocumentParseException}（而非 OOM），
 * 这正是 PRD R2-H2 的验收语义。
 */
@DisplayName("PptDocumentParser — R2-H2 解压炸弹防御")
class PptDocumentParserTest {

    private static final String PPT_MIME =
            "application/vnd.openxmlformats-officedocument.presentationml.presentation";

    private final PptDocumentParser parser = new PptDocumentParser();

    private Resource buildPptxWithSlideCount(int slideCount) throws Exception {
        try (XMLSlideShow ppt = new XMLSlideShow();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (int i = 0; i < slideCount; i++) {
                // 空幻灯片：攻击者用最小成本撑大 slide 列表 / zip 条目数
                ppt.createSlide();
            }
            ppt.write(out);
            return new ByteArrayResource(out.toByteArray()) {
                @Override
                public String getFilename() {
                    return "bomb.pptx";
                }
            };
        }
    }

    @Test
    @DisplayName("病理性幻灯片数解析抛 DocumentParseException 而非 OOM")
    void pathological_slide_count_throws_not_oom() throws Exception {
        // 6001 张 slide：同时超过 MAX_SLIDES(5000) 与 POI maxFileCount(1000)。
        // 无论哪一层先拦截，调用方都应收到 DocumentParseException。
        Resource bomb = buildPptxWithSlideCount(6_001);

        assertThatThrownBy(() -> parser.parse(bomb, PPT_MIME))
                .isInstanceOf(DocumentParseException.class);
    }

    @Test
    @DisplayName("正常规模 pptx 不触发上限")
    void normal_slide_count_parses_without_cap() throws Exception {
        // 10 张 slide：远低于上限，应正常解析（不抛异常）
        Resource normal = buildPptxWithSlideCount(10);

        // 不抛即通过；返回值可能是空列表（空 slide 无文本）
        parser.parse(normal, PPT_MIME);
    }
}
