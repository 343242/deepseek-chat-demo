package com.smart.rag.rag.parser;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("OpenDataLoaderPdfParser")
class OpenDataLoaderPdfParserTest {

    private final OpenDataLoaderPdfParser parser = new OpenDataLoaderPdfParser();

    @TempDir
    Path tempDir;

    @Nested
    @DisplayName("supportedMimeTypes")
    class SupportedMimeTypes {

        @Test
        @DisplayName("支持 application/pdf")
        void supports_pdf() {
            assertThat(parser.supportedMimeTypes()).containsExactly("application/pdf");
        }
    }

    @Nested
    @DisplayName("异常处理")
    class ExceptionHandling {

        @Test
        @DisplayName("无效 PDF 内容抛出 DocumentParseException")
        void invalid_pdf_throws_document_parse_exception() {
            // 非 PDF 二进制内容
            Resource resource = new ByteArrayResource("not a real pdf content".getBytes());

            assertThatThrownBy(() -> parser.parse(resource, "application/pdf"))
                    .isInstanceOf(DocumentParseException.class)
                    .hasMessageContaining("opendataloader");
        }

        @Test
        @DisplayName("DocumentParseException 包含文件名和解析器名")
        void exception_contains_file_and_parser_info() {
            Resource resource = new ByteArrayResource("fake".getBytes()) {
                @Override
                public String getFilename() {
                    return "test.pdf";
                }
            };

            assertThatThrownBy(() -> parser.parse(resource, "application/pdf"))
                    .isInstanceOf(DocumentParseException.class)
                    .satisfies(ex -> {
                        DocumentParseException dpe = (DocumentParseException) ex;
                        assertThat(dpe.getFileName()).isEqualTo("test.pdf");
                        assertThat(dpe.getParserName()).isEqualTo("opendataloader");
                    });
        }
    }

    @Nested
    @DisplayName("临时文件清理")
    class TempFileCleanup {

        @Test
        @DisplayName("解析失败后临时文件应被清理")
        void temp_files_cleaned_on_failure() throws IOException {
            // 记录解析前的临时文件数量
            long beforeCount = countTempFiles("rag-pdf-");
            long beforeDirCount = countTempFiles("rag-odl-");

            Resource resource = new ByteArrayResource("not a pdf".getBytes());
            try {
                parser.parse(resource, "application/pdf");
            } catch (DocumentParseException ignored) {
                // 预期异常
            }

            // 解析后不应有残留临时文件（允许少量时间差）
            long afterCount = countTempFiles("rag-pdf-");
            long afterDirCount = countTempFiles("rag-odl-");
            assertThat(afterCount).isEqualTo(beforeCount);
            assertThat(afterDirCount).isEqualTo(beforeDirCount);
        }

        private long countTempFiles(String prefix) throws IOException {
            Path tmpDir = Path.of(System.getProperty("java.io.tmpdir"));
            if (!Files.exists(tmpDir)) return 0;
            try (var stream = Files.list(tmpDir)) {
                return stream
                        .filter(p -> p.getFileName().toString().startsWith(prefix))
                        .count();
            }
        }
    }

    @Nested
    @DisplayName("DocumentParseException")
    class DocumentParseExceptionTest {

        @Test
        @DisplayName("异常消息格式正确")
        void exception_message_format() {
            DocumentParseException ex = new DocumentParseException(
                    "doc.pdf", "opendataloader", "Parse error", new RuntimeException("cause"));

            assertThat(ex.getMessage()).contains("opendataloader").contains("doc.pdf").contains("Parse error");
            assertThat(ex.getCause()).isInstanceOf(RuntimeException.class);
        }

        @Test
        @DisplayName("getter 返回正确的文件名和解析器名")
        void getters() {
            DocumentParseException ex = new DocumentParseException(
                    "test.pdf", "tika", "IO error", null);

            assertThat(ex.getFileName()).isEqualTo("test.pdf");
            assertThat(ex.getParserName()).isEqualTo("tika");
        }
    }
}
