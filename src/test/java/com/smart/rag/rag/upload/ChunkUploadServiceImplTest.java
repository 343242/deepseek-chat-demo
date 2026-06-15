package com.smart.rag.rag.upload;

import com.smart.rag.common.team.TeamStatusService;
import com.smart.rag.infrastructure.exception.ClientException;
import com.smart.rag.infrastructure.exception.errorcode.ClientErrorCode;
import com.smart.rag.rag.config.DocumentProperties;
import com.smart.rag.rag.entity.RagDocument;
import com.smart.rag.rag.mapper.RagDocumentMapper;
import com.smart.rag.rag.service.EtlDispatchService;
import com.smart.rag.rag.service.FileStorageService;
import com.smart.rag.rag.service.impl.DocumentValidator;
import io.minio.GetObjectArgs;
import io.minio.GetObjectResponse;
import io.minio.MinioClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.io.ByteArrayInputStream;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * H-T1 (blocker): ChunkUploadServiceImpl.performMerge 的 R2-H1 编排测试。
 * <p>
 * 不只是验证 {@link DocumentValidator} 谓词，而是验证编排本身：
 * <ul>
 *   <li>(a) MIME 不匹配 → {@link ClientErrorCode#UPLOAD_MIME_UNSUPPORTED} + 合并对象删除 +
 *       Redis 合并锁清除（fail-closed cleanup，防止孤儿 MinIO 对象）</li>
 *   <li>(b) OOXML 声明 + zip 容器检测 → 用声明子类型路由 persist/dispatch（检测确认放行）</li>
 *   <li>(c) 探测失败（null）→ fail-closed 拒绝，不静默接受</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ChunkUploadServiceImpl.performMerge — R2-H1 MIME 检测编排")
class ChunkUploadServiceImplTest {

    private static final String DOCX_MIME =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    private static final String PDF_MIME = "application/pdf";
    private static final String UPLOAD_ID = "test-upload-id";
    private static final String BUCKET = "test-bucket";
    private static final String BASE_PATH = "chunks/user/abc";
    private static final Executor DIRECT_EXECUTOR = Runnable::run;

    @Mock private MinioClient minioClient;
    @Mock private BucketResolver bucketResolver;
    @Mock private ChunkSizeStrategy chunkSizeStrategy;
    @Mock private FileStorageService fileStorageService;
    @Mock private RagDocumentMapper ragDocumentMapper;
    @Mock private EtlDispatchService etlDispatchService;
    @Mock private TeamStatusService teamStatusService;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private HashOperations<String, Object, Object> hashOperations;

    /** 真实的 DocumentValidator（带白名单默认值），让 isDetectedMimeTypeAcceptable 真实运行 */
    private DocumentValidator documentValidator;

    private ChunkUploadServiceImpl service;

    /**
     * 构造一份合法 session（与 createNewSession 写入的字段一致）。
     *
     * @param declaredMime 客户端声明的 MIME
     * @param fileMd5      合并后对象的真实 MD5（由调用方根据 payload 计算）
     */
    private Map<Object, Object> sessionFixture(String declaredMime, String fileMd5) {
        return sessionFixture(declaredMime, fileMd5, "doc.docx");
    }

    private Map<Object, Object> sessionFixture(String declaredMime, String fileMd5, String fileName) {
        Map<Object, Object> session = new HashMap<>();
        session.put("fileMd5", fileMd5);
        session.put("fileName", fileName);
        session.put("fileSize", "1024");
        session.put("mimeType", declaredMime);
        session.put("chunkSize", "1024");
        session.put("totalChunks", "1");
        session.put("userId", "1");
        session.put("bucket", BUCKET);
        session.put("objectName", BASE_PATH);
        session.put("createdAt", String.valueOf(System.currentTimeMillis()));
        return session;
    }

    /**
     * 准备 MinIO 流：composeObject 之后会先 getObject 算 MD5，再 getObject 探测 MIME。
     * 两次返回的内容必须指向同一份字节，故用相同 payload。
     *
     * @param payload 合并后的真实字节
     */
    private void stubMinioStreams(byte[] payload) throws Exception {
        // MD5 计算 / MIME 探测均会调用 getObject；Mockito 默认按注册顺序匹配，但
        // LENIENT 下 we use any() 让两次都返回基于同一 payload 的独立流（流不可重用）。
        when(minioClient.getObject(any(GetObjectArgs.class)))
                .thenAnswer(inv -> new GetObjectResponse(
                        null, null, null, null,
                        new ByteArrayInputStream(payload)));
    }

    private static byte[] md5(byte[] data) throws Exception {
        return MessageDigest.getInstance("MD5").digest(data);
    }

    private static String hex(byte[] bytes) {
        char[] chars = "0123456789abcdef".toCharArray();
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(chars[(b >> 4) & 0x0f]).append(chars[b & 0x0f]);
        }
        return sb.toString();
    }

    @BeforeEach
    void setUp() {
        DocumentProperties props = new DocumentProperties();
        // 默认白名单包含 pdf/docx/pptx/text 等，确认默认配置即可覆盖测试场景
        documentValidator = new DocumentValidator(props);

        when(redisTemplate.opsForHash()).thenReturn(hashOperations);

        service = new ChunkUploadServiceImpl(
                redisTemplate, minioClient, bucketResolver, chunkSizeStrategy,
                props, documentValidator, fileStorageService, ragDocumentMapper,
                etlDispatchService, teamStatusService, DIRECT_EXECUTOR,
                eventPublisher, null);
    }

    @Test
    @DisplayName("(a) 声明 PDF 但合并对象实为 ZIP → 拒绝 + 删除合并对象 + 清除 Redis 合并锁")
    void mismatchRejected_cleansUpMergedObjectAndRedisFlag() throws Exception {
        // 合并后真实字节：zip local file header
        byte[] zipBytes = {0x50, 0x4B, 0x03, 0x04, 0x00, 0x00, 0x00, 0x00, 0x01, 0x02};
        stubMinioStreams(zipBytes);

        when(hashOperations.entries(anyString())).thenReturn(
                sessionFixture(PDF_MIME, hex(md5(zipBytes)), "report.pdf"));

        assertThatThrownBy(() -> service.performMerge(UPLOAD_ID))
                .isInstanceOfSatisfying(ClientException.class,
                        ex -> assertThat(ex.getErrorCode())
                                .as("必须以 UPLOAD_MIME_UNSUPPORTED 拒绝")
                                .isEqualTo(ClientErrorCode.UPLOAD_MIME_UNSUPPORTED));

        // (a.1) 合并对象被删除：removeObject 至少调用一次（合并对象 + 临时分片）
        verify(minioClient, atLeastOnce()).removeObject(any());
        // (a.2) Redis 合并锁字段被清除：__merging
        verify(hashOperations).delete(eq(UploadRedisConstants.partsKey(UPLOAD_ID)),
                eq(UploadRedisConstants.MERGING_FIELD));
        // (a.3) 未持久化、未分发 ETL（verifyNoInteractions 避免 insert 重载歧义）
        verifyNoInteractions(ragDocumentMapper);
        verifyNoInteractions(etlDispatchService);
    }

    @Test
    @DisplayName("(b) 声明 docx + 检测到 zip 容器 → 用 docx 声明子类型 persist 与 dispatch")
    void ooxmlDeclared_zipDetected_routesWithDeclaredSubtype() throws Exception {
        // 合并后真实字节：zip 容器（魔数无法区分 docx/pptx/xlsx，需依赖扩展名）
        byte[] zipBytes = {0x50, 0x4B, 0x03, 0x04, 0x00, 0x00, 0x00, 0x00, 'a', 'b'};
        stubMinioStreams(zipBytes);

        when(hashOperations.entries(anyString())).thenReturn(sessionFixture(DOCX_MIME, hex(md5(zipBytes))));
        when(ragDocumentMapper.insert(any(RagDocument.class))).thenAnswer(inv -> {
            ((RagDocument) inv.getArgument(0)).setId(42L);
            return 1;
        });

        service.performMerge(UPLOAD_ID);

        // (b.1) persist 使用 docx 声明子类型（检测仅确认 zip 容器，子类型由扩展名路由）
        ArgumentCaptor<RagDocument> docCaptor = ArgumentCaptor.forClass(RagDocument.class);
        verify(ragDocumentMapper).insert(docCaptor.capture());
        assertThat(docCaptor.getValue().getMimeType())
                .as("persistDocument 必须使用检测确认的 docx MIME，而非 generic zip")
                .isEqualTo(DOCX_MIME);

        // (b.2) ETL dispatch 使用同一 docx MIME 路由解析器
        ArgumentCaptor<String> mimeCaptor = ArgumentCaptor.forClass(String.class);
        verify(etlDispatchService).dispatchAsync(
                eq(42L), eq(BUCKET), anyString(), anyString(),
                mimeCaptor.capture(),
                org.mockito.ArgumentMatchers.anyLong(), any(), any());
        assertThat(mimeCaptor.getValue())
                .as("dispatchAsync 必须使用 effective(docx) MIME")
                .isEqualTo(DOCX_MIME);
    }

    @Test
    @DisplayName("(c) 探测失败（null）→ fail-closed 拒绝，不静默接受")
    void detectionFailure_isFailClosed() throws Exception {
        // 合并后真实字节：无法识别的魔数（既非 PDF / zip / text）
        byte[] unrecognizable = {(byte) 0xFE, (byte) 0xFF, 0x00, 0x01, 0x02, 0x03, 0x04, 0x05};
        stubMinioStreams(unrecognizable);

        when(hashOperations.entries(anyString())).thenReturn(sessionFixture(PDF_MIME, hex(md5(unrecognizable))));

        assertThatThrownBy(() -> service.performMerge(UPLOAD_ID))
                .isInstanceOfSatisfying(ClientException.class,
                        ex -> assertThat(ex.getErrorCode())
                                .as("探测失败必须 fail-closed 为 UPLOAD_MIME_UNSUPPORTED")
                                .isEqualTo(ClientErrorCode.UPLOAD_MIME_UNSUPPORTED));

        // fail-closed：不落库、不分发（verifyNoInteractions 避免 insert 重载歧义）
        verifyNoInteractions(ragDocumentMapper);
        verifyNoInteractions(etlDispatchService);
    }
}
