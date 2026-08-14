package com.smart.rag.rag.etl;

import com.smart.rag.rag.entity.RagDocument;
import com.smart.rag.rag.event.DocumentStatusChangedEvent;
import com.smart.rag.rag.event.EtlFailedEvent;
import com.smart.rag.rag.mapper.RagDocumentMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * EtlStatusManager 单元测试。
 * <p>
 * 验证状态转换、事务调用、失败容错。
 */
@ExtendWith(MockitoExtension.class)
class EtlStatusManagerTest {

    @Mock
    private RagDocumentMapper ragDocumentMapper;

    @Mock
    private TransactionTemplate transactionTemplate;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private EtlStatusManager statusManager;

    @BeforeEach
    void setUp() {
        statusManager = new EtlStatusManager(ragDocumentMapper, transactionTemplate, eventPublisher);
        // 模拟 TransactionTemplate：立即执行回调
        lenient().doAnswer(invocation -> {
            java.util.function.Consumer<org.springframework.transaction.TransactionStatus> consumer = invocation.getArgument(0);
            consumer.accept(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
    }

    @Nested
    @DisplayName("updateStatus")
    class UpdateStatus {

        @Test
        @DisplayName("更新状态为 PROCESSING")
        void updateStatus_processing() {
            statusManager.updateStatus(1L, EtlStatus.PROCESSING);

            ArgumentCaptor<RagDocument> captor = ArgumentCaptor.forClass(RagDocument.class);
            verify(ragDocumentMapper).updateById(captor.capture());

            RagDocument doc = captor.getValue();
            assertThat(doc.getId()).isEqualTo(1L);
            assertThat(doc.getStatus()).isEqualTo(EtlStatus.PROCESSING);
            assertThat(doc.getUpdateTime()).isNotNull();
        }

        @Test
        @DisplayName("更新状态为 COMPLETED")
        void updateStatus_completed() {
            statusManager.updateStatus(2L, EtlStatus.COMPLETED);

            ArgumentCaptor<RagDocument> captor = ArgumentCaptor.forClass(RagDocument.class);
            verify(ragDocumentMapper).updateById(captor.capture());
            assertThat(captor.getValue().getStatus()).isEqualTo(EtlStatus.COMPLETED);
        }

        @Test
        @DisplayName("更新状态为 FAILED")
        void updateStatus_failed() {
            statusManager.updateStatus(3L, EtlStatus.FAILED);

            ArgumentCaptor<RagDocument> captor = ArgumentCaptor.forClass(RagDocument.class);
            verify(ragDocumentMapper).updateById(captor.capture());
            assertThat(captor.getValue().getStatus()).isEqualTo(EtlStatus.FAILED);
        }
    }

    @Nested
    @DisplayName("completeDocument")
    class CompleteDocument {

        @Test
        @DisplayName("标记完成时同时更新 chunkCount")
        void completeDocument_setsChunkCount() {
            statusManager.completeDocument(10L, 42);

            ArgumentCaptor<RagDocument> captor = ArgumentCaptor.forClass(RagDocument.class);
            verify(ragDocumentMapper).updateById(captor.capture());

            RagDocument doc = captor.getValue();
            assertThat(doc.getId()).isEqualTo(10L);
            assertThat(doc.getStatus()).isEqualTo(EtlStatus.COMPLETED);
            assertThat(doc.getChunkCount()).isEqualTo(42);
            assertThat(doc.getUpdateTime()).isNotNull();
        }

        @Test
        @DisplayName("chunkCount 为 0 也是合法值")
        void completeDocument_zeroChunks() {
            statusManager.completeDocument(11L, 0);

            ArgumentCaptor<RagDocument> captor = ArgumentCaptor.forClass(RagDocument.class);
            verify(ragDocumentMapper).updateById(captor.capture());
            assertThat(captor.getValue().getChunkCount()).isEqualTo(0);
        }
    }

    @Nested
    @DisplayName("updateChunkCount")
    class UpdateChunkCount {

        @Test
        @DisplayName("只更新分块数量，不改变状态")
        void updateChunkCount() {
            statusManager.updateChunkCount(20L, 15);

            ArgumentCaptor<RagDocument> captor = ArgumentCaptor.forClass(RagDocument.class);
            verify(ragDocumentMapper).updateById(captor.capture());

            RagDocument doc = captor.getValue();
            assertThat(doc.getId()).isEqualTo(20L);
            assertThat(doc.getChunkCount()).isEqualTo(15);
            assertThat(doc.getStatus()).isNull();
        }
    }

    @Nested
    @DisplayName("failDocument")
    class FailDocument {

        @Test
        @DisplayName("文档存在时标记失败并记录错误信息")
        void failDocument_existingDoc() {
            RagDocument existing = new RagDocument();
            existing.setId(30L);
            when(ragDocumentMapper.selectById(30L)).thenReturn(existing);

            Exception error = new RuntimeException("解析失败: 格式错误");
            statusManager.failDocument(30L, error);

            ArgumentCaptor<RagDocument> captor = ArgumentCaptor.forClass(RagDocument.class);
            verify(ragDocumentMapper).updateById(captor.capture());

            RagDocument doc = captor.getValue();
            assertThat(doc.getStatus()).isEqualTo(EtlStatus.FAILED);
            assertThat(doc.getErrorMessage()).contains("解析失败: 格式错误");
            assertThat(doc.getUpdateTime()).isNotNull();
        }

        @Test
        @DisplayName("文档不存在时不会抛异常")
        void failDocument_docNotFound() {
            when(ragDocumentMapper.selectById(999L)).thenReturn(null);

            assertThatCode(() -> statusManager.failDocument(999L, new RuntimeException("err")))
                    .doesNotThrowAnyException();
            verify(ragDocumentMapper, never()).updateById(any(RagDocument.class));
        }

        @Test
        @DisplayName("错误信息超过 2000 字符时截断")
        void failDocument_truncatesLongMessage() {
            RagDocument existing = new RagDocument();
            existing.setId(31L);
            when(ragDocumentMapper.selectById(31L)).thenReturn(existing);

            String longMsg = "x".repeat(3000);
            statusManager.failDocument(31L, new RuntimeException(longMsg));

            ArgumentCaptor<RagDocument> captor = ArgumentCaptor.forClass(RagDocument.class);
            verify(ragDocumentMapper).updateById(captor.capture());
            assertThat(captor.getValue().getErrorMessage()).hasSize(2000);
        }

        @Test
        @DisplayName("事务自身失败时不抛异常（容错）")
        void failDocument_transactionFailure() {
            // 重新配置 transactionTemplate 使其真正执行（触发 doThrow）
            doAnswer(invocation -> {
                java.util.function.Consumer<org.springframework.transaction.TransactionStatus> consumer = invocation.getArgument(0);
                consumer.accept(null);
                return null;
            }).when(transactionTemplate).executeWithoutResult(any());

            RagDocument existing = new RagDocument();
            existing.setId(32L);
            when(ragDocumentMapper.selectById(32L)).thenReturn(existing);
            doThrow(new RuntimeException("DB down")).when(ragDocumentMapper).updateById(any(RagDocument.class));

            assertThatCode(() -> statusManager.failDocument(32L, new RuntimeException("original")))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("null 错误信息不导致 NPE")
        void failDocument_nullMessage() {
            RagDocument existing = new RagDocument();
            existing.setId(33L);
            when(ragDocumentMapper.selectById(33L)).thenReturn(existing);

            statusManager.failDocument(33L, new RuntimeException((String) null));

            ArgumentCaptor<RagDocument> captor = ArgumentCaptor.forClass(RagDocument.class);
            verify(ragDocumentMapper).updateById(captor.capture());
            assertThat(captor.getValue().getErrorMessage()).isNull();
        }

        @Test
        @DisplayName("落库成功后发布 EtlFailedEvent（含 documentId 与错误信息）")
        void failDocument_publishesEvent() {
            RagDocument existing = new RagDocument();
            existing.setId(34L);
            when(ragDocumentMapper.selectById(34L)).thenReturn(existing);

            statusManager.failDocument(34L, new RuntimeException("boom"));

            ArgumentCaptor<EtlFailedEvent> captor = ArgumentCaptor.forClass(EtlFailedEvent.class);
            verify(eventPublisher).publishEvent(captor.capture());
            EtlFailedEvent ev = captor.getValue();
            assertThat(ev.documentId()).isEqualTo(34L);
            assertThat(ev.errorMessage()).contains("boom");
        }

        @Test
        @DisplayName("事务自身失败时不发布 EtlFailedEvent（保持 DB-事件一致）")
        void failDocument_transactionFailure_noEvent() {
            // 重新配置 transactionTemplate 使其真正执行（触发 updateById doThrow）
            doAnswer(invocation -> {
                java.util.function.Consumer<org.springframework.transaction.TransactionStatus> consumer = invocation.getArgument(0);
                consumer.accept(null);
                return null;
            }).when(transactionTemplate).executeWithoutResult(any());

            RagDocument existing = new RagDocument();
            existing.setId(35L);
            when(ragDocumentMapper.selectById(35L)).thenReturn(existing);
            doThrow(new RuntimeException("DB down")).when(ragDocumentMapper).updateById(any(RagDocument.class));

            assertThatCode(() -> statusManager.failDocument(35L, new RuntimeException("original")))
                    .doesNotThrowAnyException();
            verify(eventPublisher, never()).publishEvent(any());
        }
    }

    @Nested
    @DisplayName("markVectorFailed")
    class MarkVectorFailed {

        @Test
        @DisplayName("标记向量化失败并记录错误前缀")
        void markVectorFailed_existingDoc() {
            RagDocument existing = new RagDocument();
            existing.setId(40L);
            when(ragDocumentMapper.selectById(40L)).thenReturn(existing);

            statusManager.markVectorFailed(40L, new RuntimeException("embedding timeout"));

            ArgumentCaptor<RagDocument> captor = ArgumentCaptor.forClass(RagDocument.class);
            verify(ragDocumentMapper).updateById(captor.capture());

            RagDocument doc = captor.getValue();
            assertThat(doc.getStatus()).isEqualTo(EtlStatus.VECTOR_FAILED);
            assertThat(doc.getErrorMessage()).startsWith("Async vectorize failed:");
            assertThat(doc.getErrorMessage()).contains("embedding timeout");
        }

        @Test
        @DisplayName("文档不存在时安全跳过")
        void markVectorFailed_docNotFound() {
            when(ragDocumentMapper.selectById(999L)).thenReturn(null);

            assertThatCode(() -> statusManager.markVectorFailed(999L, new RuntimeException("err")))
                    .doesNotThrowAnyException();
            verify(ragDocumentMapper, never()).updateById(any(RagDocument.class));
        }
    }

    @Nested
    @DisplayName("DocumentStatusChangedEvent 发布（SSE 推送驱动）")
    class StatusChangedEvent {

        @Test
        @DisplayName("updateStatus 事务后发 DocumentStatusChangedEvent")
        void updateStatus_publishesEvent() {
            RagDocument doc = new RagDocument();
            doc.setId(50L);
            doc.setUserId(7L);
            when(ragDocumentMapper.selectById(50L)).thenReturn(doc);

            statusManager.updateStatus(50L, EtlStatus.PARSING);

            ArgumentCaptor<DocumentStatusChangedEvent> captor = ArgumentCaptor.forClass(DocumentStatusChangedEvent.class);
            verify(eventPublisher).publishEvent(captor.capture());
            DocumentStatusChangedEvent ev = captor.getValue();
            assertThat(ev.documentId()).isEqualTo(50L);
            assertThat(ev.userId()).isEqualTo(7L);
            assertThat(ev.status()).isEqualTo(EtlStatus.PARSING);
        }

        @Test
        @DisplayName("completeDocument 发 COMPLETED 事件（含 teamId）")
        void completeDocument_publishesEvent() {
            RagDocument doc = new RagDocument();
            doc.setId(51L);
            doc.setUserId(8L);
            doc.setTeamId(2L);
            when(ragDocumentMapper.selectById(51L)).thenReturn(doc);

            statusManager.completeDocument(51L, 5);

            ArgumentCaptor<DocumentStatusChangedEvent> captor = ArgumentCaptor.forClass(DocumentStatusChangedEvent.class);
            verify(eventPublisher).publishEvent(captor.capture());
            DocumentStatusChangedEvent ev = captor.getValue();
            assertThat(ev.status()).isEqualTo(EtlStatus.COMPLETED);
            assertThat(ev.teamId()).isEqualTo(2L);
        }

        @Test
        @DisplayName("markVectorFailed 发 VECTOR_FAILED 事件")
        void markVectorFailed_publishesEvent() {
            RagDocument doc = new RagDocument();
            doc.setId(52L);
            doc.setUserId(9L);
            when(ragDocumentMapper.selectById(52L)).thenReturn(doc);

            statusManager.markVectorFailed(52L, new RuntimeException("timeout"));

            ArgumentCaptor<DocumentStatusChangedEvent> captor = ArgumentCaptor.forClass(DocumentStatusChangedEvent.class);
            verify(eventPublisher).publishEvent(captor.capture());
            assertThat(captor.getValue().status()).isEqualTo(EtlStatus.VECTOR_FAILED);
        }

        @Test
        @DisplayName("文档不存在时不发事件（selectById 返回 null）")
        void noEvent_whenDocNotFound() {
            when(ragDocumentMapper.selectById(60L)).thenReturn(null);

            statusManager.updateStatus(60L, EtlStatus.PARSING);

            verify(eventPublisher, never()).publishEvent(any(DocumentStatusChangedEvent.class));
        }

        @Test
        @DisplayName("failDocument 同时发 EtlFailedEvent 和 DocumentStatusChangedEvent")
        void failDocument_publishesBothEvents() {
            RagDocument doc = new RagDocument();
            doc.setId(53L);
            doc.setUserId(10L);
            when(ragDocumentMapper.selectById(53L)).thenReturn(doc);

            statusManager.failDocument(53L, new RuntimeException("boom"));

            verify(eventPublisher).publishEvent(any(EtlFailedEvent.class));
            verify(eventPublisher).publishEvent(any(DocumentStatusChangedEvent.class));
        }
    }
}
