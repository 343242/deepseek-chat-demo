package com.smart.rag.rag.service.impl;

import com.smart.rag.infrastructure.exception.ServiceException;
import com.smart.rag.infrastructure.web.util.SecurityUtils;
import com.smart.rag.rag.entity.RagDocument;
import com.smart.rag.rag.etl.EtlStatus;
import com.smart.rag.rag.mapper.RagDocumentMapper;
import com.smart.rag.rag.service.EtlDispatchService;
import com.smart.rag.team.entity.TeamMember;
import com.smart.rag.team.enums.TeamMemberRole;
import com.smart.rag.team.service.TeamMembershipVerifier;
import com.smart.rag.team.upload.UploadStrategyFactory;
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
 * W5 R1-M1: 团队文档删除权限——仅 owner / ADMIN / CREATOR 可删（PRD 选项 3）。
 * retry / getById 保持团队共享，不受影响。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("W5 R1-M1: 团队文档删除权限（owner / ADMIN / CREATOR）")
class DocumentApplicationServiceImplAuthTest {

    @Mock private EtlDispatchService etlDispatchService;
    @Mock private RagDocumentMapper ragDocumentMapper;
    @Mock private DocumentLifecycleService documentLifecycleService;
    @Mock private UploadStrategyFactory uploadStrategyFactory;
    @Mock private TeamMembershipVerifier teamMembershipVerifier;

    private DocumentApplicationServiceImpl service() {
        return new DocumentApplicationServiceImpl(etlDispatchService, ragDocumentMapper,
                documentLifecycleService, uploadStrategyFactory, teamMembershipVerifier);
    }

    private static RagDocument teamDoc(Long teamId, Long ownerId) {
        RagDocument d = new RagDocument();
        d.setId(10L);
        d.setTeamId(teamId);
        d.setUserId(ownerId);
        d.setStatus(EtlStatus.FAILED);
        d.setFileSize(100L);
        return d;
    }

    private static TeamMember member(TeamMemberRole role) {
        TeamMember m = new TeamMember();
        m.setRole(role);
        return m;
    }

    @Test
    @DisplayName("owner 可删除自己的团队文档")
    void ownerCanDeleteOwnTeamDoc() {
        try (MockedStatic<SecurityUtils> sec = mockStatic(SecurityUtils.class)) {
            sec.when(SecurityUtils::getCurrentUserId).thenReturn(1L);
            when(ragDocumentMapper.selectById(anyLong())).thenReturn(teamDoc(5L, 1L));
            when(teamMembershipVerifier.verifyMember(5L, 1L)).thenReturn(member(TeamMemberRole.MEMBER));
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
            when(ragDocumentMapper.selectById(anyLong())).thenReturn(teamDoc(5L, 2L)); // owned by user 2
            when(teamMembershipVerifier.verifyMember(5L, 1L)).thenReturn(member(TeamMemberRole.ADMIN));
            when(documentLifecycleService.cascadeDelete(any())).thenReturn(true);

            assertThat(service().delete(10L)).isTrue();
        }
    }

    @Test
    @DisplayName("非所有者的普通 MEMBER 删除团队文档 → DOCUMENT_OWNERSHIP_DENIED")
    void nonOwnerMemberForbidden() {
        try (MockedStatic<SecurityUtils> sec = mockStatic(SecurityUtils.class)) {
            sec.when(SecurityUtils::getCurrentUserId).thenReturn(1L);
            when(ragDocumentMapper.selectById(anyLong())).thenReturn(teamDoc(5L, 2L));
            when(teamMembershipVerifier.verifyMember(5L, 1L)).thenReturn(member(TeamMemberRole.MEMBER));

            assertThatThrownBy(() -> service().delete(10L))
                    .isInstanceOf(ServiceException.class);
            verify(documentLifecycleService, never()).cascadeDelete(any());
        }
    }

    @Test
    @DisplayName("个人文档：owner 可删除（不触发团队校验）")
    void personalOwnerCanDelete() {
        try (MockedStatic<SecurityUtils> sec = mockStatic(SecurityUtils.class)) {
            sec.when(SecurityUtils::getCurrentUserId).thenReturn(1L);
            when(ragDocumentMapper.selectById(anyLong())).thenReturn(teamDoc(null, 1L));
            when(documentLifecycleService.cascadeDelete(any())).thenReturn(true);

            assertThat(service().delete(10L)).isTrue();
            verify(teamMembershipVerifier, never()).verifyMember(anyLong(), anyLong());
        }
    }

    @Test
    @DisplayName("retry 保持团队共享：普通成员可重试他人团队文档（不受 R1-M1 影响）")
    void retryRemainsTeamShared() {
        try (MockedStatic<SecurityUtils> sec = mockStatic(SecurityUtils.class)) {
            sec.when(SecurityUtils::getCurrentUserId).thenReturn(1L);
            when(ragDocumentMapper.selectById(anyLong())).thenReturn(teamDoc(5L, 2L));
            when(teamMembershipVerifier.verifyMember(5L, 1L)).thenReturn(member(TeamMemberRole.MEMBER));

            // retry 不抛 ownership 异常即证明未受 R1-M1 收紧影响（delete 专用，retry 保持共享）
            service().retry(10L);
        }
    }
}
