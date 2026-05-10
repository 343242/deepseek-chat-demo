package com.demo.chat.rag.etl;

import java.util.List;

/**
 * ETL 路由策略接口
 * <p>
 * 每种策略定义自己的适用条件（shouldApply）和执行方式（execute）。
 * {@link EtlRouteStrategyFactory} 遍历所有策略，选择第一个匹配的执行。
 * <p>
 * 新增策略只需实现此接口，无需修改 Factory 或其他策略（OCP）。
 */
public interface EtlRouteStrategy {

    /**
     * 该策略适用的排序优先级（数值越小优先级越高）
     * <p>
     * FastTrackStrategy 应优先于 StandardStrategy 判定。
     */
    int getOrder();

    /**
     * 判断是否适用当前策略
     *
     * @param candidates 待处理的文档候选列表
     * @return true 表示该策略可以处理这批文档
     */
    boolean shouldApply(List<EtlCandidate> candidates);

    /**
     * 执行 ETL 处理
     *
     * @param candidates 待处理的文档候选列表
     * @return 处理结果列表（顺序与 candidates 对应）
     */
    List<EtlResult> execute(List<EtlCandidate> candidates);
}
