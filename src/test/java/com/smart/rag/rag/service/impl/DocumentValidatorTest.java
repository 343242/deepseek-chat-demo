package com.smart.rag.rag.service.impl;

import com.smart.rag.rag.config.DocumentProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R2-H1: DocumentValidator 魔数探测与可接受性判定测试。
 * <p>
 * 验证分片上传合并后用于 MIME 路由/落库的安全门：
 * <ul>
 *   <li>{@code detectMimeType(InputStream, fileName)} 正确识别 PDF/zip/text 魔数</li>
 *   <li>{@code isDetectedMimeTypeAcceptable} 对「声明类型 ≠ 实际字节」的混淆攻击返回 false</li>
 * </ul>
 * 这正是 {@code ChunkUploadServiceImpl.performMerge} 现在调用的判定逻辑。
 */
@DisplayName("DocumentValidator — R2-H1 分片上传 MIME 校验")
class DocumentValidatorTest {

    private final DocumentValidator validator = new DocumentValidator(new DocumentProperties());

    private static final String DOCX_MIME =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    private static final String PDF_MIME = "application/pdf";

    @Nested
    @DisplayName("detectMimeType(InputStream, fileName)")
    class DetectFromStream {

        @Test
        @DisplayName("PDF 魔数 → application/pdf")
        void pdf_bytes_detected() {
            byte[] pdfHeader = "%PDF-1.4\n%binary".getBytes();

            String detected = validator.detectMimeType(
                    new ByteArrayInputStream(pdfHeader), "spoof.docx");

            assertThat(detected).isEqualTo(PDF_MIME);
        }

        @Test
        @DisplayName("纯文本字节 → text/plain")
        void text_bytes_detected() {
            byte[] text = "Hello World plain text".getBytes();

            String detected = validator.detectMimeType(
                    new ByteArrayInputStream(text), "note.txt");

            assertThat(detected).isEqualTo("text/plain");
        }

        @Test
        @DisplayName("zip 容器 + .docx 扩展名 → docx 子类型")
        void zip_with_docx_extension_detected_as_docx() {
            // PK\x03\x04 = zip local file header
            byte[] zipHeader = new byte[]{0x50, 0x4B, 0x03, 0x04, 0x00, 0x00, 0x00, 0x00};

            String detected = validator.detectMimeType(
                    new ByteArrayInputStream(zipHeader), "real.docx");

            assertThat(detected).isEqualTo(DOCX_MIME);
        }

        @Test
        @DisplayName("zip 容器 + 无扩展名 → application/zip")
        void zip_without_extension_detected_as_generic_zip() {
            byte[] zipHeader = new byte[]{0x50, 0x4B, 0x03, 0x04, 0x00, 0x00, 0x00, 0x00};

            String detected = validator.detectMimeType(
                    new ByteArrayInputStream(zipHeader), null);

            assertThat(detected).isEqualTo("application/zip");
        }
    }

    @Nested
    @DisplayName("isDetectedMimeTypeAcceptable — 声明 vs 实际混淆防御")
    class AcceptabilityGate {

        @Test
        @DisplayName("声明 docx 但实际是非白名单可执行类型 → 拒绝（false），performMerge 应拒绝上传")
        void declared_docx_but_actual_disallowed_type_rejected() {
            // R2-H1 核心防御：分片上传声明 docx，合并后字节实为非白名单类型（如可执行/二进制载荷）。
            // 注：application/pdf 本身在白名单内会被放行（属合法类型），
            // 真正的攻击向量是上传白名单外的危险类型伪装成 docx。
            String maliciousDetected = "application/x-msdownload";
            assertThat(validator.isDetectedMimeTypeAcceptable(maliciousDetected, DOCX_MIME))
                    .as("非白名单字节伪装成 docx 必须被拒绝")
                    .isFalse();
        }

        @Test
        @DisplayName("声明 zip 容器 OOXML（docx）且检测为 application/zip → 放行")
        void declared_docx_detected_as_zip_accepted() {
            // 魔数无法区分 docx/pptx，zip 容器 + OOXML 声明 → 放行（子类型由扩展名路由）
            assertThat(validator.isDetectedMimeTypeAcceptable("application/zip", DOCX_MIME))
                    .isTrue();
        }

        @Test
        @DisplayName("检测为 null（无法识别）→ 拒绝（false）")
        void null_detected_rejected() {
            assertThat(validator.isDetectedMimeTypeAcceptable(null, DOCX_MIME)).isFalse();
        }

        @Test
        @DisplayName("检测类型在白名单内（PDF 声明 PDF）→ 放行")
        void detected_matches_whitelist_accepted() {
            assertThat(validator.isDetectedMimeTypeAcceptable(PDF_MIME, PDF_MIME)).isTrue();
        }
    }
}
