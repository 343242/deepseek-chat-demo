package com.demo.chat.rag.etl;

import com.demo.chat.rag.entity.RagDocument;
import com.demo.chat.rag.mapper.RagDocumentMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
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

    private EtlStatusManager statusManager;

    @BeforeEach
    void setUp() {
        statusManager = new EtlStatusManager(ragDocumentMapper, transactionTemplate);
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
}
