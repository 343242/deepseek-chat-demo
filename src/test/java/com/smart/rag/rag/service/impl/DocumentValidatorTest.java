package com.smart.rag.rag.service.impl;

import com.smart.rag.infrastructure.exception.ClientException;
import com.smart.rag.infrastructure.exception.errorcode.ClientErrorCode;
import com.smart.rag.rag.config.DocumentProperties;
import com.smart.rag.rag.service.DocumentMimePolicy;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 服务端规范 MIME 校验测试（原文件预览与下载设计 §3）。
 * <p>
 * 验证：规范值正确（PDF/TXT/MD/HTML/三种 OOXML）、内容与扩展名不一致拒绝、
 * OOXML 必须通过包结构确认（裸 zip 不放行）、伪造客户端 Content-Type 无效、
 * 大小上限与空文件拒绝。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("DocumentValidator — 服务端规范 MIME 校验")
class DocumentValidatorTest {

    private static final String PDF_MIME = "application/pdf";
    private static final String DOCX_MIME =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    private static final String PPTX_MIME =
            "application/vnd.openxmlformats-officedocument.presentationml.presentation";
    private static final String XLSX_MIME =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private DocumentValidator validator;

    @Mock private MultipartFile file;

    @BeforeEach
    void setUp() {
        DocumentProperties props = new DocumentProperties();
        validator = new DocumentValidator(props, new DocumentMimePolicy(props));
    }

    private void stubFile(String name, byte[] content, String declaredContentType) {
        when(file.isEmpty()).thenReturn(false);
        when(file.getSize()).thenReturn((long) content.length);
        when(file.getOriginalFilename()).thenReturn(name);
        when(file.getContentType()).thenReturn(declaredContentType);
        try {
            when(file.getInputStream()).thenReturn(new ByteArrayInputStream(content));
        } catch (Exception ignored) {
            // 不可达
        }
    }

    @Nested
    @DisplayName("规范 MIME 产出")
    class CanonicalValues {

        @Test
        @DisplayName("PDF 内容 + .pdf → application/pdf")
        void pdf() {
            stubFile("report.pdf", "%PDF-1.4\n%binary-junk".getBytes(StandardCharsets.ISO_8859_1), "text/html");
            assertThat(validator.validate(file).canonicalMimeType()).isEqualTo(PDF_MIME);
        }

        @Test
        @DisplayName("文本内容 + .txt → text/plain（客户端声明 text/html 不影响结果）")
        void txt() {
            stubFile("note.txt", "plain text content".getBytes(), "text/html");
            assertThat(validator.validate(file).canonicalMimeType()).isEqualTo("text/plain");
        }

        @Test
        @DisplayName("文本内容 + .md / .markdown → text/markdown")
        void markdown() {
            stubFile("readme.md", "# Title\n\nbody".getBytes(), "text/plain");
            assertThat(validator.validate(file).canonicalMimeType()).isEqualTo("text/markdown");

            stubFile("readme.markdown", "# Title".getBytes(), "text/plain");
            assertThat(validator.validate(file).canonicalMimeType()).isEqualTo("text/markdown");
        }

        @Test
        @DisplayName("HTML 内容 + .html → text/html")
        void html() {
            stubFile("page.html", "<html><body><p>hi</p></body></html>".getBytes(), "text/plain");
            assertThat(validator.validate(file).canonicalMimeType()).isEqualTo("text/html");
        }

        @Test
        @DisplayName("真实 DOCX 包 → wordprocessingml 规范 MIME")
        void docx() throws Exception {
            stubFile("doc.docx", realDocx(), DOCX_MIME);
            assertThat(validator.validate(file).canonicalMimeType()).isEqualTo(DOCX_MIME);
        }

        @Test
        @DisplayName("真实 PPTX 包 → presentationml 规范 MIME")
        void pptx() throws Exception {
            try (XMLSlideShow ppt = new XMLSlideShow();
                 ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
                ppt.createSlide();
                ppt.write(bos);
                stubFile("deck.pptx", bos.toByteArray(), PPTX_MIME);
            }
            assertThat(validator.validate(file).canonicalMimeType()).isEqualTo(PPTX_MIME);
        }

        @Test
        @DisplayName("真实 XLSX 包 → spreadsheetml 规范 MIME")
        void xlsx() throws Exception {
            try (XSSFWorkbook wb = new XSSFWorkbook();
                 ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
                wb.createSheet("s").createRow(0).createCell(0).setCellValue("v");
                wb.write(bos);
                stubFile("sheet.xlsx", bos.toByteArray(), XLSX_MIME);
            }
            assertThat(validator.validate(file).canonicalMimeType()).isEqualTo(XLSX_MIME);
        }

        @Test
        @DisplayName("返回值携带文件名与大小")
        void carriesMetadata() {
            byte[] bytes = "abc".getBytes();
            stubFile("note.txt", bytes, "text/plain");
            ValidatedDocumentFile result = validator.validate(file);
            assertThat(result.fileName()).isEqualTo("note.txt");
            assertThat(result.fileSize()).isEqualTo(bytes.length);
        }
    }

    @Nested
    @DisplayName("内容与扩展名一致性（浏览器声明值不参与决策）")
    class ConsistencyGate {

        @Test
        @DisplayName("PDF 字节伪装 .docx → 拒绝")
        void pdfBytesAsDocx_rejected() {
            stubFile("spoof.docx", "%PDF-1.4 fake pdf".getBytes(StandardCharsets.ISO_8859_1), DOCX_MIME);
            assertThatThrownBy(() -> validator.validate(file))
                    .isInstanceOfSatisfying(ClientException.class, e ->
                            assertThat(e.getErrorCode()).isEqualTo(ClientErrorCode.UPLOAD_MIME_UNSUPPORTED));
        }

        @Test
        @DisplayName("文本字节伪装 .pdf → 拒绝")
        void textBytesAsPdf_rejected() {
            stubFile("spoof.pdf", "just plain text".getBytes(), PDF_MIME);
            assertThatThrownBy(() -> validator.validate(file))
                    .isInstanceOfSatisfying(ClientException.class, e ->
                            assertThat(e.getErrorCode()).isEqualTo(ClientErrorCode.UPLOAD_MIME_UNSUPPORTED));
        }

        @Test
        @DisplayName("裸 zip 容器伪装 .docx（无 OOXML 包结构）→ 拒绝")
        void bareZipAsDocx_rejected() {
            byte[] zipHeader = {0x50, 0x4B, 0x03, 0x04, 0x00, 0x00, 0x00, 0x00, 'a', 'b'};
            stubFile("fake.docx", zipHeader, DOCX_MIME);
            assertThatThrownBy(() -> validator.validate(file))
                    .isInstanceOfSatisfying(ClientException.class, e ->
                            assertThat(e.getErrorCode()).isEqualTo(ClientErrorCode.UPLOAD_MIME_UNSUPPORTED));
        }

        @Test
        @DisplayName("损坏（截断）的 docx → 拒绝")
        void truncatedDocx_rejected() throws Exception {
            byte[] docx = realDocx();
            byte[] truncated = new byte[docx.length / 2];
            System.arraycopy(docx, 0, truncated, 0, truncated.length);
            stubFile("broken.docx", truncated, DOCX_MIME);
            assertThatThrownBy(() -> validator.validate(file))
                    .isInstanceOfSatisfying(ClientException.class, e ->
                            assertThat(e.getErrorCode()).isEqualTo(ClientErrorCode.UPLOAD_MIME_UNSUPPORTED));
        }

        @Test
        @DisplayName("无法识别的二进制 + .pdf → 拒绝（fail-closed）")
        void unrecognizedBinary_rejected() {
            byte[] junk = new byte[64];
            new Random(42).nextBytes(junk);
            stubFile("junk.pdf", junk, PDF_MIME);
            assertThatThrownBy(() -> validator.validate(file))
                    .isInstanceOfSatisfying(ClientException.class, e ->
                            assertThat(e.getErrorCode()).isEqualTo(ClientErrorCode.UPLOAD_MIME_UNSUPPORTED));
        }

        @Test
        @DisplayName("docx 扩展名 + pptx 字节（包结构不符）→ 拒绝")
        void crossSubtype_rejected() throws Exception {
            try (XMLSlideShow ppt = new XMLSlideShow();
                 ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
                ppt.createSlide();
                ppt.write(bos);
                stubFile("wrong-name.docx", bos.toByteArray(), DOCX_MIME);
            }
            assertThatThrownBy(() -> validator.validate(file))
                    .isInstanceOfSatisfying(ClientException.class, e ->
                            assertThat(e.getErrorCode()).isEqualTo(ClientErrorCode.UPLOAD_MIME_UNSUPPORTED));
        }
    }

    @Nested
    @DisplayName("空文件与大小上限")
    class SizeGate {

        @Test
        @DisplayName("空文件 → UPLOAD_FILE_EMPTY")
        void emptyFile_rejected() {
            when(file.isEmpty()).thenReturn(true);
            assertThatThrownBy(() -> validator.validate(file))
                    .isInstanceOfSatisfying(ClientException.class, e ->
                            assertThat(e.getErrorCode()).isEqualTo(ClientErrorCode.UPLOAD_FILE_EMPTY));
        }

        @Test
        @DisplayName("流式入口超出大小上限 → UPLOAD_FILE_TOO_LARGE（有界读取，不落盘超限内容）")
        void oversizeStream_rejected() {
            DocumentProperties props = new DocumentProperties();
            props.setMaxFileSize("1KB");
            DocumentValidator small = new DocumentValidator(props, new DocumentMimePolicy(props));
            byte[] big = new byte[2048];

            assertThatThrownBy(() -> small.validate(new ByteArrayInputStream(big), "big.txt", big.length))
                    .isInstanceOfSatisfying(ClientException.class, e ->
                            assertThat(e.getErrorCode()).isEqualTo(ClientErrorCode.UPLOAD_FILE_TOO_LARGE));
        }
    }

    @Test
    @DisplayName("分片对象流式入口与非分片入口产出同一规范值")
    void streamEntryMatchesMultipartEntry() throws Exception {
        byte[] docx = realDocx();
        ValidatedDocumentFile fromStream =
                validator.validate(new ByteArrayInputStream(docx), "s.docx", docx.length);
        assertThat(fromStream.canonicalMimeType()).isEqualTo(DOCX_MIME);
    }

    private static byte[] realDocx() throws Exception {
        try (XWPFDocument doc = new XWPFDocument();
             ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            doc.createParagraph().createRun().setText("canonical mime test");
            doc.write(bos);
            return bos.toByteArray();
        }
    }
}
