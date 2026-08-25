package com.smart.rag.rag.upload;

import com.smart.rag.rag.upload.s3.S3MultipartGateway;
import io.minio.MinioClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DirectUploadOrphanCleaner：pending 对象 sessionId 提取（O(1) 判定前提）+
 * MPU 出生登记簿驱动的泄漏回收（ListMultipartUploads 不可用的替代方案回归）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("DirectUploadOrphanCleaner — sessionId 提取与 MPU 泄漏回收")
class DirectUploadOrphanCleanerTest {

    private static final String SESSION_ID = "0b1c2d3e-4f5a-6789-abcd-ef0123456789";

    @Mock private MinioClient minioClient;
    @Mock private S3MultipartGateway gateway;
    @Mock private BucketResolver bucketResolver;
    @Mock private DirectUploadSessionStore store;

    private DirectUploadOrphanCleaner cleaner;

    @BeforeEach
    void setUp() throws Exception {
        // 无托管 bucket → pending 扫描线空转，聚焦 MPU 扫描线
        when(minioClient.listBuckets()).thenReturn(List.of());
        cleaner = new DirectUploadOrphanCleaner(store, minioClient, gateway, bucketResolver, null);
    }

    @Test
    @DisplayName("extractSessionId：标准 pending key 提取第三段 sessionId")
    void extractSessionIdFromPendingKey() {
        var id = DirectUploadOrphanCleaner.extractSessionId(
                "uploads/pending/7/" + SESSION_ID + "/abcd_file.pdf");
        assertThat(id).hasValue(SESSION_ID);
    }

    @Test
    @DisplayName("extractSessionId：非 pending 结构（chunks/、documents/、畸形段数）→ empty 保守视为活跃")
    void extractSessionIdRejectsUnknownShapes() {
        assertThat(DirectUploadOrphanCleaner.extractSessionId("chunks/7/" + SESSION_ID + "/part-1")).isEmpty();
        assertThat(DirectUploadOrphanCleaner.extractSessionId("documents/7/abcd_file.pdf")).isEmpty();
        assertThat(DirectUploadOrphanCleaner.extractSessionId("uploads/pending/7/not-a-uuid/file.pdf")).isEmpty();
        assertThat(DirectUploadOrphanCleaner.extractSessionId("uploads/pending/" + SESSION_ID + "/file.pdf")).isEmpty();
    }

    @Test
    @DisplayName("MPU 泄漏回收：登记簿超 24h 条目主动 abort（幂等）+ 注销")
    void abortsLeakedMpusFromRegistry() {
        DirectUploadSessionStore.MpuEntry leaked =
                new DirectUploadSessionStore.MpuEntry("rag-documents", "uploads/pending/7/x/y.pdf", "upload-1");
        when(store.listMpusOlderThan(org.mockito.ArgumentMatchers.anyLong())).thenReturn(List.of(leaked));

        cleaner.cleanOrphans();

        verify(gateway).abortMultipartUploadQuietly("rag-documents", "uploads/pending/7/x/y.pdf", "upload-1");
        verify(store).unregisterMpu("rag-documents", "uploads/pending/7/x/y.pdf", "upload-1");
    }

    @Test
    @DisplayName("登记簿无超龄条目：不触发 abort")
    void noLeakNoAbort() {
        when(store.listMpusOlderThan(org.mockito.ArgumentMatchers.anyLong())).thenReturn(List.of());

        cleaner.cleanOrphans();

        verify(gateway, never()).abortMultipartUploadQuietly(anyString(), anyString(), anyString());
        verify(store, never()).unregisterMpu(anyString(), anyString(), anyString());
    }
}
