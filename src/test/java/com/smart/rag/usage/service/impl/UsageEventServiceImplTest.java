package com.smart.rag.usage.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.smart.rag.infrastructure.request.PageRequest;
import com.smart.rag.infrastructure.response.PagedResult;
import com.smart.rag.usage.UsageEventPayload;
import com.smart.rag.usage.dto.TimelineGranularity;
import com.smart.rag.usage.dto.UsageEventDTO;
import com.smart.rag.usage.dto.UsageQueryFilter;
import com.smart.rag.usage.dto.UsageStatsDim;
import com.smart.rag.usage.dto.UsageStatsOrder;
import com.smart.rag.usage.dto.UsageStatsSort;
import com.smart.rag.usage.dto.UsageSummaryDTO;
import com.smart.rag.usage.entity.UsageEvent;
import com.smart.rag.usage.mapper.UsageEventMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * UsageEventServiceImpl 单测 — 落库幂等 + 明细分页映射 + 时间桶缺省/收敛 + 聚合透传。
 */
@ExtendWith(MockitoExtension.class)
class UsageEventServiceImplTest {

    @Mock
    private UsageEventMapper mapper;

    @Mock
    private TransactionTemplate transactionTemplate;

    private UsageEventServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new UsageEventServiceImpl(mapper, transactionTemplate);
    }

    /** record 路径的事务模板 stub（其余查询路径不触碰事务，避免 UnnecessaryStubbing） */
    private void stubTransaction() {
        doAnswer(inv -> {
            ((Consumer<?>) inv.getArgument(0)).accept(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
    }

    private static UsageEventPayload payload(String eventId) {
        return new UsageEventPayload(eventId, 7L, "CHAT", "conv-1", "candidate-a",
            100L, 50L, 150L, false, true, 200L);
    }

    private static final UUID EVENT_UUID = UUID.randomUUID();

    @Nested
    @DisplayName("record")
    class Record {

        @Test
        @DisplayName("落库实体字段与 payload 一致")
        void insertsEntityFromPayload() {
            stubTransaction();
            service.record(payload(EVENT_UUID.toString()));

            ArgumentCaptor<UsageEvent> captor = ArgumentCaptor.forClass(UsageEvent.class);
            verify(mapper).insert(captor.capture());
            UsageEvent event = captor.getValue();
            assertThat(event.getEventId()).isEqualTo(EVENT_UUID);
            assertThat(event.getUserId()).isEqualTo(7L);
            assertThat(event.getScene()).isEqualTo("CHAT");
            assertThat(event.getConversationId()).isEqualTo("conv-1");
            assertThat(event.getModelId()).isEqualTo("candidate-a");
            assertThat(event.getPromptTokens()).isEqualTo(100L);
            assertThat(event.getTotalTokens()).isEqualTo(150L);
            assertThat(event.getSuccess()).isTrue();
            assertThat(event.getDurationMs()).isEqualTo(200L);
        }

        @Test
        @DisplayName("event_id 唯一约束冲突静默跳过（幂等兜底）")
        void duplicateKeyIsSwallowed() {
            stubTransaction();
            when(mapper.insert(any(UsageEvent.class))).thenThrow(new DuplicateKeyException("dup"));

            assertThatCode(() -> service.record(payload(EVENT_UUID.toString()))).doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("getRecords")
    class GetRecords {

        @Test
        @DisplayName("分页查询并映射 DTO（含 boolean 装箱防御）")
        void pagesAndMapsToDto() {
            UsageEvent event = new UsageEvent(EVENT_UUID, 7L, "CHAT", "conv-1", "candidate-a",
                100L, 50L, 150L, true, false, 200L);
            Page<UsageEvent> page = new Page<>(1, 20);
            page.setRecords(List.of(event));
            page.setTotal(1);
            when(mapper.selectPage(any(), any())).thenReturn(page);

            PagedResult<UsageEventDTO> result = service.getRecords(
                new UsageQueryFilter(7L, null, null, null, null, null), PageRequest.of(1, 20));

            assertThat(result.total()).isEqualTo(1);
            UsageEventDTO dto = result.content().get(0);
            assertThat(dto.eventId()).isEqualTo(EVENT_UUID.toString());
            assertThat(dto.estimated()).isTrue();
            assertThat(dto.success()).isFalse();
            assertThat(dto.durationMs()).isEqualTo(200L);
        }
    }

    @Nested
    @DisplayName("getSummary")
    class GetSummary {

        @Test
        @DisplayName("聚合结果透传；空结果防御性兜底为零值")
        void fallsBackToZeroWhenNull() {
            when(mapper.selectSummary(any())).thenReturn(null);

            UsageSummaryDTO summary = service.getSummary(
                new UsageQueryFilter(7L, null, null, null, null, null));

            assertThat(summary.requestCount()).isZero();
            assertThat(summary.totalTokens()).isZero();
        }
    }

    @Nested
    @DisplayName("getTimeline")
    class GetTimeline {

        @Test
        @DisplayName("缺省窗口：end=now，start=end-30 天")
        void defaultsRangeToLast30Days() {
            when(mapper.selectTimeline(any(), any(), any())).thenReturn(List.of());

            OffsetDateTime before = OffsetDateTime.now();
            service.getTimeline(TimelineGranularity.DAY,
                new UsageQueryFilter(7L, "CHAT", null, null, null, null));
            OffsetDateTime after = OffsetDateTime.now();

            ArgumentCaptor<UsageQueryFilter> captor = ArgumentCaptor.forClass(UsageQueryFilter.class);
            verify(mapper).selectTimeline(eq("day"), eq("1 day"), captor.capture());
            UsageQueryFilter effective = captor.getValue();
            assertThat(effective.start()).isAfterOrEqualTo(before.minusDays(30));
            assertThat(effective.start()).isBeforeOrEqualTo(after.minusDays(30));
            assertThat(effective.end()).isAfterOrEqualTo(before);
            assertThat(effective.end()).isBeforeOrEqualTo(after);
            // 其余过滤条件保留
            assertThat(effective.userId()).isEqualTo(7L);
            assertThat(effective.scene()).isEqualTo("CHAT");
        }

        @Test
        @DisplayName("day 粒度区间收敛到 366 桶上限")
        void clampsDayRangeToMaxBuckets() {
            when(mapper.selectTimeline(any(), any(), any())).thenReturn(List.of());

            OffsetDateTime end = OffsetDateTime.now();
            OffsetDateTime start = end.minusDays(1000);
            service.getTimeline(TimelineGranularity.DAY,
                new UsageQueryFilter(null, null, null, null, start, end));

            ArgumentCaptor<UsageQueryFilter> captor = ArgumentCaptor.forClass(UsageQueryFilter.class);
            verify(mapper).selectTimeline(eq("day"), eq("1 day"), captor.capture());
            assertThat(captor.getValue().start())
                .isAfter(end.minusDays(367))
                .isBeforeOrEqualTo(end.minusDays(365));
        }

        @Test
        @DisplayName("month 粒度传 unit=month / step=1 month")
        void passesMonthUnitAndStep() {
            when(mapper.selectTimeline(any(), any(), any())).thenReturn(List.of());

            service.getTimeline(TimelineGranularity.MONTH,
                new UsageQueryFilter(null, null, null, null, null, null));

            verify(mapper).selectTimeline(eq("month"), eq("1 month"), any());
        }
    }

    @Nested
    @DisplayName("getStats")
    class GetStats {

        @Test
        @DisplayName("分组维度/排序参数透传到 Mapper")
        void passesThroughToMapper() {
            when(mapper.selectStats(any(), any(), any(), any())).thenReturn(List.of());

            service.getStats(UsageStatsDim.MODEL, UsageStatsSort.REQUEST_COUNT, UsageStatsOrder.ASC,
                new UsageQueryFilter(null, null, null, null, null, null));

            verify(mapper).selectStats(eq(UsageStatsDim.MODEL), eq(UsageStatsSort.REQUEST_COUNT),
                eq(UsageStatsOrder.ASC), any());
        }
    }
}
