package com.smart.rag.rag.service;

import com.smart.rag.infrastructure.exception.ClientException;
import com.smart.rag.infrastructure.exception.errorcode.ClientErrorCode;
import com.smart.rag.rag.config.DocumentProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 文件应用服务编排测试（设计 §7 / §8）：授权 → 预览策略 → stat →
 * HEAD / GET 透传（含 Range 矩阵）/ GET 渲染分流。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("DocumentFileService — preview/download 编排")
class DocumentFileServiceTest {

    private static final String PDF = "application/pdf";
    private static final String TXT = "text/plain";
    private static final String DOCX =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    @Mock private DocumentApplicationService applicationService;
    @Mock private FileStorageService fileStorageService;

    private DocumentFileService service;
    private FakeHandle handle;

    /** 记录 content() 打开次数的内存句柄（验证 HEAD / 超限不打开内容流） */
    static final class FakeHandle implements StoredObjectHandle {
        final byte[] bytes;
        int contentOpens;

        FakeHandle(byte[] bytes) {
            this.bytes = bytes;
        }

        @Override
        public long totalSize() {
            return bytes.length;
        }

        @Override
        public StoredObjectContent content(ObjectReadRange range) {
            contentOpens++;
            if (range instanceof ObjectReadRange.Bytes b) {
                byte[] slice = new byte[(int) b.length()];
                System.arraycopy(bytes, (int) b.offset(), slice, 0, (int) b.length());
                return new StoredObjectContent(new ByteArrayResource(slice), b.offset(), b.length());
            }
            return new StoredObjectContent(new ByteArrayResource(bytes), 0, bytes.length);
        }
    }

    @BeforeEach
    void setUp() {
        handle = new FakeHandle(new byte[1000]);
        service = new DocumentFileService(applicationService, fileStorageService,
                new DocumentPreviewPolicy(new DocumentProperties()), new DocumentRenderService());
        when(fileStorageService.open(anyString(), anyString())).thenReturn(handle);
    }

    private void stubAuth(String mime, long declaredSize) {
        when(applicationService.authorizeFileRead(1L)).thenReturn(new AuthorizedDocumentFile(
                1L, "a.pdf", declaredSize, mime, "bucket", "key"));
    }

    // ==================== HEAD ====================

    @Test
    @DisplayName("透传 HEAD：stat 长度 + BYTES，不打开内容流")
    void head_passthrough() {
        stubAuth(PDF, 1000);
        DocumentFileResult.Metadata meta = (DocumentFileResult.Metadata)
                service.head(1L, DocumentFileService.FilePurpose.DOWNLOAD);
        assertThat(meta.status()).isEqualTo(HttpStatus.OK);
        assertThat(meta.contentLength()).isEqualTo(1000L);
        assertThat(meta.rangeCapability()).isEqualTo(RangeCapability.BYTES);
        assertThat(meta.disposition()).isEqualTo(Disposition.ATTACHMENT);
        assertThat(handle.contentOpens).isZero();
    }

    @Test
    @DisplayName("渲染 HEAD：无 Content-Length + NONE，不渲染内容")
    void head_render() {
        stubAuth(TXT, 10);
        DocumentFileResult.Metadata meta = (DocumentFileResult.Metadata)
                service.head(1L, DocumentFileService.FilePurpose.PREVIEW);
        assertThat(meta.contentLength()).isNull();
        assertThat(meta.rangeCapability()).isEqualTo(RangeCapability.NONE);
        assertThat(meta.responseContentType()).isEqualTo("text/plain; charset=UTF-8");
        assertThat(meta.disposition()).isEqualTo(Disposition.INLINE);
        assertThat(handle.contentOpens).isZero();
    }

    // ==================== 透传 GET 与 Range 矩阵 ====================

    @Test
    @DisplayName("无 Range → 200 完整对象")
    void get_full() {
        stubAuth(PDF, 1000);
        DocumentFileResult.Body body = (DocumentFileResult.Body)
                service.get(1L, DocumentFileService.FilePurpose.DOWNLOAD, null, null);
        assertThat(body.status()).isEqualTo(HttpStatus.OK);
        assertThat(body.contentLength()).isEqualTo(1000L);
        assertThat(body.offset()).isZero();
        assertThat(body.disposition()).isEqualTo(Disposition.ATTACHMENT);
        assertThat(body.responseContentType()).isEqualTo(PDF);
    }

    @Test
    @DisplayName("单段合法 Range → 206 精确区间，无双重 offset")
    void get_singleRange_206() throws IOException {
        stubAuth(PDF, 1000);
        DocumentFileResult.Body body = (DocumentFileResult.Body)
                service.get(1L, DocumentFileService.FilePurpose.DOWNLOAD, "bytes=100-199", null);
        assertThat(body.status()).isEqualTo(HttpStatus.PARTIAL_CONTENT);
        assertThat(body.offset()).isEqualTo(100L);
        assertThat(body.contentLength()).isEqualTo(100L);
        assertThat(body.totalSize()).isEqualTo(1000L);
        // Resource 内容即为截取后的区间（读取时不得再次跳过 offset）
        byte[] read = body.resource().getInputStream().readAllBytes();
        assertThat(read).hasSize(100);
    }

    @Test
    @DisplayName("后缀 Range → 206 按总大小换算")
    void get_suffixRange_206() {
        stubAuth(PDF, 1000);
        DocumentFileResult.Body body = (DocumentFileResult.Body)
                service.get(1L, DocumentFileService.FilePurpose.DOWNLOAD, "bytes=-100", null);
        assertThat(body.status()).isEqualTo(HttpStatus.PARTIAL_CONTENT);
        assertThat(body.offset()).isEqualTo(900L);
        assertThat(body.contentLength()).isEqualTo(100L);
    }

    @Test
    @DisplayName("起点越界 → 416（携带 totalSize）")
    void get_outOfBounds_416() {
        stubAuth(PDF, 1000);
        DocumentFileResult.RangeNotSatisfiable result =
                (DocumentFileResult.RangeNotSatisfiable) service.get(
                        1L, DocumentFileService.FilePurpose.DOWNLOAD, "bytes=2000-", null);
        assertThat(result.totalSize()).isEqualTo(1000L);
    }

    @Test
    @DisplayName("语法错误 Range → 忽略，200 完整对象（RFC 9110）")
    void get_syntaxError_ignored() {
        stubAuth(PDF, 1000);
        DocumentFileResult.Body body = (DocumentFileResult.Body)
                service.get(1L, DocumentFileService.FilePurpose.DOWNLOAD, "bytes=abc", null);
        assertThat(body.status()).isEqualTo(HttpStatus.OK);
        assertThat(body.contentLength()).isEqualTo(1000L);
    }

    @Test
    @DisplayName("合法多段 Range → 忽略，200 完整对象（不实现 multipart，不误报 416）")
    void get_multiRange_ignored() {
        stubAuth(PDF, 1000);
        DocumentFileResult.Body body = (DocumentFileResult.Body)
                service.get(1L, DocumentFileService.FilePurpose.DOWNLOAD, "bytes=0-99,200-299", null);
        assertThat(body.status()).isEqualTo(HttpStatus.OK);
        assertThat(body.contentLength()).isEqualTo(1000L);
    }

    @Test
    @DisplayName("Range + If-Range（无强校验器）→ 忽略 Range，200 完整对象")
    void get_ifRange_ignored() {
        stubAuth(PDF, 1000);
        DocumentFileResult.Body body = (DocumentFileResult.Body)
                service.get(1L, DocumentFileService.FilePurpose.DOWNLOAD, "bytes=0-99", "\"etag-1\"");
        assertThat(body.status()).isEqualTo(HttpStatus.OK);
        assertThat(body.contentLength()).isEqualTo(1000L);
    }

    // ==================== 渲染路径 ====================

    @Test
    @DisplayName("TXT 预览：GBK 输入 → UTF-8 text/plain 输出，Content-Length 为生成长度，忽略 Range")
    void get_renderTxt() {
        String text = "欢迎光临文档预览功能测试。This paragraph mixes English words 1234567890 "
                + "与足量中文字符，确保编码探测器能够可靠识别 GBK 输入并统一转码输出。";
        when(applicationService.authorizeFileRead(1L)).thenReturn(new AuthorizedDocumentFile(
                1L, "note.txt", 100, TXT, "bucket", "key"));
        handle = new FakeHandle(text.getBytes(Charset.forName("GBK")));
        when(fileStorageService.open(anyString(), anyString())).thenReturn(handle);

        DocumentFileResult.Body body = (DocumentFileResult.Body) service.get(
                1L, DocumentFileService.FilePurpose.PREVIEW, "bytes=0-9", null);
        assertThat(body.status()).isEqualTo(HttpStatus.OK);
        assertThat(body.responseContentType()).isEqualTo("text/plain; charset=UTF-8");
        assertThat(body.rangeCapability()).isEqualTo(RangeCapability.NONE);
        assertThat(new String(readAll(body.resource()), StandardCharsets.UTF_8)).isEqualTo(text);
        assertThat(body.contentLength())
                .isEqualTo(text.getBytes(StandardCharsets.UTF_8).length);
    }

    @Test
    @DisplayName("文本 stat 大小超预览上限 → DOCUMENT_PREVIEW_TOO_LARGE，且不打开内容流")
    void get_textTooLarge() {
        stubAuth(TXT, 6L * 1024 * 1024);
        assertThatThrownBy(() -> service.get(1L, DocumentFileService.FilePurpose.PREVIEW, null, null))
                .isInstanceOfSatisfying(ClientException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(ClientErrorCode.DOCUMENT_PREVIEW_TOO_LARGE));
        assertThat(handle.contentOpens).isZero();
    }

    @Test
    @DisplayName("数据库大小与 stat 不一致 → 取较大值（保守拒绝）")
    void get_sizeMismatch_usesMax() {
        // 声明 1KB、真实 6MB → 保守判定超限
        stubAuth(TXT, 1024);
        handle = new FakeHandle(new byte[6 * 1024 * 1024]);
        when(fileStorageService.open(anyString(), anyString())).thenReturn(handle);

        assertThatThrownBy(() -> service.get(1L, DocumentFileService.FilePurpose.PREVIEW, null, null))
                .isInstanceOfSatisfying(ClientException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(ClientErrorCode.DOCUMENT_PREVIEW_TOO_LARGE));
        assertThat(handle.contentOpens).isZero();
    }

    // ==================== 预览拒绝 ====================

    @Test
    @DisplayName("OOXML 预览 → DOCUMENT_PREVIEW_UNSUPPORTED，且不触碰对象存储（stat 之前拒绝）")
    void preview_ooxml_rejectedBeforeOpen() {
        stubAuth(DOCX, 1000);
        assertThatThrownBy(() -> service.get(1L, DocumentFileService.FilePurpose.PREVIEW, null, null))
                .isInstanceOfSatisfying(ClientException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(ClientErrorCode.DOCUMENT_PREVIEW_UNSUPPORTED));
        verifyNoInteractions(fileStorageService);
    }

    @Test
    @DisplayName("download 不受预览策略限制：OOXML 下载正常透传")
    void download_ooxml_ok() {
        stubAuth(DOCX, 1000);
        DocumentFileResult.Body body = (DocumentFileResult.Body)
                service.get(1L, DocumentFileService.FilePurpose.DOWNLOAD, null, null);
        assertThat(body.status()).isEqualTo(HttpStatus.OK);
        assertThat(body.disposition()).isEqualTo(Disposition.ATTACHMENT);
    }

    @Test
    @DisplayName("授权在存储访问之前：未授权文档不会调用 FileStorageService.open")
    void authorizationBeforeOpen() {
        when(applicationService.authorizeFileRead(99L))
                .thenThrow(new ClientException(ClientErrorCode.FORBIDDEN));
        assertThatThrownBy(() -> service.get(99L, DocumentFileService.FilePurpose.DOWNLOAD, null, null))
                .isInstanceOf(ClientException.class);
        verify(fileStorageService, org.mockito.Mockito.never()).open(anyString(), anyString());
    }

    private static byte[] readAll(Resource resource) {
        try (var in = resource.getInputStream()) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
