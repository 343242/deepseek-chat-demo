package com.smart.rag.usage.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.smart.rag.infrastructure.request.PageRequest;
import com.smart.rag.infrastructure.response.PagedResult;
import com.smart.rag.usage.UsageEventPayload;
import com.smart.rag.usage.dto.TimelineGranularity;
import com.smart.rag.usage.dto.UsageEventDTO;
import com.smart.rag.usage.dto.UsageQueryFilter;
import com.smart.rag.usage.dto.UsageStatsDTO;
import com.smart.rag.usage.dto.UsageStatsDim;
import com.smart.rag.usage.dto.UsageStatsOrder;
import com.smart.rag.usage.dto.UsageStatsSort;
import com.smart.rag.usage.dto.UsageSummaryDTO;
import com.smart.rag.usage.dto.UsageTimelinePointDTO;
import com.smart.rag.usage.entity.UsageEvent;
import com.smart.rag.usage.mapper.UsageEventMapper;
import com.smart.rag.usage.service.UsageEventService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 用量事件服务实现
 */
@Service
public class UsageEventServiceImpl implements UsageEventService {

    private static final Logger log = LoggerFactory.getLogger(UsageEventServiceImpl.class);

    /** 时间桶缺省窗口：近 30 天 */
    private static final int DEFAULT_TIMELINE_DAYS = 30;
    /** 时间桶上限：day 366 桶 / month 24 桶（防超长区间拖垮 generate_series 查询） */
    private static final int MAX_DAY_BUCKETS = 366;
    private static final int MAX_MONTH_BUCKETS = 24;

    private final UsageEventMapper usageEventMapper;
    private final TransactionTemplate transactionTemplate;

    public UsageEventServiceImpl(UsageEventMapper usageEventMapper,
                                 TransactionTemplate transactionTemplate) {
        this.usageEventMapper = usageEventMapper;
        this.transactionTemplate = transactionTemplate;
    }

    @Override
    public void record(UsageEventPayload payload) {
        UsageEvent event = new UsageEvent(
            UUID.fromString(payload.eventId()), payload.userId(), payload.scene(), payload.conversationId(),
            payload.candidateId(), payload.promptTokens(), payload.completionTokens(),
            payload.totalTokens(), payload.estimated(), payload.success(), payload.durationMs());
        try {
            transactionTemplate.executeWithoutResult(status -> usageEventMapper.insert(event));
        } catch (DuplicateKeyException e) {
            // event_id 唯一约束兜底：Redis 幂等窗口外的重复投递，静默跳过
            log.debug("Duplicate usage event skipped: eventId={}", payload.eventId());
        }
    }

    @Override
    public PagedResult<UsageEventDTO> getRecords(UsageQueryFilter filter, PageRequest page) {
        LambdaQueryWrapper<UsageEvent> wrapper = new LambdaQueryWrapper<UsageEvent>()
            .eq(filter.userId() != null, UsageEvent::getUserId, filter.userId())
            .eq(filter.scene() != null, UsageEvent::getScene, filter.scene())
            .eq(filter.model() != null, UsageEvent::getModelId, filter.model())
            .eq(filter.conversation() != null, UsageEvent::getConversationId, filter.conversation())
            .ge(filter.start() != null, UsageEvent::getCreatedAt, filter.start())
            .le(filter.end() != null, UsageEvent::getCreatedAt, filter.end())
            .orderByDesc(UsageEvent::getCreatedAt);

        Page<UsageEvent> result = usageEventMapper.selectPage(page.toPage(), wrapper);
        return PagedResult.of(result, UsageEventServiceImpl::toDto);
    }

    @Override
    public UsageSummaryDTO getSummary(UsageQueryFilter filter) {
        UsageSummaryDTO summary = usageEventMapper.selectSummary(filter);
        // 无 GROUP BY 的聚合恒返回一行；防御性兜底空结果
        return summary != null ? summary
            : new UsageSummaryDTO(0, 0, 0, 0, 0, 0, 0, 0);
    }

    @Override
    public List<UsageTimelinePointDTO> getTimeline(TimelineGranularity granularity, UsageQueryFilter filter) {
        OffsetDateTime end = filter.end() != null ? filter.end() : OffsetDateTime.now();
        OffsetDateTime start = filter.start() != null ? filter.start() : end.minusDays(DEFAULT_TIMELINE_DAYS);
        OffsetDateTime earliest = granularity == TimelineGranularity.DAY
            ? end.minusDays(MAX_DAY_BUCKETS) : end.minusMonths(MAX_MONTH_BUCKETS);
        if (start.isBefore(earliest)) {
            start = earliest;
        }
        if (start.isAfter(end)) {
            start = end;
        }
        UsageQueryFilter effective = new UsageQueryFilter(filter.userId(), filter.scene(),
            filter.model(), filter.conversation(), start, end);
        return usageEventMapper.selectTimeline(granularity.truncUnit(), granularity.step(), effective);
    }

    @Override
    public List<UsageStatsDTO> getStats(UsageStatsDim dim, UsageStatsSort sort, UsageStatsOrder order,
                                        UsageQueryFilter filter) {
        return usageEventMapper.selectStats(dim, sort, order, filter);
    }

    private static UsageEventDTO toDto(UsageEvent event) {
        return new UsageEventDTO(
            event.getEventId().toString(),
            event.getUserId(),
            event.getScene(),
            event.getConversationId(),
            event.getModelId(),
            event.getPromptTokens(),
            event.getCompletionTokens(),
            event.getTotalTokens(),
            Boolean.TRUE.equals(event.getEstimated()),
            Boolean.TRUE.equals(event.getSuccess()),
            event.getDurationMs() != null ? event.getDurationMs() : 0,
            event.getCreatedAt());
    }
}
