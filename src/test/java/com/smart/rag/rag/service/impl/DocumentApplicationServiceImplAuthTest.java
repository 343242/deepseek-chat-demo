package com.smart.rag.rag.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.smart.rag.infrastructure.exception.ServiceException;
import com.smart.rag.infrastructure.response.PagedResult;
import com.smart.rag.infrastructure.web.util.SecurityUtils;
import com.smart.rag.rag.dto.DocumentDTO;
import com.smart.rag.rag.entity.RagDocument;
import com.smart.rag.rag.etl.EtlStatus;
import com.smart.rag.rag.mapper.RagDocumentMapper;
import com.smart.rag.rag.mapper.VectorStoreMapper;
import com.smart.rag.rag.config.DocumentProperties;
import com.smart.rag.rag.service.DocumentPreviewPolicy;
import com.smart.rag.rag.service.EtlDispatchService;
import com.smart.rag.rag.service.TeamAccessGate;
import com.smart.rag.rag.upload.UploadStrategyRouter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * W5 R1-M1（方案 B）：团队文档权限分层。
 * <ul>
 *   <li>读可见性：非 owner/ADMIN/CREATOR 仅可见 COMPLETED；其余状态 → NOT_FOUND（不泄露存在性）</li>
 *   <li>变更（delete/retry）：仅 owner/ADMIN/CREATOR</li>
 *   <li>listByTeam：非管理员只看（自己的任意状态）OR（全队 COMPLETED）</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("W5 R1-M1: 团队文档权限分层（方案 B）")
class DocumentApplicationServiceImplAuthTest {

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

    private static RagDocument doc(Long teamId, Long ownerId, EtlStatus status) {
        RagDocument d = new RagDocument();
        d.setId(10L);
        d.setTeamId(teamId);
        d.setUserId(ownerId);
        d.setStatus(status);
        d.setFileSize(100L);
        return d;
    }

    private static TeamAccessGate.TeamAccess access(boolean manager) {
        return new TeamAccessGate.TeamAccess(manager);
    }

    // ==================== delete 授权 ====================

    @Test
    @DisplayName("owner 可删除自己的团队文档（任意状态）")
    void ownerCanDeleteOwnTeamDoc() {
        try (MockedStatic<SecurityUtils> sec = mockStatic(SecurityUtils.class)) {
            sec.when(SecurityUtils::getCurrentUserId).thenReturn(1L);
            when(ragDocumentMapper.selectById(anyLong())).thenReturn(doc(5L, 1L, EtlStatus.FAILED));
            when(teamAccessGate.verifyAccess(5L, 1L)).thenReturn(access(false));
            when(documentLifecycleService.cascadeDelete(any())).thenReturn(true);

            assertThat(service().delete(10L)).isTrue();
            verify(documentLifecycleService).cascadeDelete(any());
        }
    }

    @Test
    @DisplayName("ADMIN 可删除其他成员的团队文档")
    void adminCanDeleteOthersTeamDoc() {
        try (MockedStatic<SecurityUtils> sec = mockStatic(SecurityUtils.class)) {
            sec.when(SecurityUtils::getCurrentUserId).thenReturn(1L);
            when(ragDocumentMapper.selectById(anyLong())).thenReturn(doc(5L, 2L, EtlStatus.FAILED));
            when(teamAccessGate.verifyAccess(5L, 1L)).thenReturn(access(true));
            when(documentLifecycleService.cascadeDelete(any())).thenReturn(true);

            assertThat(service().delete(10L)).isTrue();
        }
    }

    @Test
    @DisplayName("非所有者 MEMBER 删除别人的 COMPLETED 团队文档 → OWNERSHIP_DENIED（可见但不可删）")
    void nonOwnerMemberCannotDeleteCompleted() {
        try (MockedStatic<SecurityUtils> sec = mockStatic(SecurityUtils.class)) {
            sec.when(SecurityUtils::getCurrentUserId).thenReturn(1L);
            when(ragDocumentMapper.selectById(anyLong())).thenReturn(doc(5L, 2L, EtlStatus.COMPLETED));
            when(teamAccessGate.verifyAccess(5L, 1L)).thenReturn(access(false));

            assertThatThrownBy(() -> service().delete(10L)).isInstanceOf(ServiceException.class);
            verify(documentLifecycleService, never()).cascadeDelete(any());
        }
    }

    @Test
    @DisplayName("个人文档：owner 可删除（不触发团队校验）")
    void personalOwnerCanDelete() {
        try (MockedStatic<SecurityUtils> sec = mockStatic(SecurityUtils.class)) {
            sec.when(SecurityUtils::getCurrentUserId).thenReturn(1L);
            when(ragDocumentMapper.selectById(anyLong())).thenReturn(doc(null, 1L, EtlStatus.COMPLETED));
            when(documentLifecycleService.cascadeDelete(any())).thenReturn(true);

            assertThat(service().delete(10L)).isTrue();
            verify(teamAccessGate, never()).verifyAccess(anyLong(), anyLong());
        }
    }

    // ==================== 读可见性分层 ====================

    @Test
    @DisplayName("非 owner MEMBER 访问别人的 FAILED 团队文档 → NOT_FOUND（中间态不暴露）")
    void nonOwnerCannotSeeOthersFailed() {
        try (MockedStatic<SecurityUtils> sec = mockStatic(SecurityUtils.class)) {
            sec.when(SecurityUtils::getCurrentUserId).thenReturn(1L);
            when(ragDocumentMapper.selectById(anyLong())).thenReturn(doc(5L, 2L, EtlStatus.FAILED));
            when(teamAccessGate.verifyAccess(5L, 1L)).thenReturn(access(false));

            assertThatThrownBy(() -> service().getById(10L)).isInstanceOf(ServiceException.class);
        }
    }

    @Test
    @DisplayName("非 owner MEMBER 访问别人的 COMPLETED 团队文档 → 可读")
    void nonOwnerCanSeeOthersCompleted() {
        try (MockedStatic<SecurityUtils> sec = mockStatic(SecurityUtils.class)) {
            sec.when(SecurityUtils::getCurrentUserId).thenReturn(1L);
            when(ragDocumentMapper.selectById(anyLong())).thenReturn(doc(5L, 2L, EtlStatus.COMPLETED));
            when(teamAccessGate.verifyAccess(5L, 1L)).thenReturn(access(false));

            DocumentDTO dto = service().getById(10L);
            assertThat(dto).isNotNull();
            assertThat(dto.status()).isEqualTo(EtlStatus.COMPLETED);
        }
    }

    @Test
    @DisplayName("owner 访问自己的 FAILED 团队文档 → 可见")
    void ownerCanSeeOwnFailed() {
        try (MockedStatic<SecurityUtils> sec = mockStatic(SecurityUtils.class)) {
            sec.when(SecurityUtils::getCurrentUserId).thenReturn(1L);
            when(ragDocumentMapper.selectById(anyLong())).thenReturn(doc(5L, 1L, EtlStatus.FAILED));
            when(teamAccessGate.verifyAccess(5L, 1L)).thenReturn(access(false));

            assertThat(service().getById(10L)).isNotNull();
        }
    }

    // ==================== retry 授权 ====================

    @Test
    @DisplayName("owner 可重试自己的 FAILED 团队文档")
    void ownerCanRetryOwnFailed() {
        try (MockedStatic<SecurityUtils> sec = mockStatic(SecurityUtils.class)) {
            sec.when(SecurityUtils::getCurrentUserId).thenReturn(1L);
            when(ragDocumentMapper.selectById(anyLong())).thenReturn(doc(5L, 1L, EtlStatus.FAILED));
            when(teamAccessGate.verifyAccess(5L, 1L)).thenReturn(access(false));

            assertThat(service().retry(10L).status()).isEqualTo(EtlStatus.PROCESSING);
        }
    }

    @Test
    @DisplayName("非 owner MEMBER 重试别人的 FAILED 团队文档 → NOT_FOUND（中间态不可见，retry 不再共享）")
    void nonOwnerCannotRetryOthersFailed() {
        try (MockedStatic<SecurityUtils> sec = mockStatic(SecurityUtils.class)) {
            sec.when(SecurityUtils::getCurrentUserId).thenReturn(1L);
            when(ragDocumentMapper.selectById(anyLong())).thenReturn(doc(5L, 2L, EtlStatus.FAILED));
            when(teamAccessGate.verifyAccess(5L, 1L)).thenReturn(access(false));

            assertThatThrownBy(() -> service().retry(10L)).isInstanceOf(ServiceException.class);
        }
    }

    // ==================== listByTeam 可见性过滤 ====================

    @Test
    @DisplayName("listByTeam：普通成员查询应用可见性过滤（wrapper 构造不抛异常）")
    void listByTeamMemberAppliesVisibilityFilter() {
        try (MockedStatic<SecurityUtils> sec = mockStatic(SecurityUtils.class)) {
            sec.when(SecurityUtils::getCurrentUserId).thenReturn(1L);
            when(teamAccessGate.verifyAccess(5L, 1L)).thenReturn(access(false));
            when(ragDocumentMapper.selectPage(any(), any())).thenReturn(new Page<RagDocument>(1, 10));

            PagedResult<DocumentDTO> result = service().listByTeam(5L, 1, 10);
            assertThat(result).isNotNull();
            verify(ragDocumentMapper).selectPage(any(), any());
        }
    }
}
