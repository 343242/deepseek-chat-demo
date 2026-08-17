package com.smart.rag.usage.service;

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

import java.util.List;

/**
 * 用量事件服务 — 写入（消费者落库）+ 查询（四端点数据面）
 */
public interface UsageEventService {

    /**
     * 落库一条用量事件（幂等：event_id 唯一约束冲突静默跳过）。
     * 由 {@code UsageEventConsumer} 调用。
     */
    void record(UsageEventPayload payload);

    /** 明细分页（created_at 倒序） */
    PagedResult<UsageEventDTO> getRecords(UsageQueryFilter filter, PageRequest page);

    /** 总计（请求数/成功率/token 求和/时长） */
    UsageSummaryDTO getSummary(UsageQueryFilter filter);

    /** 时间桶聚合（缺省近 30 天；day 最多 366 桶、month 最多 24 桶，空桶补零） */
    List<UsageTimelinePointDTO> getTimeline(TimelineGranularity granularity, UsageQueryFilter filter);

    /** 分组聚合（dim 分组，sort/order 排序） */
    List<UsageStatsDTO> getStats(UsageStatsDim dim, UsageStatsSort sort, UsageStatsOrder order,
                                 UsageQueryFilter filter);
}
