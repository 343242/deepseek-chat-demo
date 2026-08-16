package com.smart.rag.rag;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.smart.rag.infrastructure.response.PagedResult;
import com.smart.rag.rag.dto.DocumentDTO;
import com.smart.rag.rag.entity.RagDocument;
import com.smart.rag.rag.etl.EtlStatus;
import com.smart.rag.rag.mapper.RagDocumentMapper;
import com.smart.rag.rag.mapper.VectorStoreMapper;
import com.smart.rag.rag.service.DocumentDedupService;
import com.smart.rag.rag.service.EtlDispatchService;
import com.smart.rag.rag.service.TeamAccessGate;
import com.smart.rag.rag.service.impl.DocumentApplicationServiceImpl;
import com.smart.rag.rag.service.impl.DocumentLifecycleService;
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
import org.redisson.api.RBloomFilter;
import org.redisson.api.RedissonClient;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * W3: unbounded reads / pagination regression tests.
 * <ul>
 *   <li>R1-H2: {@code listAll}/{@code listByTeam} pagination + size clamp</li>
 *   <li>R1-M6: {@link DocumentDedupService} BloomFilter warm-up is non-blocking
 *       (constructor does not query DB; cold start falls back to DB)</li>
 * </ul>
 */
@DisplayName("W3: unbounded reads / pagination")
class W3UnboundedReadsTest {

    // ==================== R1-H2: listAll / listByTeam pagination ====================

    @Nested
    @ExtendWith(MockitoExtension.class)
    @MockitoSettings(strictness = Strictness.LENIENT)
    @DisplayName("R1-H2: listAll / listByTeam 分页 + size 钳制")
    class ListPagination {

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
                    uploadStrategyRouter, teamAccessGate, vectorStoreMapper,
                    new com.smart.rag.rag.service.impl.DocumentDtoMapper(
                            new com.smart.rag.rag.service.DocumentPreviewPolicy(
                                    new com.smart.rag.rag.config.DocumentProperties())),
                    new com.smart.rag.rag.service.impl.DocumentAccessGuard(ragDocumentMapper, teamAccessGate),
                    new com.smart.rag.rag.config.DocumentProperties());
        }

        @AfterEach
        void clearContext() {
            SecurityContextHolder.clearContext();
        }

        private void loginAs(Long userId) {
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken(userId, "n/a", List.of()));
        }

        private void stubSelectPage(int pageSize, long total, List<RagDocument> records) {
            // 用 ArgumentCaptor 不可行（泛型擦除），改用 thenAnswer 把入参 Page 回填
            when(ragDocumentMapper.selectPage(any(), any()))
                    .thenAnswer(inv -> {
                        Page<RagDocument> p = inv.getArgument(0);
                        p.setRecords(records);
                        p.setTotal(total);  // setTotal 自动触发 calcPageCount → pages = ceil(total/size)
                        return p;
                    });
        }

        private RagDocument doc(long id) {
            RagDocument d = new RagDocument();
            d.setId(id);
            d.setUserId(1L);
            d.setTeamId(null);
            d.setStatus(EtlStatus.COMPLETED);
            d.setFileName("f" + id + ".pdf");
            return d;
        }

        @Test
        @DisplayName("listAll: 返回第 N 页，total/page/size 正确")
        void listAll_returns_paged_result() {
            loginAs(1L);
            List<RagDocument> page1 = List.of(doc(1), doc(2));
            stubSelectPage(2, 5L, page1);

            PagedResult<DocumentDTO> result = service.listAll(1, 2);

            assertThat(result.content()).hasSize(2);
            assertThat(result.page()).isEqualTo(1);
            assertThat(result.size()).isEqualTo(2);
            assertThat(result.total()).isEqualTo(5L);
            assertThat(result.totalPages()).isEqualTo(3);
            verify(ragDocumentMapper, times(1)).selectPage(any(), any());
        }

        @Test
        @DisplayName("listAll: size>100 静默钳制到 100")
        void listAll_size_clamped_to_100() {
            loginAs(1L);
            // 用 AtomicReference 捕获传入的 Page 以断言 size
            AtomicReference<Page<?>> captured = new AtomicReference<>();
            when(ragDocumentMapper.selectPage(any(), any()))
                    .thenAnswer(inv -> {
                        Page<RagDocument> p = inv.getArgument(0);
                        captured.set(p);
                        p.setRecords(List.of(doc(1)));
                        p.setTotal(1L);
                        return p;
                    });

            PagedResult<DocumentDTO> result = service.listAll(1, 500);

            assertThat(result.size()).as("size 必须钳制到 100").isEqualTo(100);
            assertThat(captured.get().getSize()).isEqualTo(100L);
        }

        @Test
        @DisplayName("listAll: page<1 归一化为 1")
        void listAll_negative_page_normalized() {
            loginAs(1L);
            AtomicReference<Page<?>> captured = new AtomicReference<>();
            when(ragDocumentMapper.selectPage(any(), any()))
                    .thenAnswer(inv -> {
                        Page<RagDocument> p = inv.getArgument(0);
                        captured.set(p);
                        p.setRecords(List.of());
                        p.setTotal(0L);
                        return p;
                    });

            service.listAll(-3, 20);

            assertThat(captured.get().getCurrent()).isEqualTo(1L);
        }

        @Test
        @DisplayName("listByTeam: 返回分页结果且校验团队成员身份")
        void listByTeam_returns_paged_result_and_verifies_membership() {
            loginAs(1L);
            when(teamAccessGate.verifyAccess(7L, 1L)).thenReturn(new TeamAccessGate.TeamAccess(false));
            stubSelectPage(2, 4L, List.of(doc(1), doc(2)));

            PagedResult<DocumentDTO> result = service.listByTeam(7L, 1, 2);

            assertThat(result.content()).hasSize(2);
            assertThat(result.total()).isEqualTo(4L);
            verify(teamAccessGate, times(1)).verifyAccess(7L, 1L);
        }
    }

    // ==================== R1-M6: DocumentDedupService non-blocking warm-up ====================

    @Nested
    @ExtendWith(MockitoExtension.class)
    @MockitoSettings(strictness = Strictness.LENIENT)
    @DisplayName("R1-M6: BloomFilter warm-up 不阻塞启动，cold-start 走 DB 确认")
    class DedupNonBlockingWarmUp {

        @Test
        @DisplayName("构造器不查 DB（启动非阻塞）；warmUp 前可能命中的校验和 mayExist 仍返回 true")
        void constructor_does_not_load_db(@Mock RedissonClient redissonClient,
                                          @Mock RagDocumentMapper documentMapper,
                                          @Mock RBloomFilter<String> bloomFilter) {
            when(redissonClient.<String>getBloomFilter(any(String.class))).thenReturn(bloomFilter);
            when(bloomFilter.tryInit(anyLong(), anyDouble())).thenReturn(true);

            DocumentDedupService svc = new DocumentDedupService(redissonClient, documentMapper);

            // 构造器只 tryInit，绝不触发 selectList/ selectPage
            verify(documentMapper, never()).selectList(any());
            verify(documentMapper, never()).selectPage(any(), any());
            // 冷启动：warmedUp=false
            assertThat(svc.isWarmedUp()).isFalse();
            // mayExist 仍返回 true，确保调用方走 confirmExisting 的 DB 路径
            assertThat(svc.mayExist("any-checksum"))
                    .as("冷启动 mayExist 必须返回 true 以走 DB 确认，避免假阴性")
                    .isTrue();
        }

        @Test
        @DisplayName("warmUp 成功后 mayExist 走 BloomFilter，且 isWarmedUp=true")
        void warmUp_then_mayExist_uses_bloom_filter(@Mock RedissonClient redissonClient,
                                                    @Mock RagDocumentMapper documentMapper,
                                                    @Mock RBloomFilter<String> bloomFilter) {
            when(redissonClient.<String>getBloomFilter(any(String.class))).thenReturn(bloomFilter);
            when(bloomFilter.tryInit(anyLong(), anyDouble())).thenReturn(true);
            when(bloomFilter.add("known-checksum")).thenReturn(true);
            when(bloomFilter.contains("known-checksum")).thenReturn(true);

            // 用子类覆写 loadExistingFileChecksumsBatched 绕开 MyBatis-Plus lambda cache
            // （单元测试未启动 Spring 上下文，LambdaQueryWrapper 无法解析实体列）。
            // 子类只把 known-checksum 加入 BloomFilter 后返回 1。
            DocumentDedupService svc = new DocumentDedupService(redissonClient, documentMapper) {
                @Override
                protected long loadExistingFileChecksumsBatched() {
                    bloomFilter.add("known-checksum");
                    return 1L;
                }
            };
            svc.warmUp();

            assertThat(svc.isWarmedUp()).isTrue();
            // warmUp 完成后，add 过的校验和 → contains 返回 true
            assertThat(svc.mayExist("known-checksum")).isTrue();
            verify(bloomFilter, times(1)).add("known-checksum");
        }

        @Test
        @DisplayName("warmUp 失败仅记录日志，warmedUp 保持 false，应用可用")
        void warmUp_failure_does_not_prevent_availability(@Mock RedissonClient redissonClient,
                                                          @Mock RagDocumentMapper documentMapper,
                                                          @Mock RBloomFilter<String> bloomFilter) {
            when(redissonClient.<String>getBloomFilter(any(String.class))).thenReturn(bloomFilter);
            when(bloomFilter.tryInit(anyLong(), anyDouble())).thenReturn(true);
            // selectPage 抛异常模拟 DB 故障
            when(documentMapper.selectPage(any(), any()))
                    .thenThrow(new RuntimeException("db down"));

            DocumentDedupService svc = new DocumentDedupService(redissonClient, documentMapper);
            // warmUp 必须吞掉异常，不向上抛
            svc.warmUp();

            assertThat(svc.isWarmedUp()).isFalse();
            // 失败状态下仍返回 true（保守走 DB）
            assertThat(svc.mayExist("any")).isTrue();
        }

        @Test
        @DisplayName("RedissonClient=null 时 BloomFilter 不可用，mayExist 返回 true（不拦截）")
        void no_redisson_client_safe_degradation(@Mock RagDocumentMapper documentMapper) {
            DocumentDedupService svc = new DocumentDedupService(null, documentMapper);

            assertThat(svc.isWarmedUp()).isFalse();
            assertThat(svc.mayExist("any")).isTrue();
        }
    }
}
