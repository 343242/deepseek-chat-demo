package com.smart.rag.rag;

import com.smart.rag.common.team.TeamStatusService;
import com.smart.rag.infrastructure.exception.ClientException;
import com.smart.rag.infrastructure.exception.ServiceException;
import com.smart.rag.infrastructure.exception.errorcode.ClientErrorCode;
import com.smart.rag.infrastructure.exception.errorcode.ServiceErrorCode;
import com.smart.rag.rag.chunk.ParentChildChunkStrategy;
import com.smart.rag.rag.chunk.TokenChunkStrategy;
import com.smart.rag.rag.config.DocumentProperties;
import com.smart.rag.rag.dto.DocumentDTO;
import com.smart.rag.rag.entity.RagDocument;
import com.smart.rag.rag.etl.EtlStatus;
import com.smart.rag.rag.mapper.RagDocumentMapper;
import com.smart.rag.rag.service.EtlDispatchService;
import com.smart.rag.rag.service.FileStorageService;
import com.smart.rag.rag.service.impl.DocumentApplicationServiceImpl;
import com.smart.rag.rag.service.impl.DocumentLifecycleService;
import com.smart.rag.rag.service.impl.DocumentValidator;
import com.smart.rag.rag.upload.BucketResolver;
import com.smart.rag.rag.upload.ChunkUploadInitRequest;
import com.smart.rag.rag.upload.ChunkUploadResult;
import com.smart.rag.rag.upload.ChunkUploadServiceImpl;
import com.smart.rag.rag.upload.ChunkSizeStrategy;
import com.smart.rag.team.service.TeamMembershipVerifier;
import com.smart.rag.team.upload.UploadStrategyFactory;
import io.minio.MinioClient;
import org.apache.commons.codec.digest.DigestUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.ai.document.Document;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * W2: RAG correctness quick wins — focused regression tests.
 * <ul>
 *   <li>R1-H1: {@code complete()} throws on missing doc (no more null-as-200)</li>
 *   <li>R1-H4: {@code verifyAccess} throws DOCUMENT_NOT_FOUND vs FORBIDDEN</li>
 *   <li>R1-L1: splitter constructed once per bean (behavioral — chunking correct)</li>
 *   <li>U1: {@link DigestUtils#md5Hex} matches known MD5 (sanity)</li>
 * </ul>
 */
@DisplayName("W2: RAG correctness quick wins")
class W2CorrectnessQuickWinsTest {

    // ==================== R1-H4: verifyAccess → DOCUMENT_NOT_FOUND vs FORBIDDEN ====================

    @Nested
    @ExtendWith(MockitoExtension.class)
    @MockitoSettings(strictness = Strictness.LENIENT)
    @DisplayName("R1-H4: getById → 404 for not-found, 403 for forbidden")
    class VerifyAccessStatusCodes {

        @Mock private EtlDispatchService etlDispatchService;
        @Mock private RagDocumentMapper ragDocumentMapper;
        @Mock private DocumentLifecycleService documentLifecycleService;
        @Mock private UploadStrategyFactory uploadStrategyFactory;
        @Mock private TeamMembershipVerifier teamMembershipVerifier;

        private DocumentApplicationServiceImpl service;

        @BeforeEach
        void setUp() {
            service = new DocumentApplicationServiceImpl(
                    etlDispatchService, ragDocumentMapper, documentLifecycleService,
                    uploadStrategyFactory, teamMembershipVerifier);
        }

        @AfterEach
        void clearContext() {
            SecurityContextHolder.clearContext();
        }

        private void loginAs(Long userId) {
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken(userId, "n/a", List.of()));
        }

        @Test
        @DisplayName("文档不存在 → ServiceException(DOCUMENT_NOT_FOUND)")
        void notFound_throws_documentNotFound() {
            loginAs(1L);
            when(ragDocumentMapper.selectById(999L)).thenReturn(null);

            assertThatThrownBy(() -> service.getById(999L))
                    .isInstanceOfSatisfying(ServiceException.class,
                            ex -> assertThat(ex.getErrorCode())
                                    .as("不存在的文档必须返回 DOCUMENT_NOT_FOUND (204001)")
                                    .isEqualTo(ServiceErrorCode.DOCUMENT_NOT_FOUND));
        }

        @Test
        @DisplayName("个人文档属于他人 → ClientException(FORBIDDEN)")
        void forbidden_throws_forbidden() {
            loginAs(1L);
            RagDocument othersDoc = new RagDocument();
            othersDoc.setId(42L);
            othersDoc.setUserId(2L); // 属于另一个用户
            othersDoc.setTeamId(null); // 个人文档
            when(ragDocumentMapper.selectById(42L)).thenReturn(othersDoc);

            assertThatThrownBy(() -> service.getById(42L))
                    .isInstanceOfSatisfying(ClientException.class,
                            ex -> assertThat(ex.getErrorCode())
                                    .as("无权访问必须返回 FORBIDDEN (100004)")
                                    .isEqualTo(ClientErrorCode.FORBIDDEN));
        }

        @Test
        @DisplayName("自己的文档 → 正常返回 DTO")
        void ownDocument_returns_dto() {
            loginAs(1L);
            RagDocument myDoc = new RagDocument();
            myDoc.setId(42L);
            myDoc.setUserId(1L);
            myDoc.setTeamId(null);
            myDoc.setStatus(EtlStatus.COMPLETED);
            when(ragDocumentMapper.selectById(42L)).thenReturn(myDoc);

            DocumentDTO dto = service.getById(42L);
            assertThat(dto).isNotNull();
            assertThat(dto.id()).isEqualTo(42L);
        }
    }

    // ==================== R1-L1: TokenTextSplitter reused ====================

    @Nested
    @DisplayName("R1-L1: splitter 构造一次复用 — 行为正确性")
    class SplitterReuse {

        @Test
        @DisplayName("TokenChunkStrategy: 多次 chunk() 产出一致结果")
        void tokenStrategy_repeatedChunking_consistent() {
            DocumentProperties props = new DocumentProperties();
            props.setChunkSize(100);
            TokenChunkStrategy strategy = new TokenChunkStrategy(props);

            Document doc = new Document("The quick brown fox jumps over the lazy dog. ".repeat(50));
            List<Document> firstRun = strategy.chunk(List.of(doc), "test.txt");
            List<Document> secondRun = strategy.chunk(List.of(doc), "test.txt");

            assertThat(secondRun).hasSameSizeAs(firstRun);
            assertThat(secondRun)
                    .as("复用 splitter 不影响分块行为：第二次结果与第一次同尺寸")
                    .isNotEmpty();
        }

        @Test
        @DisplayName("ParentChildChunkStrategy: 多次 chunk() 产出一致结果")
        void parentChildStrategy_repeatedChunking_consistent() {
            DocumentProperties props = new DocumentProperties();
            props.setParentChunkSize(500);
            props.setChildChunkSize(100);
            ParentChildChunkStrategy strategy = new ParentChildChunkStrategy(props);

            Document doc = new Document("The quick brown fox jumps over the lazy dog. ".repeat(50));
            List<Document> firstRun = strategy.chunk(List.of(doc), "test.txt");
            List<Document> secondRun = strategy.chunk(List.of(doc), "test.txt");

            assertThat(secondRun).hasSameSizeAs(firstRun);
            assertThat(secondRun)
                    .as("复用 splitter 不影响父子分块行为")
                    .isNotEmpty();
        }
    }

    // ==================== U1: DigestUtils sanity ====================

    @Nested
    @DisplayName("U1: DigestUtils.md5Hex 与已知值一致")
    class DigestUtilsMd5 {

        @Test
        @DisplayName("已知输入 → 已知 MD5 hex (lowercase)")
        void knownInput_knownMd5() {
            // RFC 1321 test suite: MD5("abc") = 900150983cd24fb0d6963f7d28e17f72
            String md5 = DigestUtils.md5Hex("abc".getBytes(StandardCharsets.UTF_8));
            assertThat(md5).isEqualTo("900150983cd24fb0d6963f7d28e17f72");
        }

        @Test
        @DisplayName("空输入 → d41d8cd98f00b204e9800998ecf8427e")
        void emptyInput_knownMd5() {
            String md5 = DigestUtils.md5Hex(new byte[0]);
            assertThat(md5).isEqualTo("d41d8cd98f00b204e9800998ecf8427e");
        }
    }

    // ==================== R1-M7: chunk-upload init path trims spaced whitelist ====================

    @Nested
    @ExtendWith(MockitoExtension.class)
    @MockitoSettings(strictness = Strictness.LENIENT)
    @DisplayName("R1-M7: chunk-upload init 接受含空格的白名单配置")
    class ChunkUploadInitSpacedWhitelist {

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
        @Mock private ValueOperations<String, String> valueOperations;

        private ChunkUploadServiceImpl service;

        @BeforeEach
        void setUp() {
            // 配置含空格的白名单：旧代码的 Set.of(split) 会保留 " text/plain"，导致拒绝
            DocumentProperties props = new DocumentProperties();
            props.setAllowedMimeTypes("application/pdf, text/plain");
            DocumentValidator validator = new DocumentValidator(props);

            when(redisTemplate.opsForHash()).thenReturn(hashOperations);
            when(redisTemplate.opsForValue()).thenReturn(valueOperations);

            service = new ChunkUploadServiceImpl(
                    redisTemplate, minioClient, bucketResolver, chunkSizeStrategy,
                    props, validator, fileStorageService, ragDocumentMapper,
                    etlDispatchService, teamStatusService, Runnable::run,
                    eventPublisher, null);
        }

        @AfterEach
        void clearContext() {
            SecurityContextHolder.clearContext();
        }

        @Test
        @DisplayName("声明 text/plain + 配置 \"application/pdf, text/plain\" → init 不拒绝 MIME")
        void spacedWhitelist_acceptsTextPlain() {
            loginAs(1L);

            ChunkUploadInitRequest request = new ChunkUploadInitRequest(
                    "d41d8cd98f00b204e9800998ecf8427e",
                    "test.txt",
                    1024L,
                    "text/plain",
                    null, null, null);

            // stub resume check → null (no existing upload to resume)
            when(valueOperations.get(anyString())).thenReturn(null);
            when(chunkSizeStrategy.calculateChunkSize(anyLong())).thenReturn(1048576);
            when(bucketResolver.resolve(any())).thenReturn("test-bucket");

            // 关键断言：init 不抛 UPLOAD_MIME_UNSUPPORTED（旧 trim 缺失会在这里失败）
            ChunkUploadResult result = service.init(request);
            assertThat(result).as("init 应接受 spaced whitelist 中的 text/plain").isNotNull();
        }

        private void loginAs(Long userId) {
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken(userId, "n/a", List.of()));
        }
    }

    // ==================== R1-H1: complete() throws ETL_FAILED on missing doc ====================

    @Nested
    @ExtendWith(MockitoExtension.class)
    @MockitoSettings(strictness = Strictness.LENIENT)
    @DisplayName("R1-H1: complete() 合并后文档未找到 → ServiceException(ETL_FAILED)")
    class CompleteThrowsOnMissingDoc {

        private static final String UPLOAD_ID = "race-upload-id";
        private static final String FILE_MD5 = "d41d8cd98f00b204e9800998ecf8427e";

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

        private ChunkUploadServiceImpl service;

        @BeforeEach
        void setUp() {
            DocumentProperties props = new DocumentProperties();
            DocumentValidator validator = new DocumentValidator(props);

            when(redisTemplate.opsForHash()).thenReturn(hashOperations);

            service = new ChunkUploadServiceImpl(
                    redisTemplate, minioClient, bucketResolver, chunkSizeStrategy,
                    props, validator, fileStorageService, ragDocumentMapper,
                    etlDispatchService, teamStatusService, Runnable::run,
                    eventPublisher, null);
        }

        @AfterEach
        void clearContext() {
            SecurityContextHolder.clearContext();
        }

        @Test
        @DisplayName("performMerge 返回 null + 兜底查询 null → ServiceException(ETL_FAILED)")
        void complete_postMergeDocMissing_throwsEtlFailed() {
            loginAs(1L);

            // complete() 第一次 entries → 返回合法 session（userId=1）
            // performMerge 内部第二次 entries → 返回空（模拟竞态清理）→ performMerge 返回 null
            Map<Object, Object> validSession = new HashMap<>();
            validSession.put("userId", "1");
            validSession.put("fileMd5", FILE_MD5);
            validSession.put("fileName", "test.txt");
            validSession.put("fileSize", "1024");
            validSession.put("mimeType", "text/plain");
            validSession.put("chunkSize", "1024");
            validSession.put("totalChunks", "1");
            validSession.put("bucket", "test-bucket");
            validSession.put("objectName", "chunks/user/abc");
            validSession.put("createdAt", String.valueOf(System.currentTimeMillis()));

            when(hashOperations.entries(anyString()))
                    .thenReturn(validSession)   // complete() reads session
                    .thenReturn(Map.of());      // performMerge reads session → empty → returns null

            // not merging
            when(hashOperations.hasKey(anyString(), eq("__merging"))).thenReturn(false);

            // fallback lookup returns null
            when(ragDocumentMapper.selectOne(any())).thenReturn(null);

            assertThatThrownBy(() -> service.complete(UPLOAD_ID, FILE_MD5))
                    .isInstanceOfSatisfying(ServiceException.class,
                            ex -> assertThat(ex.getErrorCode())
                                    .as("合并后文档未找到必须抛 ETL_FAILED")
                                    .isEqualTo(ServiceErrorCode.ETL_FAILED));
        }

        private void loginAs(Long userId) {
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken(userId, "n/a", List.of()));
        }
    }
}
