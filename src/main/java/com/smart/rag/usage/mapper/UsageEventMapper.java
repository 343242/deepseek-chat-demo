package com.smart.rag.usage.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smart.rag.usage.dto.UsageQueryFilter;
import com.smart.rag.usage.dto.UsageStatsDTO;
import com.smart.rag.usage.dto.UsageStatsDim;
import com.smart.rag.usage.dto.UsageStatsOrder;
import com.smart.rag.usage.dto.UsageStatsSort;
import com.smart.rag.usage.dto.UsageSummaryDTO;
import com.smart.rag.usage.dto.UsageTimelinePointDTO;
import com.smart.rag.usage.entity.UsageEvent;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 用量事件 Mapper — 明细分页走 MyBatis-Plus BaseMapper，聚合/时间桶经 XML。
 * <p>
 * dim/sort/order 枚举在 XML 内 &lt;choose&gt; 白名单映射到列名，不拼接用户输入。
 */
@Mapper
public interface UsageEventMapper extends BaseMapper<UsageEvent> {

    /** 总计聚合（请求数/成功率/token 求和/时长） */
    UsageSummaryDTO selectSummary(@Param("filter") UsageQueryFilter filter);

    /** 分组聚合（dim 决定分组列，sort/order 决定排序） */
    List<UsageStatsDTO> selectStats(@Param("dim") UsageStatsDim dim,
                                    @Param("sort") UsageStatsSort sort,
                                    @Param("order") UsageStatsOrder order,
                                    @Param("filter") UsageQueryFilter filter);

    /** 时间桶聚合（generate_series 补零桶；filter.start/end 为桶区间，需非空） */
    List<UsageTimelinePointDTO> selectTimeline(@Param("unit") String unit,
                                               @Param("step") String step,
                                               @Param("filter") UsageQueryFilter filter);
}
