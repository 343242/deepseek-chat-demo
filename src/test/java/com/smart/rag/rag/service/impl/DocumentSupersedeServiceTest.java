package com.smart.rag.rag.service.impl;

import com.smart.rag.rag.entity.RagDocument;
import com.smart.rag.rag.etl.EtlStatus;
import com.smart.rag.rag.etl.Loader;
import com.smart.rag.rag.event.DocumentCreatedEvent;
import com.smart.rag.rag.event.DocumentDeletedEvent;
import com.smart.rag.rag.event.EtlCompletedEvent;
import com.smart.rag.rag.event.EtlFailedEvent;
import com.smart.rag.rag.mapper.RagDocumentMapper;
import com.smart.rag.rag.mapper.VectorStoreMapper;
import com.smart.rag.rag.service.FileStorageService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * DocumentSupersedeService 单元测试
 * <p>
 * 覆盖文档版本替换的完整流程：文档创建 → 版本链接 → ETL 完成后替换 → 部分失败容错。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DocumentSupersedeService 单元测试")
class DocumentSupersedeServiceTest {

    @Mock
    private RagDocumentMapper ragDocumentMapper;

    @Mock
    private VectorStoreMapper vectorStoreMapper;

    @Mock
    private Loader vectorStoreLoader;

    @Mock
    private FileStorageService fileStorageService;

    @Mock
    private TransactionTemplate transactionTemplate;

    @InjectMocks
    private DocumentSupersedeService service;

    @BeforeEach
    void setUp() {
        // 模拟 TransactionTemplate：立即执行 Consumer 回调
        lenient().doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Consumer<TransactionStatus> consumer = invocation.getArgument(0);
            consumer.accept(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
    }

    // ==================== onDocumentCreated ====================

    @Nested
    @DisplayName("onDocumentCreated — 文档创建版本关系")
    class OnDocumentCreatedTests {

        @Test
        @DisplayName("assignNewGroupId: replaceDocumentId=null 时分配新 groupId")
        void assignNewGroupId() {
            DocumentCreatedEvent event = new DocumentCreatedEvent(100L, null, 1L, null);

            service.onDocumentCreated(event);

            verify(ragDocumentMapper).updateGroupId(eq(100L), anyString());
            verify(ragDocumentMapper, never()).selectById(anyLong());
            verify(ragDocumentMapper, never()).updateGroupIdAndVersion(anyLong(), anyString(), anyInt());
        }

        @Test
        @DisplayName("linkVersion: replaceDocumentId 指向有效旧文档时建立版本关系")
        void linkVersion() {
            RagDocument oldDoc = buildDoc(50L, "group-abc", 3, 1L, null);
            when(ragDocumentMapper.selectById(50L)).thenReturn(oldDoc);

            DocumentCreatedEvent event = new DocumentCreatedEvent(100L, 50L, 1L, null);

            service.onDocumentCreated(event);

            verify(ragDocumentMapper).updateGroupIdAndVersion(eq(100L), eq("group-abc"), eq(4));
            verify(ragDocumentMapper).updateSupersededByOnly(50L, 100L);
            // CAS not needed because oldDoc already has groupId
            verify(ragDocumentMapper, never()).updateGroupIdCas(anyLong(), anyString());
        }

        @Test
        @DisplayName("linkVersion_oldDocWithoutGroup: 旧文档无 groupId 时通过 CAS 分配")
        void linkVersion_oldDocWithoutGroup() {
            RagDocument oldDoc = buildDoc(50L, null, 1, 1L, null);
            when(ragDocumentMapper.selectById(50L)).thenReturn(oldDoc);
            when(ragDocumentMapper.updateGroupIdCas(eq(50L), anyString())).thenReturn(1);

            DocumentCreatedEvent event = new DocumentCreatedEvent(100L, 50L, 1L, null);

            service.onDocumentCreated(event);

            verify(ragDocumentMapper).updateGroupIdCas(eq(50L), anyString());
            verify(ragDocumentMapper).updateGroupIdAndVersion(eq(100L), anyString(), eq(2));
            verify(ragDocumentMapper).updateSupersededByOnly(50L, 100L);
        }

        @Test
        @DisplayName("linkVersion_versionConflict: 唯一约束冲突，重试成功")
        void linkVersion_versionConflict() {
            RagDocument oldDoc1 = buildDoc(50L, "group-abc", 1, 1L, null);
            RagDocument oldDoc2 = buildDoc(50L, "group-abc", 2, 1L, null); // retry 时 version 已+1
            when(ragDocumentMapper.selectById(50L)).thenReturn(oldDoc1, oldDoc2);

            // 第一次 updateGroupIdAndVersion 抛 DuplicateKeyException，第二次成功返回 1
            doThrow(new DuplicateKeyException("duplicate key"))
                    .doReturn(1)
                    .when(ragDocumentMapper).updateGroupIdAndVersion(eq(100L), anyString(), anyInt());

            DocumentCreatedEvent event = new DocumentCreatedEvent(100L, 50L, 1L, null);

            service.onDocumentCreated(event);

            // 验证重试调用了两次 updateGroupIdAndVersion（第一次冲突，第二次成功）
            verify(ragDocumentMapper, times(2)).updateGroupIdAndVersion(eq(100L), anyString(), anyInt());
        }

        @Test
        @DisplayName("linkVersion_retryExhausted: 重试 3 次仍冲突，降级为新文档")
        void linkVersion_retryExhausted() {
            RagDocument oldDoc = buildDoc(50L, "group-abc", 1, 1L, null);
            // 每次重试 selectById 都返回同一个文档
            when(ragDocumentMapper.selectById(50L)).thenReturn(oldDoc);

            // 所有 updateGroupIdAndVersion 都抛异常
            doThrow(new DuplicateKeyException("duplicate key"))
                    .when(ragDocumentMapper).updateGroupIdAndVersion(eq(100L), anyString(), anyInt());

            DocumentCreatedEvent event = new DocumentCreatedEvent(100L, 50L, 1L, null);

            service.onDocumentCreated(event);

            // 验证重试了 3 次（updateGroupIdAndVersion 被调用了 3 次）
            verify(ragDocumentMapper, times(3)).updateGroupIdAndVersion(eq(100L), anyString(), anyInt());
            // selectById 调用次数：1 (onDocumentCreated 中) + 3 (retry 循环中) = 4
            verify(ragDocumentMapper, times(4)).selectById(50L);
            // 降级：分配新 groupId
            verify(ragDocumentMapper).updateGroupId(eq(100L), anyString());
        }

        @Test
        @DisplayName("linkVersion_targetNotFound: 替换目标不存在，降级为新文档")
        void linkVersion_targetNotFound() {
            when(ragDocumentMapper.selectById(50L)).thenReturn(null);

            DocumentCreatedEvent event = new DocumentCreatedEvent(100L, 50L, 1L, null);

            service.onDocumentCreated(event);

            verify(ragDocumentMapper).updateGroupId(eq(100L), anyString());
            verify(ragDocumentMapper, never()).updateGroupIdAndVersion(anyLong(), anyString(), anyInt());
        }

        @Test
        @DisplayName("linkVersion_targetDeleted: 替换目标已软删除，降级为新文档")
        void linkVersion_targetDeleted() {
            RagDocument oldDoc = buildDoc(50L, "group-abc", 1, 1L, null);
            oldDoc.setDeleted(1);
            when(ragDocumentMapper.selectById(50L)).thenReturn(oldDoc);

            DocumentCreatedEvent event = new DocumentCreatedEvent(100L, 50L, 1L, null);

            service.onDocumentCreated(event);

            verify(ragDocumentMapper).updateGroupId(eq(100L), anyString());
            verify(ragDocumentMapper, never()).updateGroupIdAndVersion(anyLong(), anyString(), anyInt());
        }

        @Test
        @DisplayName("linkVersion_targetSuperseded: 替换目标已被替代，降级为新文档")
        void linkVersion_targetSuperseded() {
            RagDocument oldDoc = buildDoc(50L, "group-abc", 2, 1L, null);
            oldDoc.setSupersededBy(200L);
            oldDoc.setStatus(EtlStatus.SUPERSEDED);
            when(ragDocumentMapper.selectById(50L)).thenReturn(oldDoc);

            DocumentCreatedEvent event = new DocumentCreatedEvent(100L, 50L, 1L, null);

            service.onDocumentCreated(event);

            verify(ragDocumentMapper).updateGroupId(eq(100L), anyString());
            verify(ragDocumentMapper, never()).updateGroupIdAndVersion(anyLong(), anyString(), anyInt());
        }

        @Test
        @DisplayName("linkVersion_ownershipMismatch: 个人文档，替换目标不属于当前用户，降级")
        void linkVersion_ownershipMismatch() {
            RagDocument oldDoc = buildDoc(50L, "group-abc", 1, 999L, null); // different user
            when(ragDocumentMapper.selectById(50L)).thenReturn(oldDoc);

            DocumentCreatedEvent event = new DocumentCreatedEvent(100L, 50L, 1L, null);

            service.onDocumentCreated(event);

            verify(ragDocumentMapper).updateGroupId(eq(100L), anyString());
            verify(ragDocumentMapper, never()).updateGroupIdAndVersion(anyLong(), anyString(), anyInt());
        }

        @Test
        @DisplayName("linkVersion_ownershipMismatch_team: 团队文档，替换目标不属于当前团队，降级")
        void linkVersion_ownershipMismatch_team() {
            RagDocument oldDoc = buildDoc(50L, "group-abc", 1, 1L, 888L); // different team
            when(ragDocumentMapper.selectById(50L)).thenReturn(oldDoc);

            DocumentCreatedEvent event = new DocumentCreatedEvent(100L, 50L, 1L, 777L);

            service.onDocumentCreated(event);

            verify(ragDocumentMapper).updateGroupId(eq(100L), anyString());
            verify(ragDocumentMapper, never()).updateGroupIdAndVersion(anyLong(), anyString(), anyInt());
        }

        @Test
        @DisplayName("linkVersion_sameOwner_team: 同团队文档正常链接")
        void linkVersion_sameOwner_team() {
            RagDocument oldDoc = buildDoc(50L, "group-abc", 1, 1L, 777L);
            when(ragDocumentMapper.selectById(50L)).thenReturn(oldDoc);

            DocumentCreatedEvent event = new DocumentCreatedEvent(100L, 50L, 2L, 777L);

            service.onDocumentCreated(event);

            verify(ragDocumentMapper).updateGroupIdAndVersion(eq(100L), eq("group-abc"), eq(2));
        }

        @Test
        @DisplayName("linkVersion_unexpectedError: 异常降级为新文档")
        void linkVersion_unexpectedError() {
            when(ragDocumentMapper.selectById(50L)).thenThrow(new RuntimeException("DB error"));

            DocumentCreatedEvent event = new DocumentCreatedEvent(100L, 50L, 1L, null);

            service.onDocumentCreated(event);

            verify(ragDocumentMapper).updateGroupId(eq(100L), anyString());
        }
    }

    // ==================== onEtlCompleted ====================

    @Nested
    @DisplayName("onEtlCompleted — ETL 完成后执行旧版本替换")
    class OnEtlCompletedTests {

        @Test
        @DisplayName("onEtlCompleted: ETL 完成后执行旧版本替换（SUPERSEDED + 清理）")
        void onEtlCompleted() throws Exception {
            // 预先填充 pendingSupersede
            setPendingSupersede(100L, 50L);

            // 旧文档用于文件清理
            RagDocument oldDoc = buildDoc(50L, "group-abc", 5, 1L, null);
            oldDoc.setBucket("test-bucket");
            oldDoc.setStorageKey("test-key");
            when(ragDocumentMapper.selectById(50L)).thenReturn(oldDoc);

            EtlCompletedEvent event = new EtlCompletedEvent(100L, 1L, null);

            service.onEtlCompleted(event);

            // 验证：标记旧文档为 SUPERSEDED
            verify(ragDocumentMapper).updateSuperseded(50L, 100L);

            // 验证：清理 vectors
            verify(vectorStoreLoader).deleteByDocumentId(50L);
            verify(vectorStoreMapper).deleteFastTrackRows(50L);

            // 验证：清理 MinIO 文件
            verify(fileStorageService).delete("test-bucket", "test-key");

            // 验证 pendingSupersede 已消费
            assertThat(getPendingSupersede()).doesNotContainKey(100L);
        }

        @Test
        @DisplayName("onEtlCompleted_noPendingSupersede: 非增量更新文档，跳过")
        void onEtlCompleted_noPendingSupersede() {
            EtlCompletedEvent event = new EtlCompletedEvent(100L, 1L, null);

            service.onEtlCompleted(event);

            // 验证没有任何 supersede 操作
            verify(ragDocumentMapper, never()).updateSuperseded(anyLong(), anyLong());
            verify(vectorStoreLoader, never()).deleteByDocumentId(anyLong());
            verify(vectorStoreMapper, never()).deleteFastTrackRows(anyLong());
            verify(fileStorageService, never()).delete(anyString(), anyString());
        }

        @Test
        @DisplayName("onEtlCompleted_idempotency: 同一文档两次 onEtlCompleted，第二次跳过")
        void onEtlCompleted_idempotency() throws Exception {
            setPendingSupersede(100L, 50L);
            when(ragDocumentMapper.selectById(50L)).thenReturn(buildDoc(50L, null, 1, 1L, null));

            service.onEtlCompleted(new EtlCompletedEvent(100L, 1L, null));
            // 第二次调用：pendingSupersede 已被消费
            service.onEtlCompleted(new EtlCompletedEvent(100L, 1L, null));

            // supersede 只执行了一次
            verify(ragDocumentMapper, times(1)).updateSuperseded(50L, 100L);
        }
    }

    // ==================== supersedeOldVersion — 部分失败容错 ====================

    @Nested
    @DisplayName("supersedeOldVersion — 部分失败容错")
    class SupersedeOldVersionFailureTests {

        @Test
        @DisplayName("supersedeOldVersion_partialFailure_vectors: 标记成功但 vectors 删除失败，不阻塞流程")
        void supersedeOldVersion_partialFailure_vectors() throws Exception {
            setPendingSupersede(100L, 50L);

            // 旧文档有 bucket 和 storageKey
            RagDocument oldDoc = buildDoc(50L, null, 1, 1L, null);
            oldDoc.setBucket("test-bucket");
            oldDoc.setStorageKey("test-key");
            when(ragDocumentMapper.selectById(50L)).thenReturn(oldDoc);

            // vectorStoreLoader.deleteByDocumentId 抛异常
            doThrow(new RuntimeException("vector store error"))
                    .when(vectorStoreLoader).deleteByDocumentId(50L);

            EtlCompletedEvent event = new EtlCompletedEvent(100L, 1L, null);

            // 不抛出异常
            assertThatCode(() -> service.onEtlCompleted(event)).doesNotThrowAnyException();

            // 验证：状态更新成功
            verify(ragDocumentMapper).updateSuperseded(50L, 100L);
            // 验证：vectors 删除被调用（但失败了）
            verify(vectorStoreLoader).deleteByDocumentId(50L);
            // 验证：后续的 fastTrack 和 file 清理仍然执行
            verify(vectorStoreMapper).deleteFastTrackRows(50L);
            verify(fileStorageService).delete("test-bucket", "test-key");
        }

        @Test
        @DisplayName("supersedeOldVersion_partialFailure_fastTrack: BM25 行删除失败不阻塞文件删除")
        void supersedeOldVersion_partialFailure_fastTrack() throws Exception {
            setPendingSupersede(100L, 50L);

            RagDocument oldDoc = buildDoc(50L, null, 1, 1L, null);
            oldDoc.setBucket("test-bucket");
            oldDoc.setStorageKey("test-key");
            when(ragDocumentMapper.selectById(50L)).thenReturn(oldDoc);

            // vectorStoreMapper.deleteFastTrackRows 抛异常
            doThrow(new RuntimeException("fastTrack error"))
                    .when(vectorStoreMapper).deleteFastTrackRows(50L);

            assertThatCode(() -> service.onEtlCompleted(new EtlCompletedEvent(100L, 1L, null)))
                    .doesNotThrowAnyException();

            verify(vectorStoreLoader).deleteByDocumentId(50L);
            verify(vectorStoreMapper).deleteFastTrackRows(50L);
            verify(fileStorageService).delete("test-bucket", "test-key");
        }

        @Test
        @DisplayName("supersedeOldVersion_partialFailure_file: MinIO 文件删除失败不阻塞")
        void supersedeOldVersion_partialFailure_file() throws Exception {
            setPendingSupersede(100L, 50L);

            RagDocument oldDoc = buildDoc(50L, null, 1, 1L, null);
            oldDoc.setBucket("test-bucket");
            oldDoc.setStorageKey("test-key");
            when(ragDocumentMapper.selectById(50L)).thenReturn(oldDoc);

            doThrow(new RuntimeException("MinIO error"))
                    .when(fileStorageService).delete("test-bucket", "test-key");

            assertThatCode(() -> service.onEtlCompleted(new EtlCompletedEvent(100L, 1L, null)))
                    .doesNotThrowAnyException();

            verify(vectorStoreLoader).deleteByDocumentId(50L);
            verify(vectorStoreMapper).deleteFastTrackRows(50L);
            verify(fileStorageService).delete("test-bucket", "test-key");
        }

        @Test
        @DisplayName("supersedeOldVersion_markFailed: 标记 SUPERSEDED 失败时跳过清理")
        void supersedeOldVersion_markFailed() throws Exception {
            setPendingSupersede(100L, 50L);

            // TransactionTemplate 抛异常
            doThrow(new RuntimeException("tx error"))
                    .when(transactionTemplate).executeWithoutResult(any());

            assertThatCode(() -> service.onEtlCompleted(new EtlCompletedEvent(100L, 1L, null)))
                    .doesNotThrowAnyException();

            // 清理步骤全部被跳过
            verify(vectorStoreLoader, never()).deleteByDocumentId(anyLong());
            verify(vectorStoreMapper, never()).deleteFastTrackRows(anyLong());
            verify(fileStorageService, never()).delete(anyString(), anyString());
        }

        @Test
        @DisplayName("supersedeOldVersion_selectByIdReturnsNull: 旧文档查询返回 null 时跳过文件删除")
        void supersedeOldVersion_selectByIdReturnsNull() throws Exception {
            setPendingSupersede(100L, 50L);

            // selectById 返回 null（文档已被删除）
            when(ragDocumentMapper.selectById(50L)).thenReturn(null);

            assertThatCode(() -> service.onEtlCompleted(new EtlCompletedEvent(100L, 1L, null)))
                    .doesNotThrowAnyException();

            verify(ragDocumentMapper).updateSuperseded(50L, 100L);
            verify(vectorStoreLoader).deleteByDocumentId(50L);
            verify(vectorStoreMapper).deleteFastTrackRows(50L);
            // 文件删除被跳过（oldDoc 为 null）
            verify(fileStorageService, never()).delete(anyString(), anyString());
        }
    }

    // ==================== onEtlFailed / onDocumentDeleted — 失败与删除清理 ====================

    @Nested
    @DisplayName("onEtlFailed / onDocumentDeleted — 清理 pendingSupersede 加速层")
    class CleanupListenerTests {

        @Test
        @DisplayName("onEtlFailed: 清除 pendingSupersede 中该文档的 entry")
        void onEtlFailed_clearsEntry() throws Exception {
            setPendingSupersede(100L, 50L);

            service.onEtlFailed(new EtlFailedEvent(100L, "extract failed"));

            assertThat(getPendingSupersede()).doesNotContainKey(100L);
        }

        @Test
        @DisplayName("onEtlFailed_idempotent: 未命中的 documentId 不报错")
        void onEtlFailed_idempotent() {
            assertThatCode(() -> service.onEtlFailed(new EtlFailedEvent(999L, "err")))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("onDocumentDeleted: 清除 pendingSupersede 中该文档的 entry")
        void onDocumentDeleted_clearsEntry() throws Exception {
            setPendingSupersede(100L, 50L);

            service.onDocumentDeleted(new DocumentDeletedEvent(100L));

            assertThat(getPendingSupersede()).doesNotContainKey(100L);
        }

        @Test
        @DisplayName("onDocumentDeleted_idempotent: 未命中的 documentId 不报错")
        void onDocumentDeleted_idempotent() {
            assertThatCode(() -> service.onDocumentDeleted(new DocumentDeletedEvent(999L)))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("onEtlFailed 后 onEtlCompleted 走 DB 兜底仍能完成替换（重试安全）")
        void onEtlFailed_thenCompletedViaDbFallback() throws Exception {
            setPendingSupersede(100L, 50L);

            // 失败清理 entry
            service.onEtlFailed(new EtlFailedEvent(100L, "err"));
            assertThat(getPendingSupersede()).doesNotContainKey(100L);

            // 重试成功：策略 1 miss → 策略 2 DB 兜底
            RagDocument newDoc = buildDoc(100L, "group-abc", 2, 1L, null);
            RagDocument oldDoc = buildDoc(50L, "group-abc", 1, 1L, null);
            oldDoc.setSupersededBy(100L);
            when(ragDocumentMapper.selectById(100L)).thenReturn(newDoc);
            when(ragDocumentMapper.selectById(50L)).thenReturn(oldDoc);
            when(ragDocumentMapper.selectList(any())).thenReturn(List.of(oldDoc));

            service.onEtlCompleted(new EtlCompletedEvent(100L, 1L, null));

            // 仍执行了替换（重试不因缓存清理而丢失）
            verify(ragDocumentMapper).updateSuperseded(50L, 100L);
        }
    }

    // ==================== 辅助方法 ====================

    private static RagDocument buildDoc(Long id, String groupId, int version, Long userId, Long teamId) {
        RagDocument doc = new RagDocument();
        doc.setId(id);
        doc.setDocumentGroupId(groupId);
        doc.setVersion(version);
        doc.setUserId(userId);
        doc.setTeamId(teamId);
        doc.setStatus(EtlStatus.COMPLETED);
        doc.setDeleted(0);
        return doc;
    }

    @SuppressWarnings("unchecked")
    private void setPendingSupersede(Long newDocId, Long oldDocId) throws Exception {
        var field = DocumentSupersedeService.class.getDeclaredField("pendingSupersede");
        field.setAccessible(true);
        var map = (ConcurrentHashMap<Long, Long>) field.get(service);
        map.put(newDocId, oldDocId);
    }

    @SuppressWarnings("unchecked")
    private ConcurrentHashMap<Long, Long> getPendingSupersede() throws Exception {
        var field = DocumentSupersedeService.class.getDeclaredField("pendingSupersede");
        field.setAccessible(true);
        return (ConcurrentHashMap<Long, Long>) field.get(service);
    }
}
