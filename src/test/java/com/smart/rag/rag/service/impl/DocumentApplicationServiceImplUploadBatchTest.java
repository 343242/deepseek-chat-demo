package com.smart.rag.rag.service.impl;

import com.smart.rag.infrastructure.exception.ClientException;
import com.smart.rag.infrastructure.web.util.SecurityUtils;
import com.smart.rag.rag.config.DocumentProperties;
import com.smart.rag.rag.dto.DocumentUploadResponse;
import com.smart.rag.rag.etl.EtlStatus;
import com.smart.rag.rag.mapper.RagDocumentMapper;
import com.smart.rag.rag.mapper.VectorStoreMapper;
import com.smart.rag.rag.service.DocumentPreviewPolicy;
import com.smart.rag.rag.service.EtlDispatchService;
import com.smart.rag.rag.service.TeamAccessGate;
import com.smart.rag.rag.upload.UploadStrategy;
import com.smart.rag.rag.upload.UploadStrategyRouter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 批量上传入口级约束（app.document.maxBatchFiles=10 / maxBatchTotalSize=200MB）：
 * 校验位于 application service，个人与团队两条策略路由共享同一份限制。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("uploadBatch 入口级限制：最多 10 个文件 / 总量 ≤ 200MB")
class DocumentApplicationServiceImplUploadBatchTest {

    private static final long MB = 1024 * 1024;

    @Mock private EtlDispatchService etlDispatchService;
    @Mock private RagDocumentMapper ragDocumentMapper;
    @Mock private DocumentLifecycleService documentLifecycleService;
    @Mock private UploadStrategyRouter uploadStrategyRouter;
    @Mock private TeamAccessGate teamAccessGate;
    @Mock private VectorStoreMapper vectorStoreMapper;
    @Mock private UploadStrategy uploadStrategy;

    private DocumentApplicationServiceImpl service() {
        return new DocumentApplicationServiceImpl(etlDispatchService, ragDocumentMapper,
                documentLifecycleService, uploadStrategyRouter, teamAccessGate, vectorStoreMapper,
                new DocumentDtoMapper(new DocumentPreviewPolicy(new DocumentProperties())),
                new DocumentAccessGuard(ragDocumentMapper, teamAccessGate),
                new DocumentProperties());
    }

    private static MultipartFile file(long sizeMb) {
        MultipartFile file = mock(MultipartFile.class);
        when(file.getSize()).thenReturn(sizeMb * MB);
        return file;
    }

    private static MultipartFile[] files(int count, long sizeMb) {
        MultipartFile[] files = new MultipartFile[count];
        for (int i = 0; i < count; i++) {
            files[i] = file(sizeMb);
        }
        return files;
    }

    // ==================== 数量上限 ====================

    @Test
    @DisplayName("空数组 → UPLOAD_LIST_EMPTY，不路由策略")
    void emptyBatchRejected() {
        try (MockedStatic<SecurityUtils> sec = mockStatic(SecurityUtils.class)) {
            sec.when(SecurityUtils::getCurrentUserId).thenReturn(1L);

            assertThatThrownBy(() -> service().uploadBatch(new MultipartFile[0], null))
                    .isInstanceOf(ClientException.class)
                    .extracting(e -> ((ClientException) e).getErrorCode().getCode())
                    .isEqualTo(104002);
            verify(uploadStrategyRouter, never()).route(any());
        }
    }

    @Test
    @DisplayName("11 个文件 → UPLOAD_BATCH_COUNT_EXCEEDED（104011）")
    void batchCountExceededRejected() {
        try (MockedStatic<SecurityUtils> sec = mockStatic(SecurityUtils.class)) {
            sec.when(SecurityUtils::getCurrentUserId).thenReturn(1L);

            assertThatThrownBy(() -> service().uploadBatch(files(11, 1), null))
                    .isInstanceOf(ClientException.class)
                    .extracting(e -> ((ClientException) e).getErrorCode().getCode())
                    .isEqualTo(104011);
            verify(uploadStrategyRouter, never()).route(any());
        }
    }

    // ==================== 总量上限 ====================

    @Test
    @DisplayName("总量 225MB（5×45MB）→ UPLOAD_BATCH_TOTAL_SIZE_EXCEEDED（104012）")
    void batchTotalSizeExceededRejected() {
        try (MockedStatic<SecurityUtils> sec = mockStatic(SecurityUtils.class)) {
            sec.when(SecurityUtils::getCurrentUserId).thenReturn(1L);

            assertThatThrownBy(() -> service().uploadBatch(files(5, 45), null))
                    .isInstanceOf(ClientException.class)
                    .extracting(e -> ((ClientException) e).getErrorCode().getCode())
                    .isEqualTo(104012);
            verify(uploadStrategyRouter, never()).route(any());
        }
    }

    // ==================== 边界与正常路由 ====================

    @Test
    @DisplayName("恰好 10 个 / 总量恰 200MB → 放行并路由策略")
    void exactLimitsRouteToStrategy() {
        try (MockedStatic<SecurityUtils> sec = mockStatic(SecurityUtils.class)) {
            sec.when(SecurityUtils::getCurrentUserId).thenReturn(1L);
            when(uploadStrategyRouter.route(isNull())).thenReturn(uploadStrategy);
            when(uploadStrategy.uploadBatch(anyList(), isNull(), isNull(), anyLong()))
                    .thenReturn(List.of());

            assertThat(service().uploadBatch(files(10, 20), null)).isNotNull();
            verify(uploadStrategyRouter).route(isNull());
        }
    }

    @Test
    @DisplayName("合法批量 → 按输入顺序路由策略并透传响应")
    void validBatchRoutesAndReturnsResponses() {
        try (MockedStatic<SecurityUtils> sec = mockStatic(SecurityUtils.class)) {
            sec.when(SecurityUtils::getCurrentUserId).thenReturn(1L);
            when(uploadStrategyRouter.route(isNull())).thenReturn(uploadStrategy);
            List<DocumentUploadResponse> expected = List.of(
                    new DocumentUploadResponse(1L, "a.pdf", EtlStatus.UPLOADED),
                    new DocumentUploadResponse(null, "b.pdf", EtlStatus.FAILED));
            when(uploadStrategy.uploadBatch(anyList(), isNull(), isNull(), anyLong())).thenReturn(expected);

            List<DocumentUploadResponse> actual = service().uploadBatch(files(2, 1), null);

            assertThat(actual).isSameAs(expected);
            assertThat(actual).hasSize(2);
            assertThat(actual.get(1).status()).isEqualTo(EtlStatus.FAILED);
        }
    }

    // ==================== 团队路径共享限制 ====================

    @Test
    @DisplayName("团队批量同样受数量上限约束（团队访问校验先行）")
    void teamBatchSharesCountLimit() {
        try (MockedStatic<SecurityUtils> sec = mockStatic(SecurityUtils.class)) {
            sec.when(SecurityUtils::getCurrentUserId).thenReturn(1L);
            when(teamAccessGate.verifyAccess(anyLong(), anyLong()))
                    .thenReturn(new TeamAccessGate.TeamAccess(false));

            assertThatThrownBy(() -> service().uploadBatch(files(11, 1), 5L))
                    .isInstanceOf(ClientException.class)
                    .extracting(e -> ((ClientException) e).getErrorCode().getCode())
                    .isEqualTo(104011);
            verify(teamAccessGate).verifyAccess(5L, 1L);
            verify(uploadStrategyRouter, never()).route(any());
        }
    }
}
