package com.smart.rag.rag.service.impl;

import com.smart.rag.infrastructure.exception.ServiceException;
import com.smart.rag.infrastructure.web.util.SecurityUtils;
import com.smart.rag.rag.config.DocumentProperties;
import com.smart.rag.rag.dto.BatchDeleteRequest;
import com.smart.rag.rag.dto.DocumentDeleteResult;
import com.smart.rag.rag.entity.RagDocument;
import com.smart.rag.rag.etl.EtlStatus;
import com.smart.rag.rag.mapper.RagDocumentMapper;
import com.smart.rag.rag.mapper.VectorStoreMapper;
import com.smart.rag.rag.service.DocumentPreviewPolicy;
import com.smart.rag.rag.service.EtlDispatchService;
import com.smart.rag.rag.service.TeamAccessGate;
import com.smart.rag.rag.upload.UploadStrategyRouter;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.lang.reflect.AnnotatedParameterizedType;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 批量删除（部分成功语义）：逐项复用 delete 的授权 + 级联，
 * 单项不存在 / 无权 / 非预期异常不影响其余项，不整体回滚。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("deleteBatch 部分成功语义 + BatchDeleteRequest 入口约束")
class DocumentApplicationServiceImplDeleteBatchTest {

    @Mock private EtlDispatchService etlDispatchService;
    @Mock private RagDocumentMapper ragDocumentMapper;
    @Mock private DocumentLifecycleService documentLifecycleService;
    @Mock private UploadStrategyRouter uploadStrategyRouter;
    @Mock private TeamAccessGate teamAccessGate;
    @Mock private VectorStoreMapper vectorStoreMapper;

    private DocumentApplicationServiceImpl service() {
        return new DocumentApplicationServiceImpl(etlDispatchService, ragDocumentMapper,
                documentLifecycleService, uploadStrategyRouter, teamAccessGate, vectorStoreMapper,
                new DocumentDtoMapper(new DocumentPreviewPolicy(new DocumentProperties())),
                new DocumentAccessGuard(ragDocumentMapper, teamAccessGate),
                new DocumentProperties());
    }

    /** 个人文档（userId 即当前用户 1L，verifyAccess 直接放行） */
    private static RagDocument ownDoc(Long id) {
        RagDocument d = new RagDocument();
        d.setId(id);
        d.setUserId(1L);
        d.setStatus(EtlStatus.COMPLETED);
        return d;
    }

    /** 他人所有的团队 COMPLETED 文档（MEMBER 可见但不可变更） */
    private static RagDocument othersTeamDoc(Long id, Long teamId) {
        RagDocument d = new RagDocument();
        d.setId(id);
        d.setTeamId(teamId);
        d.setUserId(2L);
        d.setStatus(EtlStatus.COMPLETED);
        return d;
    }

    @Test
    @DisplayName("全部成功 → 结果逐项 success，级联调用 N 次")
    void allSuccess() {
        try (MockedStatic<SecurityUtils> sec = mockStatic(SecurityUtils.class)) {
            sec.when(SecurityUtils::getCurrentUserId).thenReturn(1L);
            when(ragDocumentMapper.selectById(1L)).thenReturn(ownDoc(1L));
            when(ragDocumentMapper.selectById(2L)).thenReturn(ownDoc(2L));
            when(ragDocumentMapper.selectById(3L)).thenReturn(ownDoc(3L));

            List<DocumentDeleteResult> results = service().deleteBatch(List.of(1L, 2L, 3L));

            assertThat(results).hasSize(3).allMatch(DocumentDeleteResult::success);
            assertThat(results).extracting(DocumentDeleteResult::id).containsExactly(1L, 2L, 3L);
            verify(documentLifecycleService, times(3)).cascadeDelete(any());
        }
    }

    @Test
    @DisplayName("混合场景：不存在 + 无权 + 成功 → 成功项已删、失败项携带原因且不级联")
    void mixedSuccessAndFailure() {
        try (MockedStatic<SecurityUtils> sec = mockStatic(SecurityUtils.class)) {
            sec.when(SecurityUtils::getCurrentUserId).thenReturn(1L);
            when(ragDocumentMapper.selectById(1L)).thenReturn(null); // 不存在 → DOCUMENT_NOT_FOUND
            when(ragDocumentMapper.selectById(2L)).thenReturn(othersTeamDoc(2L, 5L)); // MEMBER 不可删
            when(ragDocumentMapper.selectById(3L)).thenReturn(ownDoc(3L));
            when(teamAccessGate.verifyAccess(5L, 1L)).thenReturn(new TeamAccessGate.TeamAccess(false));

            List<DocumentDeleteResult> results = service().deleteBatch(List.of(1L, 2L, 3L));

            assertThat(results).hasSize(3);
            assertThat(results.get(0).success()).isFalse();
            assertThat(results.get(0).message()).contains("不存在");
            assertThat(results.get(1).success()).isFalse();
            assertThat(results.get(1).message()).contains("所有者");
            assertThat(results.get(2).success()).isTrue();
            verify(documentLifecycleService, times(1)).cascadeDelete(any());
            verify(documentLifecycleService).cascadeDelete(org.mockito.ArgumentMatchers.argThat(d -> d.getId().equals(3L)));
        }
    }

    @Test
    @DisplayName("重复 ID 先去重 → 只级联一次，结果数与去重后一致")
    void duplicatesAreDeduplicated() {
        try (MockedStatic<SecurityUtils> sec = mockStatic(SecurityUtils.class)) {
            sec.when(SecurityUtils::getCurrentUserId).thenReturn(1L);
            when(ragDocumentMapper.selectById(5L)).thenReturn(ownDoc(5L));
            when(ragDocumentMapper.selectById(7L)).thenReturn(ownDoc(7L));

            List<DocumentDeleteResult> results = service().deleteBatch(List.of(5L, 5L, 7L));

            assertThat(results).extracting(DocumentDeleteResult::id).containsExactly(5L, 7L);
            verify(documentLifecycleService, times(2)).cascadeDelete(any());
        }
    }

    @Test
    @DisplayName("非预期异常不外泄细节、不中断整批")
    void unexpectedExceptionIsolatedPerItem() {
        try (MockedStatic<SecurityUtils> sec = mockStatic(SecurityUtils.class)) {
            sec.when(SecurityUtils::getCurrentUserId).thenReturn(1L);
            when(ragDocumentMapper.selectById(1L)).thenReturn(ownDoc(1L));
            when(ragDocumentMapper.selectById(2L)).thenReturn(ownDoc(2L));
            when(documentLifecycleService.cascadeDelete(any()))
                    .thenThrow(new RuntimeException("DB connection reset"))
                    .thenReturn(true);

            List<DocumentDeleteResult> results = service().deleteBatch(List.of(1L, 2L));

            assertThat(results.get(0).success()).isFalse();
            assertThat(results.get(0).message()).doesNotContain("connection").isEqualTo("删除失败，请稍后重试");
            assertThat(results.get(1).success()).isTrue();
        }
    }

    // ==================== BatchDeleteRequest bean validation 契约 ====================

    @Test
    @DisplayName("BatchDeleteRequest：ids @NotEmpty + @Size(max=50)，元素 @NotNull + @Positive")
    void batchDeleteRequestValidationContract() throws Exception {
        // 校验注解的 @Target 不含 RECORD_COMPONENT，会被 record 传播到访问器方法上，从 accessor 读取
        var accessor = BatchDeleteRequest.class.getRecordComponents()[0].getAccessor();
        assertThat(accessor.getAnnotations())
                .extracting(a -> a.annotationType().getSimpleName())
                .contains("NotEmpty", "Size");
        assertThat(accessor.getAnnotation(Size.class).max()).isEqualTo(50);
        assertThat(accessor.getAnnotation(NotEmpty.class)).isNotNull();

        var listType = (AnnotatedParameterizedType) accessor.getAnnotatedReturnType();
        assertThat(listType.getAnnotatedActualTypeArguments()[0].getAnnotations())
                .extracting(a -> a.annotationType().getSimpleName())
                .contains("NotNull", "Positive");
    }

    @Test
    @DisplayName("ServiceException 携带的用户消息透传到单项结果")
    void serviceExceptionMessagePropagated() {
        try (MockedStatic<SecurityUtils> sec = mockStatic(SecurityUtils.class)) {
            sec.when(SecurityUtils::getCurrentUserId).thenReturn(1L);
            when(ragDocumentMapper.selectById(9L)).thenThrow(new ServiceException(
                    com.smart.rag.infrastructure.exception.errorcode.ServiceErrorCode.DOCUMENT_NOT_FOUND,
                    "文档不存在: 9"));

            List<DocumentDeleteResult> results = service().deleteBatch(List.of(9L));

            assertThat(results.get(0).success()).isFalse();
            assertThat(results.get(0).message()).isEqualTo("文档不存在: 9");
            verify(documentLifecycleService, never()).cascadeDelete(any());
        }
    }
}
