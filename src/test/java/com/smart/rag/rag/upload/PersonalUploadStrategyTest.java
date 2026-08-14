package com.smart.rag.rag.upload;

import com.smart.rag.rag.dto.DocumentUploadResponse;
import com.smart.rag.rag.entity.RagDocument;
import com.smart.rag.rag.etl.EtlStatus;
import com.smart.rag.rag.mapper.RagDocumentMapper;
import com.smart.rag.rag.service.DocumentDedupService;
import com.smart.rag.rag.service.EtlDispatchService;
import com.smart.rag.rag.service.FileStorageService;
import com.smart.rag.rag.service.impl.DocumentValidator;
import com.smart.rag.rag.service.impl.ValidatedDocumentFile;
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
import org.springframework.core.io.ByteArrayResource;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

/**
 * W4 R1-H3: {@link PersonalUploadStrategy#uploadBatch} 单文件失败不中断整批，
 * 已 persist 的文件仍被 dispatch，失败文件返回 FAILED 并回滚 MinIO 对象，
 * 避免产生 UPLOADED 死状态和无 DB 记录的孤儿对象。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("W4 R1-H3: uploadBatch 部分失败原子性")
class PersonalUploadStrategyTest {

    private static final Long USER_ID = 7L;
    private static final String BUCKET = "test-bucket";

    @Mock private FileStorageService fileStorageService;
    @Mock private EtlDispatchService etlDispatchService;
    @Mock private RagDocumentMapper ragDocumentMapper;
    @Mock private BucketResolver bucketResolver;
    @Mock private DocumentValidator documentValidator;
    @Mock private ApplicationEventPublisher eventPublisher;

    private PersonalUploadStrategy strategy;

    @BeforeEach
    void setUp() {
        when(bucketResolver.resolve(isNull())).thenReturn(BUCKET);
        // 校验器返回服务端规范 MIME（模拟 Tika 探测结果，与客户端声明无关）
        when(documentValidator.validate(any(MultipartFile.class))).thenAnswer(inv -> {
            MultipartFile file = inv.getArgument(0);
            return new ValidatedDocumentFile(file.getOriginalFilename(), file.getSize(), "application/pdf");
        });
        strategy = new PersonalUploadStrategy(fileStorageService, etlDispatchService,
                ragDocumentMapper, bucketResolver, documentValidator, eventPublisher, null);
    }

    private MultipartFile mockFile(String name) {
        MultipartFile file = mock(MultipartFile.class);
        when(file.getOriginalFilename()).thenReturn(name);
        when(file.getContentType()).thenReturn("application/pdf");
        when(file.getSize()).thenReturn(10L);
        when(file.getResource()).thenReturn(new ByteArrayResource(new byte[]{1, 2, 3}));
        try {
            when(file.getInputStream()).thenReturn(new ByteArrayInputStream(new byte[]{1, 2, 3}));
        } catch (Exception ignored) {
            // MultipartFile#getInputStream 不抛 checked 异常
        }
        return file;
    }

    @Test
    @DisplayName("文件 N persist 失败：1..N-1 仍被 dispatch，失败文件返回 FAILED 并回滚 MinIO")
    void partialFailureDispatchesSuccessfulFiles() {
        // 第 1 个文件 insert 成功并回填 id；第 2 个文件 insert 抛异常（模拟 DB 故障）
        when(ragDocumentMapper.insert(any(RagDocument.class)))
                .thenAnswer(inv -> {
                    inv.getArgument(0, RagDocument.class).setId(100L);
                    return 1;
                })
                .thenThrow(new RuntimeException("db down"));

        MultipartFile f1 = mockFile("a.pdf");
        MultipartFile f2 = mockFile("b.pdf");

        List<DocumentUploadResponse> result = strategy.uploadBatch(List.of(f1, f2), null, null, USER_ID);

        // per-file 结果：a 成功、b 失败
        assertThat(result).hasSize(2);
        assertThat(result.get(0)).satisfies(r -> {
            assertThat(r.id()).isEqualTo(100L);
            assertThat(r.fileName()).isEqualTo("a.pdf");
            assertThat(r.status()).isEqualTo(EtlStatus.PROCESSING);
        });
        assertThat(result.get(1)).satisfies(r -> {
            assertThat(r.id()).isNull();
            assertThat(r.fileName()).isEqualTo("b.pdf");
            assertThat(r.status()).isEqualTo(EtlStatus.FAILED);
        });

        // 仅成功的候选被 dispatch（b 未 persist，不应进入 dispatch）
        @SuppressWarnings("rawtypes")
        ArgumentCaptor<List> captor = ArgumentCaptor.forClass(List.class);
        verify(etlDispatchService).dispatch(captor.capture());
        assertThat(captor.getValue()).hasSize(1);

        // b 已上传到 MinIO（persist 前失败）→ 回滚删除，避免孤儿对象
        verify(fileStorageService).delete(eq(BUCKET), contains("b.pdf"));
    }

    @Test
    @DisplayName("全部成功：所有文件 dispatch，无回滚")
    void allSuccessDispatchesAllNoRollback() {
        when(ragDocumentMapper.insert(any(RagDocument.class))).thenAnswer(inv -> {
            inv.getArgument(0, RagDocument.class).setId(200L);
            return 1;
        });

        MultipartFile f1 = mockFile("a.pdf");

        List<DocumentUploadResponse> result = strategy.uploadBatch(List.of(f1), null, null, USER_ID);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).status()).isEqualTo(EtlStatus.PROCESSING);
        verify(etlDispatchService).dispatch(org.mockito.ArgumentMatchers.anyList());
        verify(fileStorageService, never()).delete(anyString(), anyString());
    }

    @Test
    @DisplayName("persist 成功但 post-persist 步骤失败：仍 dispatch，不回滚 MinIO")
    void postPersistFailureStillDispatchesNoRollback() {
        when(ragDocumentMapper.insert(any(RagDocument.class))).thenAnswer(inv -> {
            inv.getArgument(0, RagDocument.class).setId(300L);
            return 1;
        });
        // dedup 抛异常 —— 模拟 persist 之后的低概率失败（add 返回 void，用 doThrow）
        DocumentDedupService dedup = mock(DocumentDedupService.class);
        doThrow(new RuntimeException("dedup store down")).when(dedup).add(anyString());
        PersonalUploadStrategy s = new PersonalUploadStrategy(fileStorageService, etlDispatchService,
                ragDocumentMapper, bucketResolver, documentValidator, eventPublisher, dedup);

        MultipartFile f1 = mockFile("c.pdf");
        List<DocumentUploadResponse> result = s.uploadBatch(List.of(f1), null, null, USER_ID);

        // 已 persist → 已登记 dispatch 候选，仍被 dispatch（避免 UPLOADED 死状态）
        @SuppressWarnings("rawtypes")
        ArgumentCaptor<List> captor = ArgumentCaptor.forClass(List.class);
        verify(etlDispatchService).dispatch(captor.capture());
        assertThat(captor.getValue()).hasSize(1);
        // 已 persist → 不回滚 MinIO（对象保留，ETL 可处理）
        verify(fileStorageService, never()).delete(anyString(), anyString());
        // 响应标记失败但带文档 id（客户端知道文档已落库）
        assertThat(result).hasSize(1);
        assertThat(result.get(0).id()).isEqualTo(300L);
        assertThat(result.get(0).status()).isEqualTo(EtlStatus.FAILED);
    }
}
