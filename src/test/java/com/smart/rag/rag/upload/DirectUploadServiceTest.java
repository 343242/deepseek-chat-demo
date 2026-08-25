package com.smart.rag.rag.upload;

import com.smart.rag.infrastructure.exception.ClientException;
import com.smart.rag.infrastructure.exception.RemoteException;
import com.smart.rag.infrastructure.exception.ServiceException;
import com.smart.rag.infrastructure.exception.errorcode.ClientErrorCode;
import com.smart.rag.infrastructure.exception.errorcode.ServiceErrorCode;
import com.smart.rag.infrastructure.web.util.SecurityUtils;
import com.smart.rag.rag.config.DocumentProperties;
import com.smart.rag.rag.dto.DocumentUploadResponse;
import com.smart.rag.rag.entity.RagDocument;
import com.smart.rag.rag.etl.EtlStatus;
import com.smart.rag.rag.service.DocumentDedupService;
import com.smart.rag.rag.service.DocumentMimePolicy;
import com.smart.rag.rag.service.FileStorageService;
import com.smart.rag.rag.service.TeamAccessGate;
import com.smart.rag.rag.service.impl.DocumentValidator;
import com.smart.rag.rag.service.impl.ValidatedDocumentFile;
import com.smart.rag.rag.upload.s3.S3MultipartGateway;
import com.smart.rag.rag.upload.s3.UploadGoneException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.io.ByteArrayInputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.smart.rag.rag.upload.DirectUploadRedisConstants.FIELD_BUCKET;
import static com.smart.rag.rag.upload.DirectUploadRedisConstants.FIELD_CHUNK_SIZE;
import static com.smart.rag.rag.upload.DirectUploadRedisConstants.FIELD_CREATED_AT;
import static com.smart.rag.rag.upload.DirectUploadRedisConstants.FIELD_DOCUMENT_ID;
import static com.smart.rag.rag.upload.DirectUploadRedisConstants.FIELD_FILE_CHECKSUM;
import static com.smart.rag.rag.upload.DirectUploadRedisConstants.FIELD_FILE_NAME;
import static com.smart.rag.rag.upload.DirectUploadRedisConstants.FIELD_FILE_SIZE;
import static com.smart.rag.rag.upload.DirectUploadRedisConstants.FIELD_MIME_TYPE;
import static com.smart.rag.rag.upload.DirectUploadRedisConstants.FIELD_MODE;
import static com.smart.rag.rag.upload.DirectUploadRedisConstants.FIELD_OBJECT_KEY;
import static com.smart.rag.rag.upload.DirectUploadRedisConstants.FIELD_RESULT_STATUS;
import static com.smart.rag.rag.upload.DirectUploadRedisConstants.FIELD_STATUS;
import static com.smart.rag.rag.upload.DirectUploadRedisConstants.FIELD_TOTAL_CHUNKS;
import static com.smart.rag.rag.upload.DirectUploadRedisConstants.FIELD_UPLOAD_ID;
import static com.smart.rag.rag.upload.DirectUploadRedisConstants.FIELD_USER_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DirectUploadServiceImpl 分支矩阵（设计文档测试策略）：
 * init 三态/限流/超限/团队额度、commit 失败矩阵（校验和/尺寸/分片/UPLOAD_GONE）、
 * commit 状态机（COMMITTED 幂等回查、并发冲突、H1 接管三分支）、团队审批分支。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("DirectUploadServiceImpl — init/commit 分支矩阵与状态机")
class DirectUploadServiceTest {

    private static final Long USER_ID = 7L;
    private static final Long TEAM_ID = 3L;
    private static final String SESSION_ID = "0b1c2d3e-4f5a-6789-abcd-ef0123456789";
    private static final String CHECKSUM = "a".repeat(64);
    private static final String BUCKET = "test-bucket";
    private static final String OBJECT_KEY = "uploads/pending/7/" + SESSION_ID + "/abcd_file.pdf";
    private static final String UPLOAD_ID = "mpu-upload-id";
    private static final String PDF = "application/pdf";
    private static final long CHUNK = 5L * 1024 * 1024;
    private static final long BIG = 12L * 1024 * 1024; // 12MB → 3 片

    @Mock private DirectUploadSessionStore store;
    @Mock private S3MultipartGateway gateway;
    @Mock private DocumentProperties documentProperties;
    @Mock private DocumentMimePolicy mimePolicy;
    @Mock private DocumentValidator documentValidator;
    @Mock private BucketResolver bucketResolver;
    @Mock private FileStorageService fileStorageService;
    @Mock private DocumentDedupService dedupService;
    @Mock private TeamAccessGate teamAccessGate;
    @Mock private UploadDocumentPersistence persistence;

    private DirectUploadProperties properties;
    private DirectUploadServiceImpl service;
    private MockedStatic<SecurityUtils> securityUtils;

    @BeforeEach
    void setUp() {
        properties = new DirectUploadProperties();
        properties.setEnabled(true);
        when(documentProperties.getMaxFileSize()).thenReturn("50MB");
        when(mimePolicy.isAllowed(anyString())).thenReturn(true);
        when(bucketResolver.resolve(isNull())).thenReturn(BUCKET);
        when(bucketResolver.resolve(eq(TEAM_ID))).thenReturn("rag-team-3");
        securityUtils = mockStatic(SecurityUtils.class);
        securityUtils.when(SecurityUtils::getCurrentUserId).thenReturn(USER_ID);
        service = new DirectUploadServiceImpl(store, gateway, properties, documentProperties,
                mimePolicy, documentValidator, bucketResolver, fileStorageService,
                dedupService, teamAccessGate, persistence);
    }

    @AfterEach
    void tearDown() {
        securityUtils.close();
    }

    private DirectUploadInitRequest initRequest(long size) {
        return new DirectUploadInitRequest("file.pdf", size, PDF, CHECKSUM, null, null);
    }

    private Map<String, String> multipartSession(String status) {
        Map<String, String> s = baseSession(status);
        s.put(FIELD_MODE, DirectUploadInitResult.MODE_MULTIPART);
        s.put(FIELD_UPLOAD_ID, UPLOAD_ID);
        s.put(FIELD_CHUNK_SIZE, String.valueOf(CHUNK));
        s.put(FIELD_TOTAL_CHUNKS, "3");
        return s;
    }

    private Map<String, String> singleSession(String status) {
        Map<String, String> s = baseSession(status);
        s.put(FIELD_MODE, DirectUploadInitResult.MODE_SINGLE);
        s.put(FIELD_CHUNK_SIZE, String.valueOf(1024));
        s.put(FIELD_TOTAL_CHUNKS, "1");
        return s;
    }

    private Map<String, String> baseSession(String status) {
        Map<String, String> s = new HashMap<>();
        s.put(FIELD_STATUS, status);
        s.put(FIELD_FILE_CHECKSUM, CHECKSUM);
        s.put(FIELD_FILE_NAME, "file.pdf");
        s.put(FIELD_FILE_SIZE, String.valueOf(BIG));
        s.put(FIELD_MIME_TYPE, PDF);
        s.put(FIELD_BUCKET, BUCKET);
        s.put(FIELD_OBJECT_KEY, OBJECT_KEY);
        s.put(FIELD_USER_ID, String.valueOf(USER_ID));
        s.put(FIELD_CREATED_AT, "1");
        return s;
    }

    private DirectUploadCommitRequest fullParts() {
        return new DirectUploadCommitRequest(List.of(
                new DirectUploadCommitRequest.PartDeclaration(1, "etag-1", CHUNK),
                new DirectUploadCommitRequest.PartDeclaration(2, "etag-2", CHUNK),
                new DirectUploadCommitRequest.PartDeclaration(3, "etag-3", BIG - 2 * CHUNK)));
    }

    /** stat + digests + tika + getObject 的通过型桩（statEtag 为网关归一化后的无引号形式） */
    private void stubVerificationPass(long size, String sha256, String md5, String statEtag) throws Exception {
        when(gateway.statObject(BUCKET, OBJECT_KEY))
                .thenReturn(new S3MultipartGateway.PendingObjectStat(statEtag, size, PDF));
        when(gateway.computeDigests(BUCKET, OBJECT_KEY))
                .thenReturn(new S3MultipartGateway.ObjectDigests(sha256, md5));
        when(gateway.getObject(BUCKET, OBJECT_KEY)).thenReturn(new ByteArrayInputStream(new byte[0]));
        when(documentValidator.validate(any(), anyString(), anyLong()))
                .thenReturn(new ValidatedDocumentFile("file.pdf", size, PDF));
    }

    // ==================== init ====================

    @Test
    @DisplayName("flag 关闭：所有入口拒绝（NOT_FOUND）")
    void disabledRejects() {
        properties.setEnabled(false);
        assertThatThrownBy(() -> service.init(initRequest(1024)))
                .isInstanceOf(ServiceException.class)
                .extracting(e -> ((ServiceException) e).getErrorCode())
                .isEqualTo(ServiceErrorCode.NOT_FOUND);
    }

    @Test
    @DisplayName("init 秒传：BloomFilter 命中 + DB 确认 → instant")
    void instantHit() {
        when(store.incrRateLimit(USER_ID)).thenReturn(1L);
        when(dedupService.mayExist(CHECKSUM)).thenReturn(true);
        RagDocument existing = new RagDocument();
        existing.setId(42L);
        existing.setFileName("file.pdf");
        when(dedupService.confirmExisting(CHECKSUM, USER_ID, null)).thenReturn(existing);

        DirectUploadInitResult result = service.init(initRequest(1024));

        assertThat(result.mode()).isEqualTo("instant");
        assertThat(result.documentId()).isEqualTo(42L);
        verify(store, never()).save(anyString(), any());
    }

    @Test
    @DisplayName("init single（≤5MB）：presign PUT + 会话 + 反向索引")
    void initSingle() {
        when(store.incrRateLimit(USER_ID)).thenReturn(1L);
        when(dedupService.mayExist(CHECKSUM)).thenReturn(false);
        when(gateway.presignPutUrl(eq(BUCKET), anyString(), any())).thenReturn("https://minio/put?sig=1");

        DirectUploadInitResult result = service.init(initRequest(1024));

        assertThat(result.mode()).isEqualTo("single");
        assertThat(result.uploadUrl()).isNotBlank();
        assertThat(result.sessionId()).isNotBlank();
        verify(gateway, never()).createMultipartUpload(anyString(), anyString());
        verify(store).save(anyString(), any());
        verify(store).putFileIndex(USER_ID, CHECKSUM, result.sessionId());
    }

    @Test
    @DisplayName("init multipart（>5MB）：CreateMultipartUpload + MPU 登记")
    void initMultipart() {
        when(store.incrRateLimit(USER_ID)).thenReturn(1L);
        when(dedupService.mayExist(CHECKSUM)).thenReturn(false);
        when(gateway.createMultipartUpload(anyString(), anyString())).thenReturn(UPLOAD_ID);

        DirectUploadInitResult result = service.init(initRequest(BIG));

        assertThat(result.mode()).isEqualTo("multipart");
        assertThat(result.uploadId()).isEqualTo(UPLOAD_ID);
        assertThat(result.totalChunks()).isEqualTo(3);
        verify(gateway).createMultipartUpload(eq(BUCKET), anyString());
        verify(store).registerMpu(eq(BUCKET), anyString(), eq(UPLOAD_ID));
    }

    @Test
    @DisplayName("init 限流：30 次/分独立桶超限 → RATE_LIMITED")
    void rateLimited() {
        when(store.incrRateLimit(USER_ID)).thenReturn(31L);
        assertThatThrownBy(() -> service.init(initRequest(1024)))
                .isInstanceOf(ClientException.class)
                .extracting(e -> ((ClientException) e).getErrorCode())
                .isEqualTo(ClientErrorCode.RATE_LIMITED);
    }

    @Test
    @DisplayName("init 超限：声明 >50MB → UPLOAD_FILE_TOO_LARGE")
    void sizeExceeded() {
        assertThatThrownBy(() -> service.init(initRequest(51L * 1024 * 1024)))
                .isInstanceOf(ClientException.class)
                .extracting(e -> ((ClientException) e).getErrorCode())
                .isEqualTo(ClientErrorCode.UPLOAD_FILE_TOO_LARGE);
    }

    @Test
    @DisplayName("init 团队额度：超配额 → UPLOAD_QUOTA_EXCEEDED（端口转发）")
    void teamQuotaExceeded() {
        when(store.incrRateLimit(USER_ID)).thenReturn(1L);
        org.mockito.Mockito.doThrow(new ClientException(ClientErrorCode.UPLOAD_QUOTA_EXCEEDED))
                .when(teamAccessGate).verifyUploadQuota(TEAM_ID, USER_ID, BIG);
        assertThatThrownBy(() -> service.init(new DirectUploadInitRequest(
                "file.pdf", BIG, PDF, CHECKSUM, TEAM_ID, null)))
                .isInstanceOf(ClientException.class)
                .extracting(e -> ((ClientException) e).getErrorCode())
                .isEqualTo(ClientErrorCode.UPLOAD_QUOTA_EXCEEDED);
    }

    @Test
    @DisplayName("init 续传：反向索引命中 ACTIVE multipart 会话 → 返回会话元数据（不重建）")
    void resumeMultipart() {
        when(store.incrRateLimit(USER_ID)).thenReturn(1L);
        when(dedupService.mayExist(CHECKSUM)).thenReturn(false);
        when(store.findResumableSessionId(USER_ID, CHECKSUM)).thenReturn(SESSION_ID);
        when(store.load(SESSION_ID)).thenReturn(multipartSession("ACTIVE"));

        DirectUploadInitResult result = service.init(initRequest(BIG));

        assertThat(result.mode()).isEqualTo("multipart");
        assertThat(result.sessionId()).isEqualTo(SESSION_ID);
        assertThat(result.uploadId()).isEqualTo(UPLOAD_ID);
        verify(gateway, never()).createMultipartUpload(anyString(), anyString());
    }

    @Test
    @DisplayName("init 续传：COMMITTED 会话 → 按秒传语义返回 documentId")
    void resumeCommittedAsInstant() {
        when(store.incrRateLimit(USER_ID)).thenReturn(1L);
        when(dedupService.mayExist(CHECKSUM)).thenReturn(false);
        when(store.findResumableSessionId(USER_ID, CHECKSUM)).thenReturn(SESSION_ID);
        Map<String, String> s = multipartSession("COMMITTED");
        s.put(FIELD_DOCUMENT_ID, "42");
        when(store.load(SESSION_ID)).thenReturn(s);

        DirectUploadInitResult result = service.init(initRequest(BIG));

        assertThat(result.mode()).isEqualTo("instant");
        assertThat(result.documentId()).isEqualTo(42L);
    }

    // ==================== part-urls 校验 ====================

    @Test
    @DisplayName("part-urls：single 会话 → MODE_INVALID")
    void partUrlsOnSingleSessionRejected() {
        when(store.load(SESSION_ID)).thenReturn(singleSession("ACTIVE"));
        assertThatThrownBy(() -> service.partUrls(SESSION_ID,
                new DirectUploadPartUrlsRequest(List.of(1)), null))
                .isInstanceOf(ServiceException.class)
                .extracting(e -> ((ServiceException) e).getErrorCode())
                .isEqualTo(ServiceErrorCode.DIRECT_UPLOAD_MODE_INVALID);
    }

    @Test
    @DisplayName("part-urls：单批超上限 / 序号越界 / 重复 → BAD_REQUEST")
    void partUrlsValidation() {
        when(store.load(SESSION_ID)).thenReturn(multipartSession("ACTIVE"));
        assertThatThrownBy(() -> service.partUrls(SESSION_ID,
                new DirectUploadPartUrlsRequest(java.util.stream.IntStream.rangeClosed(1, 21).boxed().toList()), null))
                .isInstanceOf(ClientException.class);
        assertThatThrownBy(() -> service.partUrls(SESSION_ID,
                new DirectUploadPartUrlsRequest(List.of(0)), null))
                .isInstanceOf(ClientException.class);
        assertThatThrownBy(() -> service.partUrls(SESSION_ID,
                new DirectUploadPartUrlsRequest(List.of(1, 1)), null))
                .isInstanceOf(ClientException.class);
    }

    // ==================== 会话访问控制 ====================

    @Test
    @DisplayName("会话劫持防护：owner 不符 → FORBIDDEN")
    void ownerMismatchRejected() {
        Map<String, String> s = multipartSession("ACTIVE");
        s.put(FIELD_USER_ID, "999");
        when(store.load(SESSION_ID)).thenReturn(s);
        assertThatThrownBy(() -> service.status(SESSION_ID, null))
                .isInstanceOf(ClientException.class)
                .extracting(e -> ((ClientException) e).getErrorCode())
                .isEqualTo(ClientErrorCode.FORBIDDEN);
    }

    @Test
    @DisplayName("团队一致性：个人端点访问团队会话 → FORBIDDEN")
    void teamScopeMismatchRejected() {
        Map<String, String> s = multipartSession("ACTIVE");
        s.put("teamId", String.valueOf(TEAM_ID));
        when(store.load(SESSION_ID)).thenReturn(s);
        assertThatThrownBy(() -> service.status(SESSION_ID, null))
                .isInstanceOf(ClientException.class)
                .extracting(e -> ((ClientException) e).getErrorCode())
                .isEqualTo(ClientErrorCode.FORBIDDEN);
    }

    // ==================== commit 状态机 ====================

    @Test
    @DisplayName("commit 幂等回查：COMMITTED → 原样返回 documentId + resultStatus")
    void committedIdempotentReplay() {
        Map<String, String> s = multipartSession("COMMITTED");
        s.put(FIELD_DOCUMENT_ID, "42");
        s.put(FIELD_RESULT_STATUS, "PENDING_APPROVAL");
        when(store.load(SESSION_ID)).thenReturn(s);
        when(store.acquireForCommit(SESSION_ID, properties.getCommitLeaseTtl()))
                .thenReturn(DirectUploadSessionStore.CommitAcquire.ALREADY_COMMITTED);

        DocumentUploadResponse resp = service.commit(SESSION_ID, fullParts(), null);

        assertThat(resp.id()).isEqualTo(42L);
        assertThat(resp.status()).isEqualTo(EtlStatus.PENDING_APPROVAL);
        verify(gateway, never()).completeMultipartUpload(anyString(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("并发双 commit：对端 COMMITTING 且租约存活 → 冲突（绝不假成功）")
    void concurrentConflictRejected() {
        when(store.load(SESSION_ID)).thenReturn(multipartSession("COMMITTING"));
        when(store.acquireForCommit(SESSION_ID, properties.getCommitLeaseTtl()))
                .thenReturn(DirectUploadSessionStore.CommitAcquire.CONFLICT);

        assertThatThrownBy(() -> service.commit(SESSION_ID, fullParts(), null))
                .isInstanceOf(ClientException.class)
                .hasMessageContaining("提交正在进行中");
    }

    // ==================== commit 成功/失败矩阵 ====================

    @Test
    @DisplayName("multipart 成功（个人）：Complete→复核→copy→persist(UPLOADED)→事件→ETL→markCommitted")
    void multipartCommitSuccess() throws Exception {
        Map<String, String> session = multipartSession("ACTIVE");
        when(store.load(SESSION_ID)).thenReturn(session);
        when(store.acquireForCommit(anyString(), any())).thenReturn(DirectUploadSessionStore.CommitAcquire.ACQUIRED);
        stubVerificationPass(BIG, CHECKSUM, "md5-x", "etag-mpu-3");
        RagDocument doc = new RagDocument();
        doc.setId(100L);
        doc.setStatus(EtlStatus.UPLOADED);
        when(persistence.insert(any())).thenReturn(doc);

        DocumentUploadResponse resp = service.commit(SESSION_ID, fullParts(), null);

        assertThat(resp.id()).isEqualTo(100L);
        assertThat(resp.status()).isEqualTo(EtlStatus.PROCESSING);
        verify(gateway).completeMultipartUpload(eq(BUCKET), eq(OBJECT_KEY), eq(UPLOAD_ID), any());
        // multipart 对象 ETag 为 -N 形式：不做 MD5 对拍（N1），copy 以 stat Etag 条件放行
        verify(gateway).copyObjectIfMatch(eq(BUCKET), eq(OBJECT_KEY), anyString(), eq("etag-mpu-3"), eq(PDF));
        verify(persistence).registerDedup(CHECKSUM);
        verify(persistence).publishCreated(100L, null, USER_ID, null);
        verify(persistence).dispatchEtl(eq(100L), eq(BUCKET), anyString(), eq("file.pdf"), eq(PDF), eq(BIG), eq(USER_ID), isNull());
        verify(store).markCommitted(SESSION_ID, 100L, "PROCESSING");
        verify(store).deleteFileIndex(USER_ID, CHECKSUM);
        verify(store).unregisterMpu(BUCKET, OBJECT_KEY, UPLOAD_ID);
        verify(gateway).removeObjectQuietly(BUCKET, OBJECT_KEY);
    }

    @Test
    @DisplayName("校验和失配 → CHECKSUM_MISMATCH + 删 pending + 回退 ACTIVE")
    void checksumMismatchRejected() throws Exception {
        when(store.load(SESSION_ID)).thenReturn(multipartSession("ACTIVE"));
        when(store.acquireForCommit(anyString(), any())).thenReturn(DirectUploadSessionStore.CommitAcquire.ACQUIRED);
        stubVerificationPass(BIG, "f".repeat(64), "md5-x", "etag-x");

        assertThatThrownBy(() -> service.commit(SESSION_ID, fullParts(), null))
                .isInstanceOf(ServiceException.class)
                .extracting(e -> ((ServiceException) e).getErrorCode())
                .isEqualTo(ServiceErrorCode.DIRECT_UPLOAD_CHECKSUM_MISMATCH);
        verify(gateway).removeObjectQuietly(BUCKET, OBJECT_KEY);
        verify(store).rollbackToActive(SESSION_ID);
        verify(store, never()).markCommitted(anyString(), anyLong(), anyString());
    }

    @Test
    @DisplayName("single 模式 TOCTOU（S1）：MD5 与 stat ETag 失配 → CHECKSUM_MISMATCH")
    void singleMd5MismatchRejected() throws Exception {
        Map<String, String> session = singleSession("ACTIVE");
        session.put(FIELD_FILE_SIZE, "1024");
        when(store.load(SESSION_ID)).thenReturn(session);
        when(store.acquireForCommit(anyString(), any())).thenReturn(DirectUploadSessionStore.CommitAcquire.ACQUIRED);
        stubVerificationPass(1024, CHECKSUM, "deadbeef", "different-etag");

        assertThatThrownBy(() -> service.commit(SESSION_ID, new DirectUploadCommitRequest(null), null))
                .isInstanceOf(ServiceException.class)
                .extracting(e -> ((ServiceException) e).getErrorCode())
                .isEqualTo(ServiceErrorCode.DIRECT_UPLOAD_CHECKSUM_MISMATCH);
    }

    @Test
    @DisplayName("尺寸失配（声明 12MB 实际 11MB）→ SIZE_MISMATCH")
    void sizeMismatchRejected() throws Exception {
        when(store.load(SESSION_ID)).thenReturn(multipartSession("ACTIVE"));
        when(store.acquireForCommit(anyString(), any())).thenReturn(DirectUploadSessionStore.CommitAcquire.ACQUIRED);
        stubVerificationPass(BIG - CHUNK, CHECKSUM, "md5-x", "etag-x");

        assertThatThrownBy(() -> service.commit(SESSION_ID, fullParts(), null))
                .isInstanceOf(ServiceException.class)
                .extracting(e -> ((ServiceException) e).getErrorCode())
                .isEqualTo(ServiceErrorCode.DIRECT_UPLOAD_SIZE_MISMATCH);
    }

    @Test
    @DisplayName("分片缺失（只回传 2/3 片）→ PARTS_INCOMPLETE")
    void partsIncompleteRejected() {
        when(store.load(SESSION_ID)).thenReturn(multipartSession("ACTIVE"));
        when(store.acquireForCommit(anyString(), any())).thenReturn(DirectUploadSessionStore.CommitAcquire.ACQUIRED);

        assertThatThrownBy(() -> service.commit(SESSION_ID, new DirectUploadCommitRequest(List.of(
                new DirectUploadCommitRequest.PartDeclaration(1, "e1", CHUNK),
                new DirectUploadCommitRequest.PartDeclaration(2, "e2", CHUNK))), null))
                .isInstanceOf(ServiceException.class)
                .extracting(e -> ((ServiceException) e).getErrorCode())
                .isEqualTo(ServiceErrorCode.DIRECT_UPLOAD_PARTS_INCOMPLETE);
    }

    @Test
    @DisplayName("Complete 遇 NoSuchUpload → UPLOAD_GONE + 会话终结（防续传死循环）")
    void uploadGoneTerminatesSession() {
        when(store.load(SESSION_ID)).thenReturn(multipartSession("ACTIVE"));
        when(store.acquireForCommit(anyString(), any())).thenReturn(DirectUploadSessionStore.CommitAcquire.ACQUIRED);
        org.mockito.Mockito.doThrow(new UploadGoneException("分片上传会话已失效", null))
                .when(gateway).completeMultipartUpload(anyString(), anyString(), anyString(), any());

        assertThatThrownBy(() -> service.commit(SESSION_ID, fullParts(), null))
                .isInstanceOf(UploadGoneException.class);
        verify(store).markAborted(SESSION_ID);
        verify(store).cleanup(SESSION_ID, USER_ID, CHECKSUM);
        verify(store).unregisterMpu(BUCKET, OBJECT_KEY, UPLOAD_ID);
    }

    // ==================== H1 接管三分支回归 ====================

    @Test
    @DisplayName("H1(a) 接管 + pending 存在：跳过 Complete（uploadId 已消亡），直接复核续走")
    void takeoverPendingExistsSkipsComplete() throws Exception {
        when(store.load(SESSION_ID)).thenReturn(multipartSession("COMMITTING"));
        when(store.acquireForCommit(anyString(), any())).thenReturn(DirectUploadSessionStore.CommitAcquire.TAKEOVER);
        stubVerificationPass(BIG, CHECKSUM, "md5-x", "etag-mpu-3");
        RagDocument doc = new RagDocument();
        doc.setId(200L);
        doc.setStatus(EtlStatus.UPLOADED);
        when(persistence.insert(any())).thenReturn(doc);

        DocumentUploadResponse resp = service.commit(SESSION_ID, fullParts(), null);

        assertThat(resp.id()).isEqualTo(200L);
        verify(gateway, never()).completeMultipartUpload(anyString(), anyString(), anyString(), any());
        verify(gateway).copyObjectIfMatch(eq(BUCKET), eq(OBJECT_KEY), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("H1(b) 接管 + pending 缺失：从 Complete 幂等重试后续走")
    void takeoverPendingMissingRetriesComplete() throws Exception {
        when(store.load(SESSION_ID)).thenReturn(multipartSession("COMMITTING"));
        when(store.acquireForCommit(anyString(), any())).thenReturn(DirectUploadSessionStore.CommitAcquire.TAKEOVER);
        // 第一次 statObject（接管探测）pending 缺失；Complete 后复核阶段的 stat 通过
        when(gateway.statObject(BUCKET, OBJECT_KEY))
                .thenThrow(new UploadGoneException("直传对象不存在", null))
                .thenReturn(new S3MultipartGateway.PendingObjectStat("etag-mpu-3", BIG, PDF));
        when(gateway.computeDigests(BUCKET, OBJECT_KEY))
                .thenReturn(new S3MultipartGateway.ObjectDigests(CHECKSUM, "md5-x"));
        when(gateway.getObject(BUCKET, OBJECT_KEY)).thenReturn(new ByteArrayInputStream(new byte[0]));
        when(documentValidator.validate(any(), anyString(), anyLong()))
                .thenReturn(new ValidatedDocumentFile("file.pdf", BIG, PDF));
        RagDocument doc = new RagDocument();
        doc.setId(201L);
        doc.setStatus(EtlStatus.UPLOADED);
        when(persistence.insert(any())).thenReturn(doc);

        DocumentUploadResponse resp = service.commit(SESSION_ID, fullParts(), null);

        assertThat(resp.id()).isEqualTo(201L);
        verify(gateway).completeMultipartUpload(eq(BUCKET), eq(OBJECT_KEY), eq(UPLOAD_ID), any());
    }

    @Test
    @DisplayName("H1(c) 接管 + 双亡（Complete 仍 NoSuchUpload）→ UPLOAD_GONE")
    void takeoverBothDeadRejected() {
        when(store.load(SESSION_ID)).thenReturn(multipartSession("COMMITTING"));
        when(store.acquireForCommit(anyString(), any())).thenReturn(DirectUploadSessionStore.CommitAcquire.TAKEOVER);
        when(gateway.statObject(BUCKET, OBJECT_KEY))
                .thenThrow(new UploadGoneException("直传对象不存在", null));
        org.mockito.Mockito.doThrow(new UploadGoneException("分片上传会话已失效", null))
                .when(gateway).completeMultipartUpload(anyString(), anyString(), anyString(), any());

        assertThatThrownBy(() -> service.commit(SESSION_ID, fullParts(), null))
                .isInstanceOf(UploadGoneException.class);
        verify(store).cleanup(SESSION_ID, USER_ID, CHECKSUM);
    }

    // ==================== 团队分支 ====================

    @Test
    @DisplayName("团队 commit 普通成员：PENDING_APPROVAL + 审批记录 + 不触发 ETL")
    void teamCommitNeedsApproval() throws Exception {
        Map<String, String> session = multipartSession("ACTIVE");
        session.put("teamId", String.valueOf(TEAM_ID));
        when(store.load(SESSION_ID)).thenReturn(session);
        when(store.acquireForCommit(anyString(), any())).thenReturn(DirectUploadSessionStore.CommitAcquire.ACQUIRED);
        stubVerificationPass(BIG, CHECKSUM, "md5-x", "etag-mpu-3");
        when(teamAccessGate.verifyAccess(TEAM_ID, USER_ID)).thenReturn(new TeamAccessGate.TeamAccess(false));
        RagDocument doc = new RagDocument();
        doc.setId(300L);
        doc.setStatus(EtlStatus.PENDING_APPROVAL);
        when(persistence.insert(any())).thenReturn(doc);

        DocumentUploadResponse resp = service.commit(SESSION_ID, fullParts(), TEAM_ID);

        assertThat(resp.id()).isEqualTo(300L);
        assertThat(resp.status()).isEqualTo(EtlStatus.PENDING_APPROVAL);
        verify(teamAccessGate).verifyUploadQuota(TEAM_ID, USER_ID, BIG); // commit 复核额度
        verify(teamAccessGate).createUploadApproval(TEAM_ID, 300L, USER_ID);
        verify(persistence, never()).dispatchEtl(anyLong(), anyString(), anyString(), anyString(), anyString(), anyLong(), any(), any());
        verify(store).markCommitted(SESSION_ID, 300L, "PENDING_APPROVAL");
    }

    @Test
    @DisplayName("团队 commit 管理员：PROCESSING + 直接 ETL")
    void teamCommitAutoApproved() throws Exception {
        Map<String, String> session = multipartSession("ACTIVE");
        session.put("teamId", String.valueOf(TEAM_ID));
        when(store.load(SESSION_ID)).thenReturn(session);
        when(store.acquireForCommit(anyString(), any())).thenReturn(DirectUploadSessionStore.CommitAcquire.ACQUIRED);
        stubVerificationPass(BIG, CHECKSUM, "md5-x", "etag-mpu-3");
        when(teamAccessGate.verifyAccess(TEAM_ID, USER_ID)).thenReturn(new TeamAccessGate.TeamAccess(true));
        RagDocument doc = new RagDocument();
        doc.setId(301L);
        doc.setStatus(EtlStatus.PROCESSING);
        when(persistence.insert(any())).thenReturn(doc);

        DocumentUploadResponse resp = service.commit(SESSION_ID, fullParts(), TEAM_ID);

        assertThat(resp.status()).isEqualTo(EtlStatus.PROCESSING);
        verify(persistence).dispatchEtl(eq(301L), anyString(), anyString(), anyString(), anyString(), anyLong(), eq(USER_ID), eq(TEAM_ID));
        verify(teamAccessGate, never()).createUploadApproval(anyLong(), anyLong(), anyLong());
    }

    @Test
    @DisplayName("copy 成功但 persist 失败：回滚删除最终对象 + 回退 ACTIVE")
    void persistFailureRollsBackFinalObject() throws Exception {
        when(store.load(SESSION_ID)).thenReturn(multipartSession("ACTIVE"));
        when(store.acquireForCommit(anyString(), any())).thenReturn(DirectUploadSessionStore.CommitAcquire.ACQUIRED);
        stubVerificationPass(BIG, CHECKSUM, "md5-x", "etag-mpu-3");
        when(persistence.insert(any())).thenThrow(new RemoteException(com.smart.rag.infrastructure.exception.errorcode.RemoteErrorCode.FILE_STORAGE_UNAVAILABLE));

        assertThatThrownBy(() -> service.commit(SESSION_ID, fullParts(), null))
                .isInstanceOf(RuntimeException.class);
        // 最终 key 对象回滚删除（R1-H3 语义）
        verify(gateway).removeObjectQuietly(eq(BUCKET), org.mockito.ArgumentMatchers.startsWith("documents/"));
        verify(store).rollbackToActive(SESSION_ID);
    }

    // ==================== abort ====================

    @Test
    @DisplayName("abort：Abort（幂等）+ 删 pending + 会话/反向索引清理")
    void abortCleansUp() {
        when(store.load(SESSION_ID)).thenReturn(multipartSession("ACTIVE"));

        service.abort(SESSION_ID, null);

        verify(gateway).abortMultipartUploadQuietly(BUCKET, OBJECT_KEY, UPLOAD_ID);
        verify(store).unregisterMpu(BUCKET, OBJECT_KEY, UPLOAD_ID);
        verify(gateway).removeObjectQuietly(BUCKET, OBJECT_KEY);
        verify(store).cleanup(SESSION_ID, USER_ID, CHECKSUM);
    }
}
