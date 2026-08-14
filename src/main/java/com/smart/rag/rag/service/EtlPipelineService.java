package com.smart.rag.rag.service;

/**
 * ETL Pipeline 服务接口
 * <p>
 * 编排 Extract → Transform(Split) → Load(向量入库) 全流程。
 * 实际入口见 {@code EtlPipelineServiceImpl.executeWithUserId}（由 EtlDispatchService 委托调用）。
 * </p>
 */
public interface EtlPipelineService {
}
