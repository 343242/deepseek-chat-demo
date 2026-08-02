package com.smart.rag.rag.service.impl;

import com.smart.rag.infrastructure.exception.ClientException;
import com.smart.rag.infrastructure.exception.ServiceException;
import com.smart.rag.infrastructure.exception.errorcode.ClientErrorCode;
import com.smart.rag.infrastructure.exception.errorcode.ServiceErrorCode;
import com.smart.rag.infrastructure.response.PagedResult;
import com.smart.rag.rag.dto.ChunkDTO;
import com.smart.rag.rag.entity.RagDocument;
import com.smart.rag.rag.etl.EtlStatus;
import com.smart.rag.rag.mapper.RagDocumentMapper;
import com.smart.rag.rag.mapper.VectorStoreMapper;
import com.smart.rag.rag.service.EtlDispatchService;
import com.smart.rag.rag.service.TeamAccessGate;
import com.smart.rag.rag.upload.UploadStrategyRouter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 文档片段查看（listChunks / getChunk）单元测试。
 * <p>
 * 核心契约：归属校验复用 {@code verifyAccess}（个人文档 owner / 团队文档成员），
 * chunk 数据来自 vector_store。覆盖：访问放行、访问拒绝、chunk 不存在、documentId 解析。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("文档片段查看（chunks REST）")
class ChunkViewTest {

    @Mock private EtlDispatchService etlDispatchService;
    @Mock private RagDocumentMapper ragDocumentMapper;
    @Mock private DocumentLifecycleService documentLifecycleService;
    @Mock private UploadStrategyRouter uploadStrategyRouter;
    @Mock private TeamAccessGate teamAccessGate;
    @Mock private VectorStoreMapper vectorStoreMapper;

    private DocumentApplicationServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new DocumentApplicationServiceImpl(
                etlDispatchService, ragDocumentMapper, documentLifecycleService,
                uploadStrategyRouter, teamAccessGate, vectorStoreMapper);
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private void loginAs(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId, "n/a", List.of()));
    }

    private static RagDocument personalDoc(Long id, Long ownerId, EtlStatus status) {
        RagDocument d = new RagDocument();
        d.setId(id);
        d.setUserId(ownerId);
        d.setStatus(status);
        d.setFileSize(100L);
        return d;
    }

    private static VectorStoreMapper.VectorStoreRow chunk(String id, String content, Long documentId) {
        return new VectorStoreMapper.VectorStoreRow(
                id, content, Map.of("documentId", String.valueOf(documentId), "fileName", "test.pdf"));
    }

    // ==================== listChunks ====================

    @Nested
    @DisplayName("listChunks — 分页 + 归属校验")
    class ListChunks {

        @Test
        @DisplayName("owner 访问个人文档：返回分页 chunk + total")
        void ownerListsOwnDocumentChunks() {
            loginAs(1L);
            when(ragDocumentMapper.selectById(10L))
                    .thenReturn(personalDoc(10L, 1L, EtlStatus.COMPLETED));
            when(vectorStoreMapper.countChunksByDocumentId("10")).thenReturn(2);
            when(vectorStoreMapper.selectChunksByDocumentIdPaged(eq("10"), anyInt(), anyInt()))
                    .thenReturn(List.of(
                            chunk("uuid-1", "内容一", 10L),
                            chunk("uuid-2", "内容二", 10L)));

            PagedResult<ChunkDTO> result = service.listChunks(10L, 1, 20);

            assertThat(result.content()).hasSize(2);
            assertThat(result.total()).isEqualTo(2L);
            assertThat(result.page()).isEqualTo(1);
            assertThat(result.size()).isEqualTo(20);
            assertThat(result.totalPages()).isEqualTo(1);
            ChunkDTO first = result.content().get(0);
            assertThat(first.id()).isEqualTo("uuid-1");
            assertThat(first.content()).isEqualTo("内容一");
            assertThat(first.documentId()).isEqualTo(10L);
            assertThat(first.fileName()).isEqualTo("test.pdf");
            // OFFSET = (page-1)*size = 0
            verify(vectorStoreMapper).selectChunksByDocumentIdPaged("10", 0, 20);
        }

        @Test
        @DisplayName("非 owner 访问个人文档：抛 FORBIDDEN，不查询 chunks")
        void nonOwnerForbidden() {
            loginAs(2L);
            when(ragDocumentMapper.selectById(10L))
                    .thenReturn(personalDoc(10L, 1L, EtlStatus.COMPLETED));

            assertThatThrownBy(() -> service.listChunks(10L, 1, 20))
                    .isInstanceOf(ClientException.class)
                    .extracting(e -> ((ClientException) e).getErrorCode())
                    .isEqualTo(ClientErrorCode.FORBIDDEN);

            verify(vectorStoreMapper, never()).selectChunksByDocumentIdPaged(anyString(), anyInt(), anyInt());
        }

        @Test
        @DisplayName("分页参数归一化：page<1 → 1，size 钳制")
        void pagingNormalized() {
            loginAs(1L);
            when(ragDocumentMapper.selectById(10L))
                    .thenReturn(personalDoc(10L, 1L, EtlStatus.COMPLETED));
            when(vectorStoreMapper.countChunksByDocumentId("10")).thenReturn(0);
            when(vectorStoreMapper.selectChunksByDocumentIdPaged(anyString(), anyInt(), anyInt()))
                    .thenReturn(List.of());

            PagedResult<ChunkDTO> result = service.listChunks(10L, 0, 0);

            assertThat(result.page()).isEqualTo(1);
            assertThat(result.size()).isEqualTo(1); // size<1 → 1
            // OFFSET = (1-1)*1 = 0
            verify(vectorStoreMapper).selectChunksByDocumentIdPaged("10", 0, 1);
        }
    }

    // ==================== getChunk ====================

    @Nested
    @DisplayName("getChunk — chunk UUID 直接寻址 + 归属校验")
    class GetChunk {

        @Test
        @DisplayName("owner 查询自己的 chunk：返回 DTO，documentId 从 metadata 解析")
        void ownerGetsOwnChunk() {
            loginAs(1L);
            when(vectorStoreMapper.selectChunkById("uuid-1"))
                    .thenReturn(chunk("uuid-1", "片段内容", 10L));
            when(ragDocumentMapper.selectById(10L))
                    .thenReturn(personalDoc(10L, 1L, EtlStatus.COMPLETED));

            ChunkDTO dto = service.getChunk("uuid-1");

            assertThat(dto.id()).isEqualTo("uuid-1");
            assertThat(dto.content()).isEqualTo("片段内容");
            assertThat(dto.documentId()).isEqualTo(10L);
            assertThat(dto.fileName()).isEqualTo("test.pdf");
        }

        @Test
        @DisplayName("chunk 不存在：抛 NOT_FOUND")
        void chunkNotFound() {
            when(vectorStoreMapper.selectChunkById("missing")).thenReturn(null);

            assertThatThrownBy(() -> service.getChunk("missing"))
                    .isInstanceOf(ServiceException.class)
                    .extracting(e -> ((ServiceException) e).getErrorCode())
                    .isEqualTo(ServiceErrorCode.NOT_FOUND);

            verify(ragDocumentMapper, never()).selectById(org.mockito.ArgumentMatchers.any());
        }

        @Test
        @DisplayName("chunk 存在但文档归属他人：抛 FORBIDDEN")
        void chunkDocumentForbidden() {
            loginAs(2L);
            when(vectorStoreMapper.selectChunkById("uuid-1"))
                    .thenReturn(chunk("uuid-1", "片段内容", 10L));
            when(ragDocumentMapper.selectById(10L))
                    .thenReturn(personalDoc(10L, 1L, EtlStatus.COMPLETED));

            assertThatThrownBy(() -> service.getChunk("uuid-1"))
                    .isInstanceOf(ClientException.class)
                    .extracting(e -> ((ClientException) e).getErrorCode())
                    .isEqualTo(ClientErrorCode.FORBIDDEN);
        }
    }
}
